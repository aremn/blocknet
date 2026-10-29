import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Body, configPath, option } from './body.js';
import { ChatRouter, routeRule } from './rules.js';
import { SessionLog, ResultSummary, perception, failed, lastLessons, oneLine } from './session-log.js';
import { ClaudeBackend } from './claude-backend.js';
import { CodexBackend } from './codex-backend.js';
import { Thinking, personaPrompt } from './thinking.js';
import { listSkills } from './skills.js';
import { Hearing, Speech, pickVoice, voiceConfig, voiceHomes, voiceName } from './voice.js';
import { parseRole, parseTool, needsPlan, PlanRunner, PlanLibrary, plannerPrompt, ACTOR_TOOLS } from './plans.js';
export { buildClaudeArgs } from './claude-backend.js';

const brainDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(brainDir);

export function createBackend(name, options) {
  if (name === 'claude') return new ClaudeBackend(options);
  if (name === 'codex') return new CodexBackend(options);
  throw new Error('--backend must be claude or codex');
}

/** Stream a model-written preamble; no timer-generated speech. */
export async function sendWithAck(backend, text, say) {
  return new Thinking({ say }).run(() => backend.send(text), { backend });
}

/** Errors a provider gives for load, not for the request: worth the same request again shortly. */
const TRANSIENT = /at capacity|overloaded|rate.?limit|too many requests|temporarily unavailable|\b(?:429|500|502|503|504|529)\b/iu;

/** Tool-free roles (planner, chat) retry a busy model twice instead of failing the whole order. */
export async function sendRetrying(backend, text, active = () => true, onRetry = () => {}, delays = [1500, 4000]) {
  for (let attempt = 0; ; attempt++) {
    try { return await backend.send(text); }
    catch (error) {
      if (attempt >= delays.length || !TRANSIENT.test(error.message) || !active()) throw error;
      onRetry(attempt + 1, error);
      backend.close?.();
      await new Promise(r => setTimeout(r, delays[attempt]));
      if (!active()) throw error;
    }
  }
}

/** Keep inventory/held/low-health facts from the revision-based changes tool. */
export class CompactState {
  facts = { inventory: 'unknown', held: 'empty', health: '', depth: '' };
  previous = '';
  update(delta) {
    const text = String(delta ?? '');
    const body = text.split(/body r\d+: /u).slice(1).at(-1)?.split('\n')[0];
    if (body) {
      const health = body.match(/health ([\d.]+)\/([\d.]+)/u), food = body.match(/food (\d+)/u), air = body.match(/air (-?\d+)\/(\d+)/u);
      this.facts.health = [health && `health ${health[1]}/${health[2]}${+health[1] / +health[2] < 0.4 ? ' (low)' : ''}`,
        food && `food ${food[1]}/20`, air && `air ${Math.max(0, +air[1])}/${air[2]}`, /\bin_water\b/u.test(body) && 'in water']
        .filter(Boolean).join(', ');
      this.facts.depth = body.match(/\by=-?\d+(?:, surface y=-?\d+)?(?:, [\w:]+ best ~-?\d+, \d+ below)?/u)?.[0] ?? '';
    }
    const inventory = text.match(/inventory r\d+: ([\s\S]*?)(?=\n(?:body|task) r\d+:|$)/u)?.[1];
    if (inventory) {
      this.facts.held = inventory.match(/(?:^|; )main: (?:\d+ )?([\w:./-]+)/mu)?.[1] ?? 'empty';
      this.facts.inventory = inventory.split(/\n|; /u).filter(line => line.includes(' slots [')).map(line => {
        const match = line.match(/^(?:(\d+) )?([\w:./-]+)/u);
        return match ? `${match[1] ?? 1} ${match[2]}` : '';
      }).filter(Boolean).join(', ') || 'empty';
    }
    const current = `inventory: ${this.facts.inventory}; held: ${this.facts.held}${this.facts.health ? `; ${this.facts.health}` : ''}${this.facts.depth ? `; ${this.facts.depth}` : ''}`;
    if (current === this.previous) return '';
    this.previous = current;
    return `\nState: ${current}`;
  }
  reset() { this.previous = ''; }
}

export function shortReply(text) {
  text = String(text ?? '').trim();
  if (!text || text === '<silent>') return '';
  return text.replace(/\s+/gu, ' ').split(' ').slice(0, 12).join(' ').slice(0, 200);
}

/** One queue owns the model; chat always precedes the next autonomous turn. */
export class BrainSession {
  constructor({ body, backend, bot, aliases = [], listen = 'all', goal = '', maxTurns = 200,
    maxMinutes = 60, pauseMs = 1200, log, auditFile, lessonsFile, usageFile, plannerBackend, strongPlannerBackend, plansFile, persona, chatBackend,
    hearing, speech, quickBackend, quickMs = 6000,
    actorIdleMs = 45_000 }) {
    if (!Number.isInteger(maxTurns) || maxTurns < 1 || !Number.isFinite(maxMinutes) || maxMinutes <= 0)
      throw new Error('Limits must be positive (--max-turns integer, --max-minutes number)');
    Object.assign(this, { body, backend, bot, goal, maxTurns, maxMinutes, pauseMs, log, auditFile, lessonsFile, usageFile });
    this.router = new ChatRouter(bot, aliases, listen);
    this.compact = new CompactState(); this.summary = new ResultSummary();
    this.recentResults = []; this.sessionTurns = 0; this.conversation = []; this.actorIdleMs = actorIdleMs;
    this.queue = []; this.events = []; this.busy = false; this.generation = 0; this.turns = 0;
    this.paused = false; this.ended = false; this.closed = false;
    this.thinking = new Thinking({ say: (text, player) => this.opened('preamble') ? this.say(text, player) : undefined, pool: persona,
      active: () => !this.closed && !this.paused && !this.awaitingRespawn });
    this.quickBackend = quickBackend; this.quickMs = quickMs;
    this.plannerBackend = plannerBackend; this.strongPlannerBackend = strongPlannerBackend;
    this.hearing = hearing; this.speech = speech;
    // Chat must remain available while the step actor is waiting on a body task.
    // Real backends get an independent, tool-free session of the actor model.
    this.chatBackend = chatBackend ?? (backend instanceof CodexBackend || backend instanceof ClaudeBackend
      ? createBackend(backend.backend, { ...backend.options, role: 'chat', mcpServer: undefined,
        mcpConfig: '{"mcpServers":{}}', effort: 'minimal', timeoutMs: 15_000 }) : backend);
    if (plannerBackend) this.plans = new PlanRunner({
      call: (tool, args) => this.call(tool, args), tools: body.hello?.tools,
      library: new PlanLibrary(plansFile ?? resolve(brainDir, 'logs/plans.jsonl')),
      planner: text => this.sendRole('planner', plannerBackend, text),
      strongPlanner: strongPlannerBackend && (text => this.sendRole('planner', strongPlannerBackend, text)),
      actor: text => this.sendRole('actor', backend, text),
      resetActor: () => { backend.close(); this.sessionTurns = 0; this.compact.reset(); },
      say: (text, player) => this.say(text, player),
      giveUp: (goal, reason, player) => this.giveUp(goal, reason, player),
      log: (type, data) => { this.log?.write(type, data); this.notePlan(type, data); },
      feedback: gaps => this.plannerFeedback(gaps),
      isActive: () => !this.closed && !this.paused && !this.awaitingRespawn,
    });
    this.traceBackend('actor', backend);
    this.traceBackend('planner', plannerBackend);
    this.traceBackend('chat', this.chatBackend);
    this.onEvent = event => this.event(event);
    this.onReady = () => { this.checkBodyCapabilities(); this.schedule(); };
    this.onDisconnect = () => { clearTimeout(this.nextTimer); this.log?.write('connection', { state: 'disconnected' }); };
    body.on('event', this.onEvent); body.on('ready', this.onReady); body.on('disconnected', this.onDisconnect);
  }
  async start() {
    this.started = Date.now();
    if (this.goal) this.deadline = setTimeout(() => this.endAutoplay('max minutes'), this.maxMinutes * 60_000);
    try { await this.body.ready(); this.checkBodyCapabilities(); this.schedule(); }
    catch (error) { this.diagnostic(error); } // Body continues reconnecting even after its initial ready timeout.
    return this;
  }
  diagnostic(error) { process.stderr.write(`${error.message ?? error}\n`); }
  checkBodyCapabilities() {
    const tools = this.body.hello?.tools;
    if (!tools) return;
    if (this.plans) this.plans.tools = tools;
    const names = tools.map(t => t.name).sort(), signature = JSON.stringify(names);
    if (signature === this.bodyCapabilities) return;
    this.bodyCapabilities = signature;
    const missing = ACTOR_TOOLS.filter(name => !names.includes(name));
    this.log?.write('body_capabilities', { tools: names, missing_actor_tools: missing });
    if (names.includes('obtain') && !names.includes('give')) {
      this.diagnostic('Outdated body: give is missing. Deploy the current mod jar and restart Minecraft.');
      this.say('Загружен старый мод: обнови jar и перезапусти игру.').catch(e => this.diagnostic(e));
    }
  }
  /** One line of the brain's reasoning for the bot's in-game trace and overlay (never awaited). */
  note(source, text) {
    try { this.body.note?.(this.bot, source, oneLine(text).slice(0, 600)); } catch { /* debug only */ }
  }
  /** Full model inputs, outputs, reasoning and in-turn tool calls: the session log keeps them all. */
  trace(role, kind, data) {
    try {
      const clip = value => typeof value === 'string' ? value.slice(0, 20_000)
        : value && typeof value === 'object' ? JSON.parse(JSON.stringify(value, (k, v) => typeof v === 'string' ? v.slice(0, 20_000) : v)) : value;
      this.log?.write('model_trace', { model_role: role, kind, ...Object.fromEntries(Object.entries(data).map(([k, v]) => [k, clip(v)])) });
    } catch (error) { this.diagnostic(`trace: ${error.message}`); }
  }
  traceBackend(role, backend) {
    if (!backend || backend.onTrace) return;
    const resultText = value => typeof value === 'string' ? value
      : Array.isArray(value?.content) ? value.content.map(c => c?.text ?? '').join(' ')
      : Array.isArray(value) ? value.map(c => c?.text ?? (typeof c === 'string' ? c : '')).join(' ') : JSON.stringify(value ?? '');
    backend.onTrace = (kind, data) => {
      if (role === 'actor' && kind === 'tool') this.activeActor?.progress();
      this.trace(role, kind, data);
      if (kind === 'reasoning') this.note(role, `thinks: ${data.text}`);
      else if (kind === 'tool') this.note(role, `calls ${data.tool} ${JSON.stringify(data.arguments ?? {})}${data.result ? ` -> ${resultText(data.result).slice(0, 200)}` : ''}`);
      else if (kind === 'tool_result') this.note(role, `tool result: ${resultText(data.result).slice(0, 200)}`);
      else if (kind === 'search') this.note(role, `searches the web: ${data.query}`);
    };
  }
  notePlan(type, data = {}) {
    const steps = plan => (plan?.steps ?? []).map(s => `${s.id}. ${s.do}${s.else ? ` (else ${s.else})` : ''}`).join('; ');
    // Steps first: the overlay keeps 600 characters and a long goal must not hide them.
    if (type === 'plan') this.note('plan', `${steps(data.plan)}${data.failures?.length ? ` (after ${data.failures.length} failure(s))` : ''}; goal: ${oneLine(data.plan?.goal).slice(0, 160)}`);
    else if (type === 'plan_step_start') this.note('step', data.status);
    else if (type === 'plan_step') this.note('critic', `step ${data.step?.id} verified: ${data.status}`);
    else if (type === 'plan_fallback') this.note('critic', `step ${data.step?.id} failed: ${data.reason}; fallback: ${data.fallback}`);
    else if (type === 'plan_failure') this.note('critic', `plan failed (${data.failures}/3): ${data.reason}`);
    else if (type === 'plan_revised') this.note('plan', `revised after hint ${JSON.stringify(data.hint)}: ${steps(data.plan)}`);
    else if (type === 'plan_reused') this.note('plan', `reusing a saved plan for: ${data.goal}`);
    else if (type === 'plan_chat_error') this.note('plan', `chat during plan failed: ${data.error}`);
  }
  languageRule() {
    return `Answer in the language the player last used, including autoplay chat. Before any player message, use ${this.thinking.pool.defaultLanguage ?? 'ru'}. Latest player message: ${JSON.stringify(this.lastPlayerMessage ?? null)}.\n${this.dialogue()}`;
  }
  remember(who, text) {
    this.conversation.push({ who, text: String(text).slice(0, 600) });
    this.conversation = this.conversation.slice(-6);
  }
  dialogue() { return `Recent conversation (speaker-labelled, including questions and answers): ${JSON.stringify(this.conversation)}\n`; }

  /** Cancel even a backend that does not settle send() when closed. Tools may legitimately run longer. */
  async actorTurn(work, request, player) {
    const controller = new AbortController(), generation = this.generation;
    const active = this.activeActor = { controller, request, player, generation, tool: false };
    let timer, poll, abort;
    const clearWatch = () => { clearTimeout(timer); clearInterval(poll); };
    active.progress = () => { active.tool = true; clearWatch(); };
    // MCP lives in another process. Audit writes occur at tool invocation, before waiting on the body.
    if (this.auditFile) poll = setInterval(() => {
      readFile(this.auditFile, 'utf8').then(text => { if (text.trim()) active.progress(); }).catch(() => {});
    }, Math.min(250, this.actorIdleMs));
    try {
      return await Promise.race([Promise.resolve().then(work), new Promise((_, reject) => {
        abort = () => reject(new Error('Actor turn cancelled'));
        controller.signal.addEventListener('abort', abort, { once: true });
        timer = setTimeout(() => {
          if (active.tool || generation !== this.generation) return;
          // Reject before close(), whose backend-specific error must not mask the timeout.
          reject(new Error('Actor idle timeout'));
          this.backend.close();
        }, this.actorIdleMs);
      })]);
    } catch (error) {
      if (error.message === 'Actor idle timeout' && generation === this.generation && !this.closed) {
        this.generation++; this.sessionTurns = 0; this.compact.reset();
        const entry = { time: new Date().toISOString(), bot: this.bot, kind: 'bug', source: 'actor',
          note: `Actor produced no tool call or final reply within ${this.actorIdleMs} ms. Request: ${request}`, request };
        this.log?.write('tool_feedback', entry);
        const file = resolve(brainDir, 'logs/tool-feedback.jsonl');
        await mkdir(dirname(file), { recursive: true });
        await appendFile(file, `${JSON.stringify(entry)}\n`);
        await this.say('Застрял на этом запросе, попробуй сказать иначе.', player);
      }
      throw error;
    } finally {
      clearWatch(); controller.signal.removeEventListener('abort', abort);
      if (this.activeActor === active) this.activeActor = null;
    }
  }
  async sendRole(role, backend, text) {
    const generation = this.generation;
    return this.thinking.run(() => this.sendRoleWithScene(role, backend, this.languageRule() + text, generation), {
      backend,
      languageText: this.lastPlayerMessage?.text, player: this.lastPlayerMessage?.from,
      isActive: () => generation === this.generation && (role !== 'planner' || this.plans?.running),
    });
  }
  async sendRoleWithScene(role, backend, text, generation) {
    const started = Date.now(); let result, failure, timer;
    // Read memory once while preparing the planner request; scene never replaces a task or moves.
    const [scene, map] = await Promise.all([
      role === 'planner' && this.body.hello?.tools?.some(t => t.name === 'scene')
        ? this.call('scene', { delta: false }).then(r => r.result).catch(() => 'unavailable') : null,
      role === 'planner' && this.body.hello?.tools?.some(t => t.name === 'map')
        ? this.call('map', {}).then(r => r.result).catch(() => null) : null,
      this.auditFile && role !== 'chat' ? writeFile(this.auditFile, '') : null,
      role === 'planner' ? backend.prepare?.() : null,
    ]);
    if (generation !== this.generation || this.closed) throw new Error('Model request cancelled');
    if (scene) text += `\nScene observed while preparing this plan: ${scene}`;
    if (map) text += `\nMap (places, caves, creatures; no ores): ${map}`;
    if (role === 'planner') {
      const saved = await listSkills().catch(() => []);
      if (saved.length) text += `\nSaved skills: ${saved.slice(0, 20).map(s => `${s.name}(${Object.keys(s.params ?? {}).join(', ')}): ${s.description}`).join('; ')}`;
    }
    this.log?.write('model_message', { role: 'user', model_role: role, text: text.slice(0, 300) });
    this.trace(role, 'input', { text });
    this.note(role, `thinking about: ${text.match(/^(?:Goal|Step|Player \S+ message):[^\n]*/mu)?.[0] ?? oneLine(text).slice(0, 200)}`);
    try {
      if (this.goal && !this.ended && this.turns >= this.maxTurns) throw new Error('max turns reached');
      this.turns++;
      result = role === 'actor'
        ? await this.actorTurn(() => backend.send(text), text, this.lastPlayerMessage?.from)
        : await Promise.race([sendRetrying(backend, text, () => generation === this.generation && !this.closed,
          (attempt, error) => this.log?.write('model_retry', { role, attempt, error: error.message })), new Promise((_, reject) => {
          timer = setTimeout(() => { reject(new Error(`${role} budget exceeded`)); backend.close(); }, role === 'planner' ? 120_000 : 15_000);
        })]);
      this.log?.write('model_message', { role: 'final', model_role: role, text: String(result.text ?? '').slice(0, 300) });
      this.trace(role, 'output', { text: String(result.text ?? ''), ms: result.ms ?? null });
      this.note(role, `answered in ${((result.ms ?? Date.now() - started) / 1000).toFixed(1)} s: ${String(result.text ?? '')}`);
      return result;
    } catch (error) { failure = error.message; this.note(role, `failed: ${error.message}`); throw error; }
    finally {
      clearTimeout(timer);
      const audit = this.auditFile && role !== 'chat' ? await readFile(this.auditFile, 'utf8').catch(() => '') : '';
      const entry = { bot: this.bot, role, backend: backend.backend, model: backend.model,
        ms: result?.ms ?? Date.now() - started, ttft_ms: result?.ttftMs ?? null, usage: result?.usage ?? null,
        tool_calls: audit.trim() ? audit.trim().split('\n').length : result?.toolCalls ?? 0, error: failure ?? null };
      this.log?.write('turn', entry);
      if (this.usageFile) await appendFile(this.usageFile, `${JSON.stringify({ time: new Date(started).toISOString(), ...entry })}\n`);
    }
  }
  sendChat(text, generation) {
    const pending = (this.chatRequests ?? Promise.resolve()).then(() =>
      this.sendRoleWithScene('chat', this.chatBackend, text, generation).catch(error => {
        // Not every model accepts the cheapest reasoning level; fall back to low once and keep it.
        if (this.chatBackend?.options?.effort !== 'minimal' || !/effort|reasoning|unsupported|invalid/iu.test(error.message)) throw error;
        this.log?.write('chat_effort_fallback', { error: error.message });
        this.chatBackend.close?.();
        this.chatBackend = createBackend(this.chatBackend.backend, { ...this.chatBackend.options, effort: 'low' });
        return this.sendRoleWithScene('chat', this.chatBackend, text, generation);
      }));
    this.chatRequests = pending.catch(() => {});
    return pending;
  }
  async plannerFeedback(gaps) {
    if (!gaps?.length) return;
    const file = resolve(brainDir, 'logs/tool-feedback.jsonl');
    await mkdir(dirname(file), { recursive: true });
    for (const gap of gaps) {
      const entry = { time: new Date().toISOString(), bot: this.bot, session: this.log?.id,
        source: 'planner', kind: 'missing', want: gap.want, why: gap.why, tool: gap.want, note: gap.why };
      await appendFile(file, `${JSON.stringify(entry)}\n`);
      this.log?.write('tool_feedback', entry);
    }
  }
  async call(tool, args = {}) {
    const started = Date.now(), id = `brain-${started}-${this.sequence = (this.sequence ?? 0) + 1}`;
    this.log?.write('tool_call', { id, tool, args });
    try {
      const result = await this.body.call(this.bot, tool, args);
      if (result.task_id) {
        const outcome = this.body.waitTask(result.task_id);
        outcome.then(text => this.log?.write('tool_result', { id, tool, result: this.summary.summarize(tool, text), failed: failed(text), ms: Date.now()-started }),
          error => this.log?.write('tool_result', { id, tool, result: error.message, failed: true, ms: Date.now()-started }));
        return { ...result, outcome };
      }
      this.log?.write('tool_result', { id, tool, result: this.summary.summarize(tool, result.result ?? `task ${result.task_id}`), failed: failed(result.result), ms: Date.now()-started });
      return result;
    } catch (error) { this.log?.write('tool_result', { id, tool, result: error.message, failed: true, ms: Date.now()-started }); throw error; }
  }
  async say(text, player) {
    if (!text || this.closed) return;
    await this.call('say', { text, ...(player && { player }) });
    if (this.speech) this.speak(text);
    this.remember(`bot ${this.bot}`, text);
    this.router.spoke(this.bot, player);
  }
  schedule() {
    clearTimeout(this.nextTimer);
    if (this.awaitingRespawn || this.closed || this.busy || !this.goal || this.paused || this.ended || !this.body.hello) return;
    if (!this.body.hello.bots?.some(name => name.toLowerCase() === this.bot.toLowerCase())) return;
    this.nextTimer = setTimeout(() => {
      if (this.queue.length === 0) this.queue.push({ autoplay: true, from: 'Goal', text: this.goal });
      this.processQueue().catch(e => this.diagnostic(e));
    }, this.pauseMs);
  }
  cancel(clear = true) {
    this.generation++; this.activeActor?.controller.abort(); this.backend.close(); this.sessionTurns = 0; this.compact.reset();
    this.plannerBackend?.close(); this.strongPlannerBackend?.close(); this.plans?.cancel();
    if (this.chatBackend !== this.backend) this.chatBackend?.close();
    clearTimeout(this.nextTimer);
    if (clear) this.queue.length = 0;
  }
  endAutoplay(reason) {
    if (this.ended || !this.goal) return;
    this.ended = true; clearTimeout(this.deadline); clearTimeout(this.nextTimer);
    this.log?.write('autoplay_end', { reason, turns: this.turns, ms: Date.now()-this.started });
    if (reason === 'max minutes') {
      this.cancel(false);
      this.call('stop').catch(e => this.diagnostic(e));
    }
    this.queue = this.queue.filter(m => !m.autoplay);
    this.queue.push({ reflection: true, from: 'Session', text: 'Reflect on this session.' });
    this.processQueue().catch(e => this.diagnostic(e));
  }
  /** The body is in trouble and already acting; the bot yells for help in its own words, once. */
  shout(note) {
    if (this.closed || Date.now() - (this.lastShout ?? 0) < 15_000) return;
    this.lastShout = Date.now();
    const generation = this.generation;
    this.sendChat(this.languageRule() + `You are in real danger right now: ${note}. Your body is already trying to get out. `
      + 'Yell ONE very short, panicked line to the player in character (a cry for help, not a status report). Reply with the line only.', generation)
      .then(result => { const line = shortReply(result.text); if (line) return this.say(line, this.lastPlayerMessage?.from); })
      .catch(error => this.log?.write('voice_error', { stage: 'shout', error: error.message }));
  }
  /**
   * An order failed for good. The player hears it in the bot's own words, not a stack of tool
   * reports in English; the raw reason is already in the trace and the session log.
   */
  async giveUp(goal, reason, player) {
    const generation = this.generation;
    let line = '';
    try {
      const result = await this.sendChat(this.languageRule() + `You gave up on the order "${oneLine(goal).slice(0, 200)}". `
        + `Why, from your body's reports: ${oneLine(reason).slice(0, 600)}. Tell the player in ONE short spoken line, in character, `
        + 'what went wrong in plain words (no item ids, tool names or English error text), and what would help, if anything. Reply with the line only.', generation);
      line = shortReply(result.text);
    } catch (error) { this.log?.write('give_up_error', { error: error.message }); }
    if (generation !== this.generation) return;
    await this.say(line || 'Не вышло, застрял. Глянь, что не так, или скажи по-другому.', player);
  }
  /** "goto p1"-style tool syntax runs without the model and needs no spoken reaction. */
  typedCommand(order) {
    try { return !!parseTool(order, this.body.hello?.tools ?? this.plans?.tools); } catch { return false; }
  }
  /**
   * The bot's mouth: one spoken line within about a second, from a separate tool-free session that
   * sees only the persona, the last six lines of dialogue and the compact state. The actor or
   * planner works in parallel; whichever opening line comes first is said, the other is dropped.
   */
  quickReply(order, player) {
    if (!this.quickBackend || this.closed) return;
    const version = this.playerMessageVersion, generation = this.generation, started = Date.now();
    this.opening = { version, state: 'open' };
    // An older pending line is stale now; an idle session stays warm for this one.
    if (this.quickPending) this.quickBackend.close?.();
    this.quickPending = true;
    const prompt = this.languageRule() + `State: ${JSON.stringify(this.compact.facts)}. Current job: ${this.plans?.status() ?? 'none'}.\n`
      + `Player ${JSON.stringify(player)} just said: ${JSON.stringify(order)}.\n`
      + 'React at once like a friend in voice chat: ONE short spoken line, at most 10 words. For an order, say you are on it '
      + '(no results, places or amounts you do not know yet). For a question you can answer from the state above, answer it. '
      + 'Otherwise reply naturally. No item ids, no lists. Reply with the line only, or <silent>.';
    let timer;
    Promise.race([this.quickBackend.send(prompt), new Promise((_, reject) => {
      timer = setTimeout(() => { reject(new Error('quick reply too slow')); this.quickBackend.close?.(); }, this.quickMs);
    })])
      .then(result => {
        const line = shortReply(result.text);
        this.log?.write('quick_reply', { ms: Date.now() - started, ttft_ms: result.ttftMs ?? null, text: line, tokens: result.usage?.inputTokens ?? null });
        if (!line || generation !== this.generation || this.playerMessageVersion !== version || !this.opened('quick')) return;
        return this.say(line, player);
      })
      .catch(error => this.log?.write('quick_reply', { ms: Date.now() - started, error: error.message }))
      .finally(() => {
        clearTimeout(timer);
        if (this.opening?.version === version) this.quickPending = false;
        // Start the next session now (Codex re-opens after its turn limit), not when a player speaks.
        if (!this.closed) Promise.resolve(this.quickBackend.prepare?.()).catch(() => {});
      });
  }
  /**
   * One opening line per player message when the quick mouth is racing the model's preamble.
   * The later of the two is a duplicate and stays silent; later preambles (replans) are said.
   */
  opened(by) {
    const opening = this.opening;
    const current = !!opening && opening.version === this.playerMessageVersion;
    if (by === 'quick') {
      if (!current || opening.state !== 'open') return false;
      opening.state = 'quick'; return true;
    }
    if (!current) return true;
    if (opening.state === 'open') { opening.state = 'preamble'; return true; }
    // The quick line already answered this message: drop the first preamble, keep later ones.
    if (opening.state === 'quick') { opening.state = 'done'; return false; }
    return true;
  }
  /** /blocknet voice [name|next]: lists the installed voices or switches to one, and says so in it. */
  async chooseVoice({ from, choice }) {
    if (!this.speech) return this.say('Голоса не установлены: запусти brain/setup-voice.ps1.', from || undefined);
    const voices = this.speech.list();
    const picked = pickVoice(voices, this.speech.voice, choice);
    const names = voices.map(voiceName).join(', ');
    if (picked === null) return this.say(`Голоса: ${names}. Сейчас ${voiceName(this.speech.voice)}.`, from || undefined);
    if (!picked) return this.say(`Нет голоса «${choice}». Есть: ${names}.`, from || undefined);
    if (voices.length === 1) return this.say(`У меня один голос, ${names}. Ещё ставит brain/setup-voice.ps1 -Voices all.`, from || undefined);
    await this.speech.use(picked);
    this.log?.write('voice_changed', { voice: picked, from });
    await this.say(`Так лучше? Это голос ${voiceName(picked)}.`, from || undefined);
  }
  /** Says the same words aloud through the body's voice chat; text chat has already gone out. */
  speak(text) {
    this.speech.synthesize(text)
      .then(wav => this.call('speak', { wav: wav.toString('base64') }))
      .catch(error => this.log?.write('voice_error', { stage: 'speak', error: error.message }));
  }
  /** A phrase a player said near this bot: transcribe it, show what was understood, then treat it as chat. */
  async heard(event) {
    const started = Date.now();
    try {
      const text = await this.hearing.transcribe(Buffer.from(event.wav, 'base64'));
      this.log?.write('voice_heard', { from: event.from, ms: event.ms, text, transcribe_ms: Date.now() - started });
      if (!text || this.closed) return;
      this.call('heard', { player: event.from, text }).catch(() => {});
      const { wav, bots, ms, ...chat } = event;
      this.event({ ...chat, type: 'chat', text, voice: true });
    } catch (error) { this.log?.write('voice_error', { stage: 'hear', error: error.message }); }
  }
  event(event) {
    if (event.type === 'voice') {
      if (!event.bots?.some(name => name.toLowerCase() === this.bot.toLowerCase())) return;
      if (this.hearing) this.heard(event);
      else this.log?.write('voice_error', { stage: 'hear', error: 'speech recognition is not installed (brain/setup-voice.ps1)', from: event.from, ms: event.ms });
      return;
    }
    if (event.bot && event.bot.toLowerCase() !== this.bot.toLowerCase() && event.type !== 'bot.spoke') return;
    this.log?.write(event.type === 'chat' ? 'chat' : 'event', event.type === 'chat' ? { from: event.from, text: event.text } : { event });
    if (event.type === 'bot.spoke') { this.router.spoke(event.bot, event.player); return; }
    if (event.type === 'voice.choose') { this.chooseVoice(event).catch(error => this.log?.write('voice_error', { stage: 'choose', error: error.message })); return; }
    if (event.type === 'bots.changed') { this.schedule(); return; }
    if (event.type === 'chat') {
      this.events.push(perception(event)); if (this.events.length > 16) this.events.shift();
    }
    if (event.type !== 'chat') {
      if (event.type === 'moment' || event.type === 'task.finished' || event.type === 'bot.died' || event.type === 'bot.respawned') {
        this.events.push(perception(event)); if (this.events.length > 16) this.events.shift();
      }
      if (event.type === 'task.finished') {
        const kill = /\bkilled e\d+ ([\w:-]+)/u.exec(String(event.result));
        if (kill) this.plans?.recordKill(kill[1]);
        this.recentResults.push(String(event.result).slice(0, 300));
        this.recentResults = this.recentResults.slice(-3);
      }
      if (event.type === 'bot.died') {
        this.awaitingRespawn = true; this.cancel(false);
        this.events.push(`you died at ${event.position} (${event.cause}); ${event.items_dropped === false ? 'inventory kept' : 'items dropped there'}`);
      }
      if (event.type === 'bot.respawned') {
        this.awaitingRespawn = false;
        this.processQueue().catch(e => this.diagnostic(e)); this.schedule();
      }
      if (event.type === 'moment' && event.kind === 'emergency') this.shout(event.note);
      if (event.type === 'moment' && event.kind === 'danger_failed' && this.goal && !this.paused && !this.ended) {
        this.cancel(false);
        this.queue.push({ autoplay: true, from: 'Body', text: this.goal });
        this.processQueue().catch(e => this.diagnostic(e));
      }
      return;
    }
    const order = this.router.route(event.text, event.from, this.body.hello?.bots ?? [this.bot]);
    if (!order) return;
    this.playerMessageVersion = (this.playerMessageVersion ?? 0) + 1;
    this.lastPlayerMessage = { from: event.from, text: event.text,
      ...(event.pointed_block && { pointed_ref: event.pointed_ref, pointed_block: event.pointed_block, pointed_dimension: event.pointed_dimension,
        provenance: 'player pointing hint, not bot eye evidence' }) };
    this.remember(`player ${event.from}`, order);
    clearTimeout(this.nextTimer);
    const rule = routeRule(order, event.from);
    if (/^(?:план|plan)\?$/iu.test(order.trim())) {
      const status = async () => {
        if (this.plans?.step) await this.plans.snapshot();
        const generation = this.generation;
        const result = await this.sendChat(this.languageRule()
          + `Return ONLY JSON with a reply field: a brief in-character answer about plan progress. Context: ${this.plans?.status() ?? 'plan: none'}`, generation);
        if (generation === this.generation) await this.say(shortReply(JSON.parse(result.text).reply), event.from);
      };
      status().catch(e => this.diagnostic(e)); return;
    }
    if (rule?.tool === 'stop') {
      this.paused = true; this.cancel();
      const generation = this.generation;
      this.runRule(rule, event.from).then(() => {
        if (!rule.nextOrder || generation !== this.generation || this.closed) return;
        this.paused = false;
        this.queue.push({ from: event.from, text: rule.nextOrder });
        return this.processQueue();
      }).catch(e => this.diagnostic(e));
      this.log?.write('autoplay_pause');
      return;
    }
    if (/^(?:continue|продолжай)[.!?]*$/iu.test(order.trim())) {
      this.paused = false; this.log?.write('autoplay_resume'); this.schedule(); return;
    }
    if (this.plans?.running) {
      const generation = this.generation;
      this.plans.interact(async () => {
        const context = await this.plans.context();
        const result = await this.sendChat(
          this.languageRule() + `Classify this player message during the current plan. Do not call tools or act in the world.\n`
          + `Return ONLY JSON: {"kind":"hint|new_goal|question|chat","reply":"one short sentence or empty","goal":"only for new_goal"}.\n`
          + `For a hint, reply briefly acknowledging what you understood; the planner reviews it next.\n`
          + `hint = information relevant to the current goal/step; new_goal = asks to do something else; question = answer using the context; chat = brief small talk or silence.\n`
          + `${context}\nPlayer ${JSON.stringify(event.from)} message: ${JSON.stringify(order)}`, generation);
        if (generation !== this.generation || this.closed) return;
        this.plans.assertActive();
        const classified = JSON.parse(result.text);
        if (classified.kind === 'hint') {
          await this.say(shortReply(classified.reply), event.from);
          await this.plans.hint(order, event.from);
        }
        else if (classified.kind === 'new_goal') {
          await this.say(shortReply(classified.reply), event.from);
          this.queue = this.queue.filter(m => !m.autoplay);
          this.queue.unshift({ from: event.from, text: classified.goal?.trim() || order, needsPlan: true });
          this.cancel(false);
          await this.call('stop');
        } else if (['question', 'chat'].includes(classified.kind)) await this.say(shortReply(classified.reply), event.from);
      }).catch(e => { if (generation === this.generation && !this.plans.controller.signal.aborted) this.diagnostic(e); });
      return;
    }
    if (this.busy) {
      this.interruptActor(order, event.from).catch(e => this.diagnostic(e));
      return;
    }
    this.queue = this.queue.filter(m => !m.autoplay);
    const reflection = /^(?:итоги|lessons|summary)[.!?]*$/iu.test(order.trim());
    this.queue.push(reflection ? { reflection: true, from: event.from, text: order }
      : rule ? { rule, from: event.from } : { from: event.from, text: order });
    if (!reflection && !rule && !this.typedCommand(order)) this.quickReply(order, event.from);
    this.processQueue().catch(e => this.diagnostic(e));
  }
  async interruptActor(order, player, version = this.playerMessageVersion) {
    const generation = this.generation;
    const request = this.activeActor?.request;
    const result = await this.sendChat(this.languageRule()
      + 'Classify this message while an actor/body action is running. Do not call tools.\n'
      + 'Return ONLY JSON: {"kind":"stop|new_order|hint|question|chat","reply":"one short sentence or empty"}.\n'
      + 'stop = cancel; new_order = replace the current request (including bring/give owned items instead of mining); '
      + 'hint = information for this action; question/chat = answer from context without stopping.\n'
      + `Running request: ${JSON.stringify(this.activeActor?.request)}\nState: ${JSON.stringify(this.compact.facts)}\n`
      + `Player ${JSON.stringify(player)} message: ${JSON.stringify(order)}`, generation)
      .catch(error => {
        if (generation === this.generation) this.diagnostic(error);
        return { text: '{"kind":"new_order","reply":""}' };
      });
    if (generation !== this.generation || version !== this.playerMessageVersion || this.closed) return;
    let classified;
    try {
      classified = JSON.parse(result.text);
      if (!['stop', 'new_order', 'hint', 'question', 'chat'].includes(classified.kind)) throw new Error('Invalid chat classification');
    } catch (error) { this.diagnostic(error); classified = { kind: 'new_order', reply: '' }; }
    if (['stop', 'new_order', 'hint'].includes(classified.kind)) {
      // Backends cannot safely inject into an outstanding MCP call: hints become a fresh turn.
      this.cancel(false); this.queue = this.queue.filter(m => !m.autoplay);
      const replacement = this.generation;
      await this.call('stop');
      if (replacement !== this.generation || version !== this.playerMessageVersion || this.closed) return;
      this.paused = classified.kind === 'stop';
      await this.say(shortReply(classified.reply), player);
      if (classified.kind !== 'stop') this.queue.unshift({ from: player,
        text: classified.kind === 'hint' ? `${request}\nPlayer hint: ${order}` : order });
      await this.processQueue();
    } else if (['question', 'chat'].includes(classified.kind)) await this.say(shortReply(classified.reply), player);
  }
  async runRule(rule, player) {
    const result = await this.call(rule.tool, rule.args);
    await this.say(result.result && /failed|blocked/iu.test(result.result) ? result.result : '', player);
    if (result.outcome) result.outcome.then(text => {
      if (/failed|blocked/iu.test(text)) return this.say(text, player);
    }).catch(e => this.diagnostic(e));
  }
  async processQueue() {
    if (this.busy || this.closed || this.awaitingRespawn) return;
    this.busy = true;
    try {
      while (this.queue.length && !this.closed && !this.awaitingRespawn) {
        const message = this.queue.shift();
        if (message.rule) { await this.runRule(message.rule, message.from).catch(e => this.diagnostic(e)); continue; }
        if (message.autoplay && (this.paused || this.ended)) continue;
        if (!message.reflection && this.goal && !this.ended && this.turns >= this.maxTurns) {
          this.endAutoplay('max turns');
          if (message.autoplay) continue;
        }
        if (this.plans && !message.reflection) {
          this.plans.tools = this.body.hello?.tools ?? this.plans.tools;
          let direct;
          try { direct = !message.autoplay && !message.needsPlan && parseTool(message.text, this.plans.tools); }
          catch { /* A tool verb followed by natural language belongs to the planner. */ }
          if (direct) { await this.runRule(direct, message.from); continue; }
          if (message.needsPlan || message.autoplay || needsPlan(message.text, this.plans.tools)) {
            const completed = await this.plans.run(message.autoplay ? this.goal : message.text, message.autoplay ? undefined : message.from);
            if (completed === true && !message.autoplay && this.plans.plan?.goal) {
              // A finished job is announced (silence after the last step looked like a hang). The
              // planner wrote the goal in the player's language, so no model call is needed.
              await this.say(`✔ ${shortReply(this.plans.plan.goal)}`, message.from);
            }
            if (message.autoplay && completed !== null) this.endAutoplay('plan finished');
            else if (this.goal && this.turns >= this.maxTurns) this.endAutoplay('max turns');
            continue;
          }
        }
        const generation = this.generation, started = Date.now();
        let result, failure, called = false;
        try {
          if (!this.backend.process || this.backend.process.error || this.backend.turns >= this.backend.maxTurns) this.compact.reset();
          let state = '', scene = '';
          if (!message.reflection) {
            const delta = await this.call('changes');
            state = this.compact.update(delta.result);
            if (this.body.hello?.tools?.some(t => t.name === 'scene')) {
              scene = (await this.call('scene', { delta: true, goal: message.autoplay ? this.goal : message.text })).result;
            }
          }
          if (generation !== this.generation || this.closed) continue;
          const events = this.events.splice(0);
          const content = message.reflection
            ? `Session goal: ${this.goal || 'help the player'}. Give 3-5 short concrete lessons: what worked, what went wrong, which tool you missed. No tools, no game actions; reflect only. Recent events: ${JSON.stringify(events)}.`
            : message.autoplay ? `Continue toward the goal: ${this.goal}; current state: ${state || JSON.stringify(this.compact.facts)}. Scene: ${scene || 'unknown; try look'}. Events: ${JSON.stringify(events)}. Pick the next action.`
              : `You are ${this.bot}. ${message.from}: ${message.text}${state}${scene ? `\nScene: ${scene}` : ''}${events.length ? `\nEvents: ${JSON.stringify(events)}` : ''}`;
          let rotation = '';
          if (this.sessionTurns >= 10) {
            this.backend.close(); this.sessionTurns = 0;
            rotation = `Session summary: goal: ${this.goal || message.text}; inventory: ${this.compact.facts.inventory}; last 3 results: ${JSON.stringify(this.recentResults)}; current scene: ${String(scene).split('\n')[0] || 'unknown'}.\n`;
          }
          const prompt = rotation + this.languageRule() + `${content}\n${this.plans?.status() ?? 'план: нет'}`;
          this.log?.write('model_message', { role: 'user', text: prompt.slice(0, 300) });
          this.trace('actor', 'input', { text: prompt });
          this.note('actor', `thinking about: ${message.reflection ? 'session lessons' : message.autoplay ? `next action toward ${this.goal}` : `${message.from}: ${message.text}`}`);
          if (this.auditFile) await writeFile(this.auditFile, '');
          if (generation !== this.generation) continue;
          called = true;
          if (!message.reflection) this.turns++;
          result = message.reflection ? await this.backend.send(prompt) : await this.thinking.run(() => this.actorTurn(() => this.backend.send(prompt), message.text, message.from), {
            backend: message.reflection ? undefined : this.backend, languageText: this.lastPlayerMessage?.text, player: message.from,
            isActive: () => generation === this.generation && !message.reflection,
          });
          this.sessionTurns++;
          if (generation !== this.generation || this.closed) continue;
          if (this.plans && !message.reflection && /^needs_plan\b/iu.test(String(result.text).trim())) {
            this.queue.unshift({ ...message, needsPlan: true }); continue;
          }
          this.log?.write('model_message', { role: 'final', text: String(result.text ?? '').slice(0, 300) });
          this.trace('actor', 'output', { text: String(result.text ?? ''), ms: result.ms ?? null });
          this.note('actor', `answered: ${String(result.text ?? '')}`);
          if (message.reflection) {
            const lessons = String(result.text ?? '').trim().slice(0, 1800);
            if (lessons && this.lessonsFile) await appendFile(this.lessonsFile, `## ${this.log?.id ?? new Date().toISOString()}\n${lessons}\n\n`);
            this.log?.write('reflection', { text: lessons });
          }
          const reply = shortReply(result.text);
          if (reply && (!message.reflection || message.from !== 'Session')
              && (!message.autoplay || this.turns - (this.lastAutoplaySpeech ?? -3) >= 3)) {
            await this.say(reply, message.autoplay ? undefined : message.from);
            if (message.autoplay) this.lastAutoplaySpeech = this.turns;
          }
        } catch (error) {
          failure = error.message; this.compact.reset();
          if (generation === this.generation) this.diagnostic(error);
        } finally {
          if (called) {
            const audit = this.auditFile ? await readFile(this.auditFile, 'utf8').catch(() => '') : '';
            const entry = { bot: this.bot, backend: this.backend.backend, model: this.backend.model,
              ms: result?.ms ?? Date.now()-started, ttft_ms: result?.ttftMs ?? null, usage: result?.usage ?? null,
              tool_calls: audit.trim() ? audit.trim().split('\n').length : result?.toolCalls ?? 0, error: failure ?? null,
              reflection: !!message.reflection, role: 'actor' };
            this.log?.write('turn', entry);
            if (this.usageFile) await appendFile(this.usageFile, `${JSON.stringify({ time: new Date(started).toISOString(), ...entry })}\n`);
          }
        }
        if (!message.reflection && this.goal && this.turns >= this.maxTurns) this.endAutoplay('max turns');
      }
    } finally { this.busy = false; this.schedule(); }
  }
  close() {
    this.closed = true; this.cancel(); clearTimeout(this.deadline); this.quickBackend?.close?.();
    this.body.off('event', this.onEvent); this.body.off('ready', this.onReady); this.body.off('disconnected', this.onDisconnect);
    this.hearing?.close();
    this.body.close(); this.log?.write('session_end', { turns: this.turns, ms: Date.now()-this.started });
  }
}

export async function main() {
  const bot = option('bot', process.env.BLOCKNET_BOT ?? 'Kirill');
  const aliases = option('alias', process.env.BLOCKNET_ALIASES ?? (bot === 'Kirill' ? 'Кирилл,Кир' : '')).split(',').map(s => s.trim()).filter(Boolean);
  if (!/^[A-Za-z0-9_]{3,16}$/u.test(bot)) throw new Error('Invalid Minecraft bot name');
  const config = configPath(), goal = option('autoplay', '');
  const body = new Body(config);
  body.on('diagnostic', text => process.stderr.write(`${text}\n`));
  const logDir = resolve(brainDir, 'logs');
  const log = new SessionLog(logDir, bot, goal);
  const mcpConfig = bot === 'Kirill' ? 'brain/mcp.json' : `brain/mcp.${bot}.json`;
  const auditFile = resolve(logDir, `tool-calls.${bot}.jsonl`), lessonsFile = resolve(logDir, 'lessons.md');
  const mcpServer = { command: process.execPath, args: [resolve(brainDir, 'mcp-server.js')],
    env: { BLOCKNET_CONFIG: config, BLOCKNET_BOT: bot, BLOCKNET_AUDIT: auditFile,
      BLOCKNET_SESSION: log.file, BLOCKNET_SESSION_ID: log.id } };
  await writeFile(resolve(rootDir, mcpConfig), `${JSON.stringify({ mcpServers: { blocknet: { type: 'stdio', ...mcpServer } } }, null, 2)}\n`);
  const prompt = personaPrompt + '\n' + await readFile(resolve(brainDir, 'prompt.md'), 'utf8') + lastLessons(lessonsFile, Number(option('lessons', '0')));
  const promptFile = resolve(logDir, `prompt.${bot}.md`);
  await writeFile(promptFile, prompt);
  const legacyBackend = option('backend', process.env.BLOCKNET_BACKEND);
  const actorSpec = parseRole(option('actor', legacyBackend === 'claude' ? 'claude:haiku' : 'codex:gpt-6.1-sol:medium'));
  const plannerSpec = parseRole(option('planner', 'codex:gpt-6.1-sol:high'));
  const backend = createBackend(actorSpec.backend, {
    cwd: rootDir, mcpConfig, mcpServer, prompt, promptFile, maxTurns: 10,
    ...actorSpec,
    onMessage: text => log.write('model_message', { role: 'assistant', text: String(text ?? '').slice(0, 300) }),
  });
  const plannerFile = resolve(logDir, `planner-prompt.${bot}.md`);
  await writeFile(plannerFile, plannerPrompt);
  const plannerMcp = { ...mcpServer, env: { ...mcpServer.env, BLOCKNET_ROLE: 'planner' } };
  const plannerConfig = resolve(logDir, `mcp.planner.${bot}.json`);
  await writeFile(plannerConfig, JSON.stringify({ mcpServers: { blocknet: { type: 'stdio', ...plannerMcp } } }));
  const plannerBackend = createBackend(plannerSpec.backend, { ...plannerSpec, cwd: rootDir,
    role: 'planner',
    prompt: plannerPrompt, promptFile: plannerFile, mcpConfig: plannerConfig, mcpServer: plannerMcp, maxTurns: 10 });
  // Optional voice: local speech recognition and synthesis (brain/setup-voice.ps1 installs them).
  const voiceSettings = JSON.parse(await readFile(config, 'utf8')).voice ?? {};
  const voice = voiceConfig(voiceSettings);
  const hearing = voice.stt && new Hearing({ ...voice.stt, log: (type, data) => log.write(type, data),
    prompt: `Разговор в Minecraft с ботом ${bot}. Майнкрафт, кирка, верстак, сундук, печка, руда, железо, алмазы, МЭ-сеть, терминал, шаблон, автокрафт.` });
  const speech = voice.tts && new Speech(voice.tts);
  log.write('voice', { hearing: voice.stt ?? 'off', speech: voice.tts ?? 'off', home: voice.home, searched: voiceHomes(voiceSettings) });
  // Optional: --planner-strong backend:model takes over replanning after repeated failures.
  const strongSpec = option('planner-strong', '') ? parseRole(option('planner-strong', '')) : null;
  const strongPlannerBackend = strongSpec && createBackend(strongSpec.backend, { ...strongSpec, cwd: rootDir,
    role: 'planner', prompt: plannerPrompt, promptFile: plannerFile, mcpConfig: plannerConfig, mcpServer: plannerMcp, maxTurns: 10 });
  // The quick mouth (--quick backend:model[:effort], or off): persona only, no tools, low effort.
  const quickOption = option('quick', `${actorSpec.backend}:${actorSpec.model}:low`);
  const quickSpec = quickOption === 'off' ? null : parseRole(quickOption);
  const quickFile = resolve(logDir, `quick-prompt.${bot}.md`);
  await writeFile(quickFile, personaPrompt);
  const quickBackend = quickSpec && createBackend(quickSpec.backend, { ...quickSpec, cwd: rootDir, role: 'chat', prompt: personaPrompt,
    promptFile: quickFile, mcpConfig: '{"mcpServers":{}}', maxTurns: 8, timeoutMs: 15_000 });
  Promise.resolve(quickBackend?.prepare?.()).catch(() => {});
  hearing?.warm?.().then(ms => log.write('voice_warm', { ms }), error => log.write('voice_error', { stage: 'warm', error: error.message }));
  const session = new BrainSession({ body, backend, plannerBackend, strongPlannerBackend, hearing, speech, quickBackend, bot, aliases, goal, log, auditFile, lessonsFile,
    usageFile: resolve(logDir, 'usage.jsonl'), listen: option('listen', 'all'),
    maxTurns: Number(option('max-turns', '200')), maxMinutes: Number(option('max-minutes', '60')) });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => session.close());
  await session.start();
  process.stderr.write(`Brain ready for ${bot}: planner ${plannerBackend.backend}/${plannerBackend.model}, actor ${backend.backend}/${backend.model}. Log: ${log.file}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.stack}\n`); process.exit(1); });
}
