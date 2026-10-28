import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { bodyTraceFile, readBodyTrace, render, timeline } from '../timeline.js';

test('timeline merges the brain session with the body trace in time order', async () => {
  await mkdir(resolve('build'), { recursive: true });
  const dir = await mkdtemp(resolve('build/timeline-test-'));
  try {
    const local = (h, m, s) => new Date(2026, 9, 28, h, m, s);
    const stamp = date => `2026-10-28 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}.000`;
    const body = join(dir, 'Kirill-trace.log');
    await writeFile(body, [
      `${stamp(local(12, 0, 2))} t=40 [nav] new leg from 0 64 0 to GoalGetToBlock{x=5,y=64,z=5} (normal: walk, dig)`,
      'not a trace line',
      `${stamp(local(12, 0, 4))} t=80 [aim] 5 64 5 is in reach but hidden behind stone at 4 64 5; walking around to it`,
      `${stamp(local(13, 0, 0))} t=9000 [task] long after the session`,
    ].join('\n'));
    const session = [
      { time: local(12, 0, 0).toISOString(), type: 'session', session: 's1', bot: 'Kirill', goal: 'wood' },
      { time: local(12, 0, 1).toISOString(), type: 'model_trace', model_role: 'planner', kind: 'reasoning', text: 'trees are north' },
      { time: local(12, 0, 3).toISOString(), type: 'plan_step_start', status: 'план: шаг 1/1 gather logs 3 — 0/3' },
      { time: local(12, 0, 5).toISOString(), type: 'tool_result', tool: 'gather', result: 'gathered 3 logs', failed: false, ms: 9000 },
    ];
    const bodyEntries = readBodyTrace(body);
    assert.equal(bodyEntries.length, 3);
    const text = render(timeline(session, bodyEntries));
    const lines = text.split('\n');
    assert.match(lines[0], /BRAIN SESSION s1/u);
    assert.match(lines[1], /BRAIN planner thinks: trees are north/u);
    assert.match(lines[2], /BODY  \[nav\] new leg/u);
    assert.match(lines[3], /BRAIN STEP план: шаг 1\/1/u);
    assert.match(lines[4], /BODY  \[aim\] .*hidden behind stone/u);
    assert.match(lines[5], /BRAIN brain gather -> gathered 3 logs \(9000 ms\)/u);
    assert.equal(lines.length, 6, 'body lines far outside the session are left out');
    assert.equal(bodyTraceFile('/games/atm10/config/blocknet_ai-bridge.json', 'Kirill'),
      resolve('/games/atm10/logs/blocknet/Kirill-trace.log'));
  } finally { await rm(dir, { recursive: true, force: true }); }
});
