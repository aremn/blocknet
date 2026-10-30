// The worker's brain (ADR-0016): the model only turns a player's words into jobs; the body does them.
import { appendFile, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Body, configPath, option } from './body.js';
import { ChatRouter, routeRule } from './rules.js';
import { SessionLog, failed, oneLine } from './session-log.js';
import { ClaudeBackend } from './claude-backend.js';
import { CodexBackend } from './codex-backend.js';
import { Hearing, Speech, pickVoice, voiceConfig, voiceHomes, voiceName } from './voice.js';

const brainDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(brainDir);
export const personaPrompt = await readFile(new URL('./persona.md', import.meta.url), 'utf8');
export const jobsPrompt = await readFile(new URL('./jobs.md', import.meta.url), 'utf8');
export const systemPrompt = `${personaPrompt}\n${jobsPrompt}`;

export function createBackend(name, options) {
  if (name === 'claude') return new ClaudeBackend(options);
  if (name === 'codex') return new CodexBackend(options);
  throw new Error('--model must start with claude: or codex:');
}

/** "backend:model[:effort|tier]", e.g. codex:gpt-6.1-sol:low or claude:haiku. */
export function parseModel(spec) {
  const match = /^(claude|codex):([^:\s]+)(?::([^:\s]+))?$/u.exec(spec ?? '');
  if (!match) throw new Error('Use --model backend:model[:effort], e.g. codex:gpt-6.1-sol:low');
  const [, backend, model, modifier] = match;
  if (modifier && !['default', 'priority', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'].includes(modifier))
    throw new Error(`Unknown tier/effort: ${modifier}`);
  return { backend, model, ...(modifier && (['default', 'priority'].includes(modifier) ? { serviceTier: modifier } : { effort: modifier })) };
}

const itemId = id => String(id).replace(/^minecraft:/u, '');
/** How long the brain waits for one body task; the body's own budgets end a stuck one sooner. */
export const JOB_WAIT_MS = 2 * 60 * 60_000;

/** Item counts from the body's inv text (main, armor and offhand slots). */
export function inventoryCounts(text) {
  const counts = {};
  for (const line of String(text).replace(/^inventory r\d+:\s*/u, '').split(/\n|;\s*|,\s*(?=\d+ [\w:./-]+)/u)) {
    if (/^(?:main:|selected |summary:|free slots)/u.test(line.trim())) continue;
    const m = /^(?:(?:off|head|chest|legs|feet):\s*)?(?:(\d+) )?([\w:./-]+)(?:\s|$)/u.exec(line.trim());
    if (!m || !m[1] && !/ slots \[|^(?:off|head|chest|legs|feet):/u.test(line.trim())) continue;
    const key = itemId(m[2]); counts[key] = (counts[key] ?? 0) + +(m[1] ?? 1);
  }
  return counts;
}

const KINDS = ['get', 'smelt', 'store', 'take', 'move', 'give', 'drop', 'come', 'follow', 'stop', 'clear'];
const count = value => Number.isInteger(value) && value >= 1 && value <= 100_000;
const id = value => typeof value === 'string' && /^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$/u.test(value);
const ref = value => typeof value === 'string' && /^p\d+$/u.test(value);

/** One job the body can do, or a reason it is not one; the model's text never reaches the body unchecked. */
export function checkJob(job) {
  if (!job || typeof job !== 'object' || !KINDS.includes(job.job)) return `unknown job ${JSON.stringify(job?.job)}`;
  const bad = {
    get: () => !id(job.item) || !count(job.count) || job.as != null && !['raw', 'smelted'].includes(job.as),
    smelt: () => job.item != null && !id(job.item),
    store: () => !ref(job.target) || !(id(job.what) || ['loot', 'all'].includes(job.what)),
    take: () => !ref(job.target) || !id(job.item) || job.count != null && !count(job.count),
    move: () => !ref(job.from) || !ref(job.to),
    give: () => !(id(job.item) || job.item === 'all') || job.count != null && !count(job.count),
    drop: () => !(id(job.item) || job.item === 'all') || job.count != null && !count(job.count),
    clear: () => !Number.isInteger(job.radius) || job.radius < 1 || job.radius > 16,
  }[job.job];
  return bad?.() ? `bad arguments for ${job.job}: ${JSON.stringify(job)}` : null;
}

/** The model's JSON (possibly wrapped in prose or a code fence) as {reply, jobs, now, invalid}. */
export function parseReply(text) {
  const raw = String(text ?? '');
  const start = raw.indexOf('{'), end = raw.lastIndexOf('}');
  let value;
  try { value = JSON.parse(raw.slice(start, end + 1)); } catch { return { reply: '', jobs: [], now: false, invalid: [`not JSON: ${oneLine(raw).slice(0, 200)}`] }; }
  const jobs = [], invalid = [];
  for (const job of Array.isArray(value.jobs) ? value.jobs : []) {
    const problem = checkJob(job);
    if (problem) invalid.push(problem); else jobs.push(job);
  }
  return { reply: shortLine(value.reply), jobs, now: value.now === true, invalid };
}

export function shortLine(text, words = 14) {
  text = oneLine(text);
  if (!text || text === '<silent>') return '';
  return text.split(' ').slice(0, words).join(' ').slice(0, 200);
}

/** What a job is, in a few words, for the model's Work line and the logs. */
export function describeJob(job) {
  const n = job.count ? `${job.count} ` : '';
  return {
    get: () => `${job.deliver ? 'bring' : 'get'} ${n}${job.item}${job.source === 'world' ? ' (mine it yourself)' : ''}${job.as ? ` as ${job.as}` : ''}`,
    smelt: () => `smelt ${job.item ?? 'the ore in the bag'}`,
    store: () => `put ${job.what} into ${job.target}`,
    take: () => `take ${n}${job.item} from ${job.target}`,
    move: () => `move everything from ${job.from} to ${job.to}`,
    give: () => `give ${n}${job.item}`, drop: () => `drop ${n}${job.item}`,
    come: () => 'come to the player', follow: () => 'follow the player', stop: () => 'stop',
    clear: () => `clear a square of radius ${job.radius}`,
  }[job.job]();
}

/** A finished job that did not do everything: worth one plain-words line instead of "done". */
const incomplete = report => failed(report) || /^(?:\[[^\]]+\] )?partly\b/iu.test(String(report));

/**
 * One bot's brain: chat in, jobs out. Messages are translated one at a time, in order; jobs run one
 * at a time from a queue. Speech is the model's opening line, then one line per finished job.
 */
export class Worker {
  constructor({ body, backend, bot, aliases = [], listen = 'all', log, hearing, speech, usageFile, modelMs = 25_000 }) {
    Object.assign(this, { body, backend, bot, log, hearing, speech, usageFile, modelMs });
    this.router = new ChatRouter(bot, aliases, listen);
    this.queue = []; this.current = null; this.generation = 0; this.conversation = []; this.pointed = [];
    this.turns = Promise.resolve();
    this.onEvent = event => this.event(event);
    body.on('event', this.onEvent);
  }
  async start() {
    try { await this.body.ready(); } catch (error) { this.diagnostic(error); } // Body keeps reconnecting.
    Promise.resolve(this.backend.prepare?.()).catch(() => {});
    return this;
  }
  diagnostic(error) { process.stderr.write(`${error?.message ?? error}\n`); }
  note(text) { try { this.body.note?.(this.bot, 'brain', oneLine(text).slice(0, 600)); } catch { /* debug only */ } }
  remember(who, text) {
    this.conversation.push({ who, text: String(text).slice(0, 300) });
    this.conversation = this.conversation.slice(-6);
  }
  event(event) {
    if (event.type === 'voice') {
      if (!event.bots?.some(name => name.toLowerCase() === this.bot.toLowerCase())) return;
      if (this.hearing) this.heard(event);
      else this.log?.write('voice_error', { stage: 'hear', error: 'speech recognition is not installed (brain/setup-voice.ps1)', from: event.from });
      return;
    }
    if (event.bot && event.bot.toLowerCase() !== this.bot.toLowerCase() && event.type !== 'bot.spoke') return;
    this.log?.write(event.type === 'chat' ? 'chat' : 'event', event.type === 'chat' ? { from: event.from, text: event.text } : { event });
    if (event.type === 'bot.spoke') this.router.spoke(event.bot, event.player);
    else if (event.type === 'voice.choose') this.chooseVoice(event).catch(error => this.log?.write('voice_error', { stage: 'choose', error: error.message }));
    else if (event.type === 'bot.died') this.cancel('died');
    else if (event.type === 'chat') this.hear(event);
  }
  hear(event) {
    const text = this.router.route(event.text, event.from, this.body.hello?.bots ?? [this.bot]);
    if (!text) return;
    if (event.pointed_ref) this.pointed = [...this.pointed.filter(p => p.ref !== event.pointed_ref),
      { ref: event.pointed_ref, block: event.pointed_block }].slice(-4);
    this.remember(`player ${event.from}`, text);
    const rule = routeRule(text, event.from);
    if (rule?.tool === 'stop') {
      this.cancel('stop');
      this.call('stop').catch(error => this.diagnostic(error));
      if (rule.nextOrder) this.serial(() => this.translate(rule.nextOrder, event));
      return;
    }
    // Short, unambiguous orders need no model: "сюда", "за мной", "накопай 20 угля".
    const quick = rule?.tool === 'come' || rule?.tool === 'follow' ? { jobs: [{ job: rule.tool }], now: true }
      : rule?.tool === 'gather' ? { jobs: [{ job: 'get', item: rule.args.item, count: rule.args.count, source: 'world' }], now: false } : null;
    if (quick) this.add(quick.jobs, event.from, quick.now).catch(error => this.diagnostic(error));
    else this.serial(() => this.translate(text, event));
  }
  /** Model turns run one after another, in the order the player spoke. */
  serial(work) {
    const turn = this.turns.then(work).catch(error => this.diagnostic(error));
    this.turns = turn;
    return turn;
  }
  async context(event, text) {
    const [status, inv] = await Promise.all([this.call('status'), this.call('inv')]).catch(() => [{}, {}]);
    const bag = Object.entries(inventoryCounts(inv.result ?? '')).map(([item, n]) => `${n} ${item}`).join(', ');
    const pointed = event?.pointed_ref ? `${event.pointed_ref} = block ${event.pointed_block}` : 'nothing';
    return `Bot: ${this.bot}. ${event ? `Player ${JSON.stringify(event.from)} says: ${JSON.stringify(text)}` : 'No new message.'}\n`
      + `Pointed now: ${pointed}; recent pointed refs: ${this.pointed.map(p => `${p.ref} = ${p.block}`).join('; ') || 'none'}\n`
      + `State: ${oneLine(status.result ?? 'unknown').slice(0, 400)}\nBag: ${bag.slice(0, 600) || 'empty'}\n`
      + `Work: ${this.current ? describeJob(this.current) : 'idle'}${this.queue.length ? `; queued: ${this.queue.map(describeJob).join('; ')}` : ''}\n`
      + `Recent conversation: ${JSON.stringify(this.conversation)}`;
  }
  async ask(prompt) {
    const started = Date.now();
    let timer, result, error;
    try {
      result = await Promise.race([this.backend.send(prompt), new Promise((_, reject) => {
        timer = setTimeout(() => { reject(new Error('model too slow')); this.backend.close?.(); }, this.modelMs);
      })]);
      return result;
    } catch (e) { error = e; throw e; } finally {
      clearTimeout(timer);
      const entry = { bot: this.bot, role: 'worker', backend: this.backend.backend, model: this.backend.model,
        ms: result?.ms ?? Date.now() - started, ttft_ms: result?.ttftMs ?? null, usage: result?.usage ?? null, error: error?.message ?? null };
      this.log?.write('turn', entry);
      if (this.usageFile) appendFile(this.usageFile, `${JSON.stringify({ time: new Date(started).toISOString(), ...entry })}\n`).catch(() => {});
    }
  }
  async translate(text, event) {
    const generation = this.generation;
    const prompt = await this.context(event, text);
    if (this.closed) return;
    let parsed;
    try { parsed = parseReply((await this.ask(prompt)).text); }
    catch (error) {
      this.log?.write('translate_error', { error: error.message });
      return this.say('Чё-то я подвис, скажи ещё раз.', event.from);
    }
    this.log?.write('translate', { text, ...parsed });
    this.note(`${event.from}: ${text} -> ${parsed.jobs.map(describeJob).join('; ') || 'no job'}${parsed.invalid.length ? ` (rejected: ${parsed.invalid.join('; ')})` : ''}`);
    if (parsed.reply) await this.say(parsed.reply, event.from);
    if (parsed.jobs.some(job => job.job === 'stop')) { this.cancel('stop'); await this.call('stop'); return; }
    // An order given while it was translated still counts; only a stop in between drops it.
    if (generation !== this.generation && !parsed.now) return;
    if (parsed.jobs.length) await this.add(parsed.jobs, event.from, parsed.now);
  }
  async add(jobs, player, now) {
    // The body stops before the new jobs queue up, so the stop cannot hit the job meant to replace.
    if (now && (this.current || this.queue.length)) { this.cancel('replaced'); await this.call('stop').catch(error => this.diagnostic(error)); }
    // Jobs of one order share a batch: "dig coal, then put it in the chest" stops at the first failure.
    const batch = this.batches = (this.batches ?? 0) + 1;
    this.queue.push(...jobs.map(job => ({ ...job, player, batch })));
    this.pump().catch(error => this.diagnostic(error));
  }
  /** Drops the running job and the queue; the body is stopped by the caller. */
  cancel(reason) {
    if (!this.current && !this.queue.length) return;
    this.generation++;
    this.log?.write('jobs_cancelled', { reason, current: this.current && describeJob(this.current), queued: this.queue.length });
    this.queue.length = 0;
  }
  async pump() {
    if (this.running) return;
    this.running = true;
    try {
      while (this.queue.length && !this.closed) {
        const job = this.current = this.queue.shift(), generation = this.generation, started = Date.now();
        this.log?.write('job_start', { job, what: describeJob(job) });
        this.note(`job: ${describeJob(job)}`);
        const report = await this.run(job).catch(error => `failed: ${error.message}`);
        this.log?.write('job_end', { job, report: oneLine(report).slice(0, 600), ms: Date.now() - started, cancelled: generation !== this.generation });
        if (generation !== this.generation || this.closed) continue;
        if (job.job === 'follow') continue;
        if (incomplete(report)) {
          const dropped = this.queue.filter(next => next.batch === job.batch);
          this.queue = this.queue.filter(next => next.batch !== job.batch);
          if (dropped.length) this.log?.write('jobs_dropped', { after: describeJob(job), dropped: dropped.map(describeJob) });
          await this.serial(() => this.explain(job, report, dropped));
        }
        // Arriving speaks for itself; any other finished job gets its line.
        else await this.say(shortLine(job.done, 8) || (job.job === 'come' ? '' : 'Готово.'), job.player);
      }
    } finally { this.current = null; this.running = false; }
  }
  /** One job through the body's own tasks; resolves with the body's final report. */
  async run(job) {
    const player = job.player;
    const optional = (key, value) => value == null ? {} : { [key]: value };
    switch (job.job) {
      case 'get': {
        // "as": the player chose raw or smelted ore. The body names the raw form (raw_iron for
        // iron_ingot, a mod's raw osmium for its ingot), so no metal is spelled out here.
        const raw = job.as ? (await this.call('raw', { item: job.item })).result : 'none';
        if (raw === 'none') return this.obtain(job, player);
        if (job.as === 'raw') return this.obtain({ ...job, item: raw, source: 'world' }, player);
        return this.smeltNear(job, raw, player);
      }
      case 'smelt': return this.task('smelt', optional('item', job.item));
      case 'store': return this.task('deposit', { ref: job.target, filter: job.what });
      case 'take': return this.task('withdraw', { ref: job.target, filter: job.item, ...optional('count', job.count) });
      case 'move': return this.task('transfer', { source: job.from, destination: job.to });
      case 'give': return this.task('give', { player, item: job.item, ...optional('count', job.count) });
      case 'drop': return this.task('drop', { item: job.item, ...optional('count', job.count) });
      case 'come': return this.task('come', { player });
      // Following lasts until the next job or a stop; the queue does not wait for it.
      case 'follow': await this.call('follow', { player }); return 'following';
      case 'clear': return this.task('clear', { radius: job.radius, player });
      default: return `failed: unknown job ${job.job}`;
    }
  }
  /** Bringing N means handing over N (owned ones count); getting N means N more than now. */
  async obtain(job, player) {
    const owned = inventoryCounts((await this.call('inv')).result)[itemId(job.item)] ?? 0;
    const total = job.deliver ? job.count : owned + job.count;
    const report = owned >= total ? `already have ${owned} ${job.item}`
      : await this.task('obtain', { item: job.item, count: total, ...(job.source === 'world' && { source: 'world' }) });
    if (failed(report) || !job.deliver) return report;
    return this.task('give', { player, item: job.item, count: job.count });
  }
  /** Smelted where the player sees it: dig the raw ore, come back, smelt it beside them, hand it over. */
  async smeltNear(job, raw, player) {
    const counts = inventoryCounts((await this.call('inv')).result);
    const need = job.deliver ? Math.max(0, job.count - (counts[itemId(job.item)] ?? 0)) : job.count;
    if (need > (counts[itemId(raw)] ?? 0)) {
      const dug = await this.task('obtain', { item: raw, count: need, source: 'world' });
      if (failed(dug)) return dug;
    }
    await this.task('come', { player }).catch(() => {});
    const smelted = need > 0 ? await this.task('smelt', { item: raw }) : 'nothing to smelt';
    if (failed(smelted) || !job.deliver) return smelted;
    return this.task('give', { player, item: job.item, count: job.count });
  }
  async task(tool, args) {
    const response = await this.call(tool, args);
    return response.outcome ? await response.outcome : response.result;
  }
  async call(tool, args = {}) {
    const started = Date.now();
    this.log?.write('tool_call', { tool, args });
    try {
      const result = await this.body.call(this.bot, tool, args);
      if (result.task_id) {
        // The body ends its own jobs (budgets, stop); a big order (64 iron, smelted) runs past ten minutes.
        const outcome = this.body.waitTask(result.task_id, JOB_WAIT_MS);
        outcome.then(text => this.log?.write('tool_result', { tool, result: oneLine(text).slice(0, 1000), ms: Date.now() - started }),
          error => this.log?.write('tool_result', { tool, result: error.message, failed: true, ms: Date.now() - started }));
        return { ...result, outcome };
      }
      if (!['status', 'inv', 'say', 'speak', 'heard'].includes(tool)) this.log?.write('tool_result', { tool, result: oneLine(result.result).slice(0, 1000), ms: Date.now() - started });
      return result;
    } catch (error) { this.log?.write('tool_result', { tool, result: error.message, failed: true, ms: Date.now() - started }); throw error; }
  }
  /** A job that failed or did only part of its work is told in plain words, once. */
  async explain(job, report, dropped = []) {
    let line = '';
    try {
      const prompt = `${await this.context(null, '')}\nNo new message: the job "${describeJob(job)}" just ended. The body reported: ${oneLine(report).slice(0, 600)}.\n`
        + (dropped.length ? `The rest of that order was not started: ${dropped.map(describeJob).join('; ')}.\n` : '')
        + 'Return {"reply": "...", "jobs": []} where reply tells the player in ONE short line, in character, what went wrong or what is left, '
        + 'in plain words (no item ids, tool names or English error text), and what would help, if anything.';
      line = parseReply((await this.ask(prompt)).text).reply;
    } catch (error) { this.log?.write('explain_error', { error: error.message }); }
    await this.say(line || 'Не вышло, глянь, что не так.', job.player);
  }
  async say(text, player) {
    if (!text || this.closed) return;
    await this.call('say', { text, ...(player && { player }) }).catch(error => this.diagnostic(error));
    if (this.speech) this.speak(text);
    this.remember(`bot ${this.bot}`, text);
    this.router.spoke(this.bot, player);
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
  close() {
    this.closed = true; this.cancel('closed');
    this.body.off('event', this.onEvent);
    this.hearing?.close(); this.backend.close?.();
    this.body.close(); this.log?.write('session_end', {});
  }
}

export async function main() {
  const bot = option('bot', process.env.BLOCKNET_BOT ?? 'Kirill');
  const aliases = option('alias', process.env.BLOCKNET_ALIASES ?? (bot === 'Kirill' ? 'Кирилл,Кир' : '')).split(',').map(s => s.trim()).filter(Boolean);
  if (!/^[A-Za-z0-9_]{3,16}$/u.test(bot)) throw new Error('Invalid Minecraft bot name');
  const config = configPath();
  const body = new Body(config);
  body.on('diagnostic', text => process.stderr.write(`${text}\n`));
  const logDir = resolve(brainDir, 'logs');
  const log = new SessionLog(logDir, bot);
  const promptFile = resolve(logDir, `prompt.${bot}.md`);
  await writeFile(promptFile, systemPrompt);
  const spec = parseModel(option('model', 'codex:gpt-6.1-sol:low'));
  const backend = createBackend(spec.backend, { ...spec, cwd: rootDir, role: 'chat', prompt: systemPrompt, promptFile,
    mcpConfig: '{"mcpServers":{}}', maxTurns: 8, timeoutMs: 30_000 });
  const voice = voiceConfig(JSON.parse(await readFile(config, 'utf8')).voice ?? {});
  const hearing = voice.stt && new Hearing({ ...voice.stt, log: (type, data) => log.write(type, data),
    prompt: `Разговор в Minecraft с ботом ${bot}. Майнкрафт, кирка, верстак, сундук, печка, руда, железо, алмазы, уголь, площадка.` });
  const speech = voice.tts && new Speech(voice.tts);
  log.write('voice', { hearing: voice.stt ?? 'off', speech: voice.tts ?? 'off', home: voice.home, searched: voiceHomes(JSON.parse(await readFile(config, 'utf8')).voice ?? {}) });
  hearing?.warm?.().then(ms => log.write('voice_warm', { ms }), error => log.write('voice_error', { stage: 'warm', error: error.message }));
  const worker = new Worker({ body, backend, bot, aliases, log, hearing, speech, listen: option('listen', 'all'),
    usageFile: resolve(logDir, 'usage.jsonl') });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { worker.close(); process.exit(0); });
  await worker.start();
  process.stderr.write(`Worker brain ready for ${bot}: ${backend.backend}/${backend.model}. Log: ${log.file}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.stack}\n`); process.exit(1); });
}
