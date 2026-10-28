import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { option } from './body.js';
import { oneLine, readSession } from './session-log.js';

/**
 * One chronological story of a session: the brain's session log (model inputs/outputs, reasoning,
 * tools, plan, critic, events) merged with the body's trace file (logs/blocknet/<bot>-trace.log:
 * routes, aiming, mining, danger, why each choice was made).
 */

const BODY_LINE = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}\.\d{3}) t=(\d+) \[([^\]]+)\] (.*)$/u;

/** Body trace lines use the game's local clock, like this process. */
export function readBodyTrace(file) {
  if (!file || !existsSync(file)) return [];
  return readFileSync(file, 'utf8').split(/\r?\n/u).flatMap(line => {
    const match = BODY_LINE.exec(line);
    if (!match) return [];
    const time = new Date(`${match[1]}T${match[2]}`);
    return Number.isNaN(time.getTime()) ? [] : [{ ms: time.getTime(), origin: 'body', source: match[4], text: match[5], tick: +match[3] }];
  });
}

function brainLine(e) {
  const clip = (text, n) => oneLine(text).slice(0, n);
  switch (e.type) {
    case 'model_trace':
      if (e.kind === 'input') return [`${e.model_role} <- ${clip(e.text, 4000)}`];
      if (e.kind === 'output') return [`${e.model_role} -> ${clip(e.text, 4000)}${e.ms ? ` (${(e.ms / 1000).toFixed(1)} s)` : ''}`];
      if (e.kind === 'reasoning') return [`${e.model_role} thinks: ${clip(e.text, 4000)}`];
      if (e.kind === 'tool') return [`${e.model_role} calls ${e.tool} ${JSON.stringify(e.arguments ?? {})}${e.result ? ` -> ${clip(JSON.stringify(e.result), 600)}` : ''}`];
      if (e.kind === 'tool_result') return [`${e.model_role} tool result: ${clip(JSON.stringify(e.result), 600)}`];
      if (e.kind === 'search') return [`${e.model_role} searches: ${e.query}`];
      return [];
    case 'tool_call': return [`brain calls ${e.tool} ${JSON.stringify(e.args ?? {})}`];
    case 'tool_result': return [`brain ${e.tool} ${e.failed ? 'FAILED' : '->'} ${clip(e.result, 600)}${e.ms != null ? ` (${e.ms} ms)` : ''}`];
    case 'plan': return [`PLAN ${clip(JSON.stringify(e.plan?.steps?.map(s => s.do) ?? []), 1200)}${e.failures?.length ? ` after failures ${clip(JSON.stringify(e.failures), 800)}` : ''}`];
    case 'plan_step_start': return [`STEP ${e.status}`];
    case 'plan_step': return [`STEP done ${e.status}`];
    case 'plan_fallback': return [`STEP ${e.step?.id} failed: ${clip(e.reason, 400)}; fallback ${e.fallback}`];
    case 'plan_failure': return [`PLAN failed (${e.failures}/3): ${clip(e.reason, 600)}`];
    case 'plan_revised': return [`PLAN revised after hint ${JSON.stringify(e.hint)}`];
    case 'plan_reused': return [`PLAN reused for ${e.goal}`];
    case 'chat': return [`chat <${e.from}> ${e.text}`];
    case 'event': {
      const event = e.event ?? {};
      if (event.type === 'moment') return [`moment ${event.kind}: ${event.note ?? ''}`];
      if (event.type === 'task.finished') return [`task finished: ${clip(event.result, 600)}`];
      return event.type ? [`event ${event.type}`] : [];
    }
    case 'turn': return e.error ? [`model ${e.role} error: ${e.error}`] : [];
    case 'session': return [`SESSION ${e.session} bot ${e.bot} goal ${e.goal || '(chat)'}`];
    default: return [];
  }
}

export function timeline(sessionEntries, bodyEntries = []) {
  const rows = [
    ...sessionEntries.flatMap(e => brainLine(e).map(text => ({ ms: Date.parse(e.time), origin: 'brain', text }))),
    ...bodyEntries.map(e => ({ ...e, text: `[${e.source}] ${e.text}` })),
  ].filter(row => Number.isFinite(row.ms));
  // Stable: equal times keep brain-before-body file order.
  rows.sort((a, b) => a.ms - b.ms);
  if (sessionEntries.length) {
    // Keep body lines from shortly before the session to its end only.
    const start = Date.parse(sessionEntries[0].time) - 60_000, end = Date.parse(sessionEntries.at(-1).time) + 60_000;
    return rows.filter(row => row.origin === 'brain' || row.ms >= start && row.ms <= end);
  }
  return rows;
}

export function render(rows) {
  return rows.map(row => {
    const date = new Date(row.ms);
    const clock = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}.${String(date.getMilliseconds()).padStart(3, '0')}`;
    return `${clock} ${row.origin === 'body' ? 'BODY ' : 'BRAIN'} ${row.text}`;
  }).join('\n');
}

/** The game directory is two levels above the bridge config (<game>/config/blocknet_ai-bridge.json). */
export function bodyTraceFile(config, bot) {
  return resolve(dirname(config), '..', 'logs', 'blocknet', `${bot}-trace.log`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const dir = join(dirname(fileURLToPath(import.meta.url)), 'logs');
  const positional = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : null;
  const latest = existsSync(dir) ? readdirSync(dir).filter(f => /^session-.*\.jsonl$/u.test(f)).sort().at(-1) : null;
  const sessionFile = positional ? resolve(positional) : latest ? join(dir, latest) : '';
  const entries = sessionFile && existsSync(sessionFile) ? readSession(sessionFile) : [];
  const bot = option('bot', entries.find(e => e.type === 'session')?.bot ?? process.env.BLOCKNET_BOT ?? 'Kirill');
  const config = resolve(option('config', process.env.BLOCKNET_CONFIG ?? 'mod/run/config/blocknet_ai-bridge.json'));
  const bodyFile = option('body', bodyTraceFile(config, bot));
  const text = render(timeline(entries, readBodyTrace(bodyFile)));
  const output = join(sessionFile ? dirname(sessionFile) : dir, `timeline-${sessionFile ? basename(sessionFile).replace(/^session-/u, '').replace(/\.jsonl$/u, '') : bot}.txt`);
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${text}\n`);
  process.stdout.write(`Session: ${entries.length ? sessionFile : 'none'}\nBody trace: ${existsSync(bodyFile) ? bodyFile : `none at ${bodyFile}`}\nWritten: ${output}\n`);
}
