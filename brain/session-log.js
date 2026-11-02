import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';

export const oneLine = value => String(value ?? '').replace(/\s+/gu, ' ').trim();
export const failed = value => /^(?:\[[^\]]+\] )?(?:failed|blocked|cancelled|not hungry|stopped|unsupported|none seen)\b|; stopped(?: before|:)/iu.test(String(value));

// Each process appends complete JSON lines; a full segment continues as <file>.1, <file>.2...
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

export function readSession(file) {
  const parts = [file];
  for (let i = 1; existsSync(`${file}.${i}`); i++) parts.push(`${file}.${i}`);
  return parts.flatMap(part => readFileSync(part, 'utf8').split('\n').filter(Boolean).flatMap(line => {
    try { return [JSON.parse(line)]; } catch { return []; }
  })).sort((a, b) => Date.parse(a.time) - Date.parse(b.time));
}
