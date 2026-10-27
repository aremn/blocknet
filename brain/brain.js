import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Body, configPath, option } from './body.js';
import { ChatRouter, routeRule } from './rules.js';
import { SessionLog, ResultSummary, perception, failed, lastLessons } from './session-log.js';
import { ClaudeBackend } from './claude-backend.js';
import { CodexBackend } from './codex-backend.js';
export { buildClaudeArgs } from './claude-backend.js';

const brainDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(brainDir);

export function createBackend(name, options) {
  if (name === 'claude') return new ClaudeBackend(options);
  if (name === 'codex') return new CodexBackend(options);
  throw new Error('--backend must be claude or codex');
}

/** Acknowledge only turns which are still waiting after the short grace period. */
export async function sendWithAck(backend, text, say, { delayMs = 700, ack = 'On it.' } = {}) {
  let acknowledgement;
  const timer = setTimeout(() => {
    acknowledgement = Promise.resolve().then(() => say(ack)).catch(error => process.stderr.write(`${error.message}\n`));
  }, delayMs);
  try { return await backend.send(text); }
  finally { clearTimeout(timer); await acknowledgement; }
}

/** Keep inventory/held/low-health facts from the revision-based changes tool. */
export class CompactState {
  facts = { inventory: 'unknown', held: 'empty', health: '' };
  previous = '';
  update(delta) {
    const text = String(delta ?? '');
    const body = text.match(/body r\d+: ([^\n]*)/u)?.[1];
    if (body) {
      const health = body.match(/health ([\d.]+)\/([\d.]+)/u);
      this.facts.health = health && +health[1] / +health[2] < 0.4 ? `health low (${health[1]}/${health[2]})` : '';
    }
    const inventory = text.match(/inventory r\d+: ([\s\S]*?)(?=\n(?:body|task) r\d+:|$)/u)?.[1];
    if (inventory) {
      this.facts.held = inventory.match(/^main: (?:\d+ )?([\w:./-]+)/mu)?.[1] ?? 'empty';
      this.facts.inventory = inventory.split('\n').filter(line => line.includes(' slots [')).map(line => {
        const match = line.match(/^(?:(\d+) )?([\w:./-]+)/u);
        return match ? `${match[1] ?? 1} ${match[2]}` : '';
      }).filter(Boolean).join(', ') || 'empty';
    }
    const current = `inventory: ${this.facts.inventory}; held: ${this.facts.held}${this.facts.health ? `; ${this.facts.health}` : ''}`;
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
    maxMinutes = 60, pauseMs = 1200, log, auditFile, lessonsFile, usageFile }) {
    if (!Number.isInteger(maxTurns) || maxTurns < 1 || !Number.isFinite(maxMinutes) || maxMinutes <= 0)
      throw new Error('Limits must be positive (--max-turns integer, --max-minutes number)');
    Object.assign(this, { body, backend, bot, goal, maxTurns, maxMinutes, pauseMs, log, auditFile, lessonsFile, usageFile });
    this.router = new ChatRouter(bot, aliases, listen);
    this.compact = new CompactState(); this.summary = new ResultSummary();
    this.queue = []; this.events = []; this.busy = false; this.generation = 0; this.turns = 0;
    this.paused = false; this.ended = false; this.closed = false;
    this.onEvent = event => this.event(event);
    this.onReady = () => this.schedule();
    this.onDisconnect = () => { clearTimeout(this.nextTimer); this.log?.write('connection', { state: 'disconnected' }); };
    body.on('event', this.onEvent); body.on('ready', this.onReady); body.on('disconnected', this.onDisconnect);
  }
  async start() {
    this.started = Date.now();
    if (this.goal) this.deadline = setTimeout(() => this.endAutoplay('max minutes'), this.maxMinutes * 60_000);
    try { await this.body.ready(); this.schedule(); }
    catch (error) { this.diagnostic(error); } // Body continues reconnecting even after its initial ready timeout.
    return this;
  }
  diagnostic(error) { process.stderr.write(`${error.message ?? error}\n`); }
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
    this.router.spoke(this.bot, player);
  }
  schedule() {
    clearTimeout(this.nextTimer);
    if (this.closed || this.busy || !this.goal || this.paused || this.ended || !this.body.hello) return;
    if (!this.body.hello.bots?.some(name => name.toLowerCase() === this.bot.toLowerCase())) return;
    this.nextTimer = setTimeout(() => {
      if (this.queue.length === 0) this.queue.push({ autoplay: true, from: 'Goal', text: this.goal });
      this.processQueue().catch(e => this.diagnostic(e));
    }, this.pauseMs);
  }
  cancel(clear = true) {
    this.generation++; this.backend.close(); this.compact.reset();
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
  event(event) {
    if (event.bot && event.bot.toLowerCase() !== this.bot.toLowerCase() && event.type !== 'bot.spoke') return;
    this.log?.write(event.type === 'chat' ? 'chat' : 'event', event.type === 'chat' ? { from: event.from, text: event.text } : { event });
    if (event.type === 'bot.spoke') { this.router.spoke(event.bot, event.player); return; }
    if (event.type === 'bots.changed') { this.schedule(); return; }
    if (event.type === 'chat') {
      this.events.push(perception(event)); if (this.events.length > 16) this.events.shift();
    }
    if (event.type !== 'chat') {
      if (event.type === 'moment' || event.type === 'task.finished' || event.type === 'bot.died') {
        this.events.push(perception(event)); if (this.events.length > 16) this.events.shift();
      }
      if (event.type === 'bot.died') { this.paused = true; this.cancel(); }
      return;
    }
    const order = this.router.route(event.text, event.from, this.body.hello?.bots ?? [this.bot]);
    if (!order) return;
    clearTimeout(this.nextTimer);
    const rule = routeRule(order, event.from);
    if (rule?.tool === 'stop') {
      this.paused = true; this.cancel();
      this.runRule(rule, event.from).catch(e => this.diagnostic(e));
      this.log?.write('autoplay_pause');
      return;
    }
    if (/^(?:continue|продолжай)[.!?]*$/iu.test(order.trim())) {
      this.paused = false; this.log?.write('autoplay_resume'); this.schedule(); return;
    }
    this.queue = this.queue.filter(m => !m.autoplay);
    this.queue.push(/^(?:итоги|lessons|summary)[.!?]*$/iu.test(order.trim())
      ? { reflection: true, from: event.from, text: order }
      : rule ? { rule, from: event.from } : { from: event.from, text: order });
    this.processQueue().catch(e => this.diagnostic(e));
  }
  async runRule(rule, player) {
    const result = await this.call(rule.tool, rule.args);
    await this.say(result.result && /failed|blocked/iu.test(result.result) ? result.result : rule.ack, player);
    if (result.outcome) result.outcome.then(text => {
      if (/failed|blocked/iu.test(text)) return this.say(text, player);
    }).catch(e => this.diagnostic(e));
  }
  async processQueue() {
    if (this.busy || this.closed) return;
    this.busy = true;
    try {
      while (this.queue.length && !this.closed) {
        const message = this.queue.shift();
        if (message.rule) { await this.runRule(message.rule, message.from).catch(e => this.diagnostic(e)); continue; }
        if (message.autoplay && (this.paused || this.ended)) continue;
        if (!message.reflection && this.goal && !this.ended && this.turns >= this.maxTurns) {
          this.endAutoplay('max turns');
          if (message.autoplay) continue;
        }
        const generation = this.generation, started = Date.now();
        let result, failure, called = false;
        try {
          if (!this.backend.process || this.backend.process.error || this.backend.turns >= this.backend.maxTurns) this.compact.reset();
          let state = '';
          if (!message.reflection) {
            const delta = await this.call('changes');
            state = this.compact.update(delta.result);
          }
          if (generation !== this.generation || this.closed) continue;
          const events = this.events.splice(0);
          const prompt = message.reflection
            ? `Session goal: ${this.goal || 'help the player'}. Give 3-5 short concrete lessons: what worked, what went wrong, which tool you missed. No tools, no game actions; reflect only. Recent events: ${JSON.stringify(events)}.`
            : message.autoplay ? `Continue toward the goal: ${this.goal}; current state: ${state || JSON.stringify(this.compact.facts)}. Events: ${JSON.stringify(events)}. Pick the next action.`
              : `You are ${this.bot}. ${message.from}: ${message.text}${state}${events.length ? `\nEvents: ${JSON.stringify(events)}` : ''}`;
          this.log?.write('model_message', { role: 'user', text: prompt.slice(0, 300) });
          if (this.auditFile) await writeFile(this.auditFile, '');
          if (generation !== this.generation) continue;
          called = true;
          if (!message.reflection) this.turns++;
          result = await this.backend.send(prompt);
          if (generation !== this.generation || this.closed) continue;
          this.log?.write('model_message', { role: 'final', text: String(result.text ?? '').slice(0, 300) });
          if (message.reflection) {
            const lessons = String(result.text ?? '').trim().slice(0, 1800);
            if (lessons && this.lessonsFile) await appendFile(this.lessonsFile, `## ${this.log?.id ?? new Date().toISOString()}\n${lessons}\n\n`);
            this.log?.write('reflection', { text: lessons });
          }
          const reply = shortReply(result.text);
          if (reply && (!message.reflection || message.from !== 'Session')) await this.say(reply, message.autoplay ? undefined : message.from);
        } catch (error) {
          failure = error.message; this.compact.reset();
          if (generation === this.generation) this.diagnostic(error);
        } finally {
          if (called) {
            const audit = this.auditFile ? await readFile(this.auditFile, 'utf8').catch(() => '') : '';
            const entry = { bot: this.bot, backend: this.backend.backend, model: this.backend.model,
              ms: result?.ms ?? Date.now()-started, ttft_ms: result?.ttftMs ?? null, usage: result?.usage ?? null,
              tool_calls: audit.trim() ? audit.trim().split('\n').length : result?.toolCalls ?? 0, error: failure ?? null,
              reflection: !!message.reflection };
            this.log?.write('turn', entry);
            if (this.usageFile) await appendFile(this.usageFile, `${JSON.stringify({ time: new Date(started).toISOString(), ...entry })}\n`);
          }
        }
        if (!message.reflection && this.goal && this.turns >= this.maxTurns) this.endAutoplay('max turns');
      }
    } finally { this.busy = false; this.schedule(); }
  }
  close() {
    this.closed = true; this.cancel(); clearTimeout(this.deadline);
    this.body.off('event', this.onEvent); this.body.off('ready', this.onReady); this.body.off('disconnected', this.onDisconnect);
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
  const prompt = await readFile(resolve(brainDir, 'prompt.md'), 'utf8') + lastLessons(lessonsFile, Number(option('lessons', '0')));
  const promptFile = resolve(logDir, `prompt.${bot}.md`);
  await writeFile(promptFile, prompt);
  const backend = createBackend(option('backend', process.env.BLOCKNET_BACKEND ?? 'codex'), {
    cwd: rootDir, mcpConfig, mcpServer, prompt, promptFile, maxTurns: 30,
    onMessage: text => log.write('model_message', { role: 'assistant', text: String(text ?? '').slice(0, 300) }),
  });
  const session = new BrainSession({ body, backend, bot, aliases, goal, log, auditFile, lessonsFile,
    usageFile: resolve(logDir, 'usage.jsonl'), listen: option('listen', 'all'),
    maxTurns: Number(option('max-turns', '200')), maxMinutes: Number(option('max-minutes', '60')) });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => session.close());
  await session.start();
  process.stderr.write(`Brain ready for ${bot}: ${backend.backend}/${backend.model}. Log: ${log.file}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.stack}\n`); process.exit(1); });
}
