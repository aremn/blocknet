import { writeFileSync, readdirSync } from 'node:fs';
import { dirname, join, basename, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readSession, oneLine } from './session-log.js';

const clock = time => String(time ?? '').slice(11, 19);
const clean = value => oneLine(value).replace(/\|/gu, '/');
export function momentLine(event) {
  const s = event.snapshot ?? {}, kind = event.kind;
  const context = `while ${s.task ?? 'idle'} at ${s.pos ?? '?'}, ${Number(s.time) >= 13000 && Number(s.time) < 23000 ? 'night' : 'day'}, light ${s.light ?? '?'}`;
  const detail = kind === 'damage' ? `took ${event.amount} damage from ${event.attacker ?? event.source ?? event.note}${event.attacker_distance == null ? '' : ` (${event.attacker_distance} m)`}, health ${event.health_before} -> ${event.health_after}` : `${kind}: ${event.note ?? ''}`;
  const entities = (s.entities ?? []).map(e => `${e.type} ${e.distance}m${e.hostile ? ' hostile' : ''}${e.name ? ` ${e.name}` : ''}`).join(', ');
  return `${detail} ${context}; food ${s.food ?? '?'}, held ${s.held ?? '?'}, ${s.dimension ?? '?'}/${s.biome ?? '?'}, ${s.weather ?? '?'}; under ${s.under ?? '?'}, front ${s.front ?? '?'}.` +
    ` ${s.last_report ? `Last report: ${s.last_report}. ` : ''}Tools: ${(s.tools ?? []).map(t => `${t.tool} ${JSON.stringify(t.args)}`).join('; ') || 'none'}. Nearby: ${entities || 'none'}; hazards: ${(s.hazards ?? []).join(', ') || 'none'}` +
    (s.unseen?.length || event.unseen ? `; unseen: ${JSON.stringify({ snapshot: s.unseen ?? [], ...event.unseen })}` : '');
}

/** Collapse damage bursts in older/unbatched logs too; keep the first context and final health. */
export function mergeDamageMoments(entries) {
  const result = [], pending = new Map();
  for (const original of entries) {
    const e = structuredClone(original);
    if (e.type === 'event' && e.event?.kind === 'damage') {
      const m = e.event, key = `${m.bot}/${m.source}/${m.attacker}`;
      const old = pending.get(key);
      if (old && Date.parse(e.time)-Date.parse(old.lastTime) <= 2000) {
        old.entry.event.amount += m.amount;
        old.entry.event.health_after = m.health_after;
        Object.assign(old.entry.event.snapshot ??= {}, { health_after: m.health_after, health: m.health_after });
        old.lastTime = e.time;
        continue;
      }
      pending.set(key, { entry: e, lastTime: e.time });
    }
    if (e.event?.kind === 'death') pending.clear();
    result.push(e);
  }
  return result;
}

export function summarize(entries, lastN = 10) {
  entries = mergeDamageMoments(entries);
  const session = entries.find(e => e.type === 'session') ?? {};
  const start = Date.parse(entries[0]?.time), end = Date.parse(entries.at(-1)?.time);
  const calls = new Map(), counts = new Map(), failures = [], feedback = new Map();
  let tokens = 0;
  for (const e of entries) {
    if (e.type === 'tool_call') { calls.set(e.id, e); const c = counts.get(e.tool) ?? { calls: 0, failures: 0 }; c.calls++; counts.set(e.tool, c); }
    if (e.type === 'tool_result' && e.failed) { const name = e.tool ?? calls.get(e.id)?.tool ?? '?'; const c = counts.get(name) ?? { calls: 0, failures: 0 }; c.failures++; counts.set(name, c); failures.push(e); }
    if (e.type === 'turn' && e.error) failures.push(e);
    if (e.type === 'turn') { const u = e.usage ?? {}; tokens += u.totalTokens ?? ((u.inputTokens ?? u.input_tokens ?? 0) + (u.outputTokens ?? u.output_tokens ?? 0)); }
    if (e.type === 'tool_feedback') { const list = feedback.get(e.kind) ?? []; list.push(e); feedback.set(e.kind, list); }
  }
  const moments = entries.filter(e => e.type === 'event' && e.event?.type === 'moment');
  const deaths = moments.filter(e => e.event.kind === 'death');
  const progress = entries.filter(e => e.type === 'tool_result' && !e.failed && !['status', 'inv', 'changes', 'tool_feedback', 'say', 'plan', 'memory'].includes(e.tool)
    || e.type === 'event' && e.event?.type === 'task.finished' && !/failed|blocked|cancelled/iu.test(e.event.result));
  const boundaries = [entries[0], ...progress, entries.at(-1)].filter(Boolean);
  const gaps = boundaries.slice(1).map((e, i) => ({ from: boundaries[i].time, to: e.time, ms: Date.parse(e.time) - Date.parse(boundaries[i].time) })).sort((a,b) => b.ms-a.ms).slice(0,3);
  const lines = [`# Blocknet session ${session.session ?? ''}`, '', `Goal: ${session.goal || '(player chat)'}`, `Bot: ${session.bot ?? '?'}. Duration: ${Math.max(0, (end-start)/1000) || 0}s. Turns: ${entries.filter(e => e.type === 'turn').length}. Tokens: ${tokens}.`, '',
    '| Tool | Calls | Failures |', '| --- | ---: | ---: |', ...[...counts].map(([name,c]) => `| ${name} | ${c.calls} | ${c.failures} |`), '', '## Key moments', '',
    ...moments.map(e => `- ${clock(e.time)} ${momentLine(e.event)}`), '', '## Deaths: preceding 30 seconds', ''];
  for (const death of deaths) {
    lines.push(`### ${clock(death.time)} ${death.event.note ?? 'death'}`, '');
    for (const e of entries.filter(e => Date.parse(e.time) >= Date.parse(death.time)-30000 && Date.parse(e.time) <= Date.parse(death.time))) {
      if (e.type === 'event' && e.event?.type === 'moment') lines.push(`- ${clock(e.time)} ${momentLine(e.event)}`);
      else if (e.type === 'tool_result' || e.type === 'chat' || e.type === 'model_message') lines.push(`- ${clock(e.time)} ${e.tool ?? e.role ?? e.type}: ${clean(e.result ?? e.text)}`);
    }
  }
  lines.push('', '## Tool feedback', '');
  for (const [kind, list] of feedback) lines.push(`### ${kind}`, '', ...list.map(e => `- ${e.source ? `[${e.source}] ` : ''}${e.tool ? `${e.tool}: ` : ''}${clean(e.note)}`), '');
  lines.push('## Last failures', '', ...failures.slice(-lastN).map(e => `- ${clock(e.time)} ${e.tool ?? 'model'}: ${clean(e.result ?? e.error)}`), '', '## Longest gaps between progress', '', ...gaps.map(g => `- ${clock(g.from)} to ${clock(g.to)}: ${(g.ms/1000).toFixed(1)}s`), '');
  return lines.join('\n');
}

export function report(file, lastN = 10) {
  file = resolve(file);
  const output = join(dirname(file), `report-${basename(file).replace(/^session-/u, '').replace(/\.jsonl$/u, '')}.md`);
  const markdown = summarize(readSession(file), lastN);
  writeFileSync(output, markdown);
  return { output, markdown };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const dir = join(dirname(fileURLToPath(import.meta.url)), 'logs');
  const file = process.argv[2] ?? join(dir, readdirSync(dir).filter(f => /^session-.*\.jsonl$/u.test(f)).sort().at(-1) ?? '');
  const result = report(file, Number(process.argv[3] ?? 10));
  process.stdout.write(`${result.markdown}\nWritten: ${result.output}\n`);
}
