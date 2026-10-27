import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, unlinkSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';

export const oneLine = value => String(value ?? '').replace(/\s+/gu, ' ').trim();
export const failed = value => /^(?:\[[^\]]+\] )?(?:failed|blocked|cancelled|not hungry)\b/iu.test(String(value));

/** Remove human-only diagnostic facts before passing an event to any model. */
export function perception(value) {
  if (Array.isArray(value)) return value.map(perception);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'unseen').map(([key, v]) => [key, perception(v)]));
}

// Each process appends complete JSON lines. Segments share a stable session id/path via MCP env.
export function appendSession(file, entry, maxBytes = 5 * 1024 * 1024) {
  if (!file) return;
  mkdirSync(dirname(file), { recursive: true });
  let segment = file, part = 0;
  while (existsSync(`${file}.${part + 1}`)) segment = `${file}.${++part}`;
  const line = `${JSON.stringify({ time: new Date().toISOString(), ...entry })}\n`;
  if (existsSync(segment) && statSync(segment).size + Buffer.byteLength(line) > maxBytes) segment = `${file}.${part + 1}`;
  appendFileSync(segment, line);
}

export class SessionLog {
  constructor(dir, bot, goal = '', { now = Date.now(), maxBytes } = {}) {
    mkdirSync(dir, { recursive: true });
    this.id = `${new Date(now).toISOString().replace(/[:.]/gu, '-')}-${bot}`;
    this.file = join(dir, `session-${this.id}.jsonl`);
    this.maxBytes = maxBytes;
    const sessions = readdirSync(dir).filter(f => /^session-.*\.jsonl$/u.test(f)).sort();
    for (const old of sessions.slice(0, Math.max(0, sessions.length - 19))) {
      for (const part of readdirSync(dir).filter(f => f === old || f.startsWith(`${old}.`))) unlinkSync(join(dir, part));
    }
    this.write('session', { session: this.id, bot, goal });
  }
  write(type, data = {}) { appendSession(this.file, { type, ...data }, this.maxBytes); }
}

/** Full first state; subsequent state results carry only changed/removed lines. */
export class ResultSummary {
  previous = new Map();
  summarize(tool, text) {
    if (!['inv', 'status', 'changes'].includes(tool)) return oneLine(text).slice(0, 1000);
    const lines = String(text).split(/\r?\n/u).filter(Boolean);
    const old = this.previous.get(tool) ?? [];
    if (tool === 'changes' && /^(?:no changes|unchanged)$/iu.test(oneLine(text))) return 'unchanged';
    this.previous.set(tool, lines);
    const diff = [...old.filter(l => !lines.includes(l)).map(l => `- ${l}`), ...lines.filter(l => !old.includes(l)).map(l => `+ ${l}`)];
    return oneLine(diff.join('; ')).slice(0, 1500) || 'unchanged';
  }
}

export function readSession(file) {
  const parts = [file];
  for (let i = 1; existsSync(`${file}.${i}`); i++) parts.push(`${file}.${i}`);
  return parts.flatMap(part => readFileSync(part, 'utf8').split('\n').filter(Boolean).flatMap(line => {
    try { return [JSON.parse(line)]; } catch { return []; }
  })).sort((a, b) => Date.parse(a.time) - Date.parse(b.time));
}

export function lastLessons(file, count) {
  if (!count || !existsSync(file)) return '';
  const entries = readFileSync(file, 'utf8').split(/(?=^## )/mu).filter(s => s.startsWith('## '));
  return `\nLessons from previous sessions:\n${entries.slice(-count).join('\n').slice(-2400)}\n`;
}
