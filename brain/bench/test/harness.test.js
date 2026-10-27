import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdtemp, rm, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { createInterface } from 'node:readline';
import { runBench, scenarios, roleScenarios, parseArgs, normalizeUsage } from '../run.js';
import { evaluate } from '../rules.js';
import { tools } from '../fake-body.js';
import { FakeBackend } from './fake-backend.js';
import { CodexBackend, buildCodexArgs } from '../../codex-backend.js';
import { ClaudeBackend, buildClaudeArgs } from '../../claude-backend.js';

test('harness exercises real MCP + TCP, cold/warm reuse, every scenario passes or fails one rule, writes tables and raw JSONL', async () => {
  await mkdir(resolve('build'), { recursive: true });
  const outputDir = await mkdtemp(resolve('build/bench-test-'));
  const instances = [];
  try {
    const { records, reportFile, rawFile } = await runBench({ models: ['codex:passing', 'codex:failing'],
      tiers: ['default', 'priority'], repeat: 3, outputDir, backendFactory(options) {
        const instance = new FakeBackend(options); instances.push(instance); return instance;
      } });
    assert.equal(records.length, 96);
    assert.equal(instances.length, 32); // One backend per model/tier/scenario, never per warm repeat.
    assert.ok(instances.every(instance => instance.sends === 3));
    const failedRules = ['gatherWithin', 'short', 'mentions', 'calls', 'observeExplore', 'dangerFirst', 'gatherBeforeCraft', 'silent'];
    for (const row of records) {
      assert.equal(row.temperature, row.repeat === 1 ? 'cold' : 'warm');
      assert.equal(row.pass, row.model === 'passing');
      assert.deepEqual(row.failedRules, row.pass ? [] : [failedRules[scenarios.findIndex(s => s.id === row.scenario)]]);
      assert.deepEqual(row.tokens, { input: 100, cached: 20, output: 5 });
      assert.equal(row.toolCalls, row.calls.length);
      assert.ok(row.ms >= 0 && row.ttftMs != null);
    }
    const md = await readFile(reportFile, 'utf8');
    assert.match(md, /\| 1 \| codex:passing/);
    assert.match(md, /\| cold \| 8 \|/);
    assert.match(md, /\| warm \| 16 \|/);
    assert.match(md, /100%/);
    assert.match(md, /0%/);
    assert.match(md, /Failed rules:/);
    assert.deepEqual((await readFile(rawFile, 'utf8')).trim().split('\n').map(JSON.parse), records);
  } finally { await rm(outputDir, { recursive: true, force: true }); }
});

test('invalid coordinates, unsafe danger actions and reversed mining/crafting fail independently', () => {
  const wood = scenarios[0];
  const gathered = { tool: 'gather', args: { item: 'logs' }, result: 'gathered 4 oak_log' };
  assert.deepEqual(evaluate(wood, [gathered, {tool: 'goto', args: {x: 999, y: 64, z: 999}, result: 'failed: unobserved'}], 'Готово').failedRules, ['observedTargets']);
  assert.deepEqual(evaluate(wood, [...Array.from({length: 3}, () => ({tool: 'scene', args: {}, result: wood.scene})), gathered], 'Готово').failedRules, ['gatherWithin']);
  const danger = scenarios.find(s => s.id === 'night-danger');
  assert.equal(evaluate(danger, [{tool: 'goto', args: {ref: 'z1'}, result: 'arrived'}], 'Ок').pass, false);
  const stone = scenarios.find(s => s.id === 'stone-pickaxe');
  assert.equal(evaluate(stone, [{tool: 'craft', args: {item: 'stone_pickaxe'}, result: 'crafted'},
    {tool: 'gather', args: {item: 'cobblestone'}, result: 'gathered'}], 'Готово').pass, false);
});

test('backend errors are recorded; recovery is cold and missing usage remains unknown', async () => {
  const outputDir = await mkdtemp(resolve('build/bench-error-test-'));
  let count = 0, closes = 0;
  try {
    const result = await runBench({ models: ['claude:haiku'], cases: [scenarios[3]], repeat: 2, outputDir,
      backendFactory: () => ({ send: async () => { if (++count === 1) throw new Error('fake unavailable'); return {text: 'Привет!'}; }, close: () => { closes++; } }) });
    assert.equal(result.records[0].error, 'fake unavailable');
    assert.deepEqual(result.records[0].failedRules, ['backendError']);
    assert.equal(result.records[1].temperature, 'cold');
    assert.equal(result.records[1].pass, true);
    assert.equal(result.records[1].tokens.input, null);
    assert.equal(closes, 2);
    assert.match(await readFile(result.reportFile, 'utf8'), /tier n\/a/);
    assert.deepEqual(normalizeUsage({input_tokens: 10, cache_read_input_tokens: 20, cache_creation_input_tokens: 5, output_tokens: 3}, 'claude'), {input: 35, cached: 20, output: 3});
  } finally { await rm(outputDir, { recursive: true, force: true }); }
});

function fakeChild(receive) {
  const child = new EventEmitter();
  child.stdin = new PassThrough(); child.stdout = new PassThrough(); child.stderr = new PassThrough();
  const lines = createInterface({input: child.stdin});
  lines.on('line', line => receive(JSON.parse(line), value => child.stdout.write(`${JSON.stringify(value)}\n`)));
  child.kill = () => { lines.close(); child.emit('close', 0); };
  return child;
}

test('model/tier options reach both backend argv and Codex thread/turn requests; old defaults remain', async () => {
  assert.ok(buildClaudeArgs().includes('haiku'));
  assert.ok(buildClaudeArgs({model: 'sonnet'}).includes('sonnet'));
  assert.ok(buildCodexArgs().includes('service_tier="priority"'));
  for (const serviceTier of ['default', 'priority']) {
    const requests = [];
    const backend = new CodexBackend({model: 'test-model', serviceTier, spawnProcess(command, args) {
      assert.ok(args.includes(`service_tier="${serviceTier}"`));
      assert.ok(args.includes('model="test-model"'));
      return fakeChild((request, send) => {
        requests.push(request);
        if (request.id) send({id: request.id, result: request.method === 'thread/start' ? {thread: {id: 'fake'}} : {}});
        if (request.method === 'turn/start') send({method: 'turn/completed', params: {turn: {status: 'completed'}}});
      });
    }});
    try {
      await backend.send('test');
      for (const method of ['thread/start', 'turn/start']) assert.equal(requests.find(r => r.method === method).params.serviceTier, serviceTier);
    } finally { backend.close(); }
  }
  const claude = new ClaudeBackend({model: 'sonnet', spawnProcess(command, args) {
    assert.equal(args[args.indexOf('--model') + 1], 'sonnet');
    return fakeChild((request, send) => send({type: 'result', result: 'Привет'}));
  }});
  try { await claude.send('hi'); assert.equal(claude.model, 'sonnet'); } finally { claude.close(); }
});

test('tool fixture matches all real registry names/descriptions/task flags; CLI rejects malformed options', async () => {
  const source = await readFile(resolve('mod/src/main/java/dev/blocknet/bridge/BridgeTools.java'), 'utf8');
  const real = [...source.matchAll(/add\("([^"]+)", "([^"]+)", (true|false)/g)].map(([,name,description,task]) => ({name,description,task: task === 'true'}));
  assert.deepEqual(tools.map(({name, description, task}) => ({name,description,task})), real);
  assert.equal(tools.length, real.length);
  assert.equal(parseArgs(['--fake', '--repeat', '2']).fake, true);
  for (const argv of [['--repeat','0'], ['--models','oops'], ['--tiers','flex'], ['--repeat'], ['--unknown']]) assert.throws(() => parseArgs(argv));
});

test('planner and actor roles use isolated MCP, checkable ordered plans and single-step scenarios', async () => {
  const outputDir = await mkdtemp(resolve('build/bench-role-test-'));
  try {
    for (const role of ['planner', 'actor']) {
      assert.equal(parseArgs(['--role', role]).role, role);
      const { records } = await runBench({ role, models: ['codex:passing', 'codex:failing'], repeat: 1,
        outputDir, backendFactory: options => new FakeBackend(options) });
      assert.equal(records.length, 4);
      for (const row of records) {
        assert.equal(row.role, role); assert.equal(row.pass, row.model === 'passing');
        if (role === 'planner') { assert.equal(row.calls.length, 0); assert.match(row.input, /Available skills\/tools/u); }
        else assert.match(row.input, /план: шаг/u);
      }
    }
    const iron = roleScenarios.planner[0];
    const reversed = { ...iron.samplePlan, steps: [...iron.samplePlan.steps].reverse().map((s, i) => ({ ...s, id: i + 1 })) };
    assert.equal(evaluate(iron, [], JSON.stringify(reversed)).pass, false);
    assert.throws(() => parseArgs(['--role', 'critic']));
  } finally { await rm(outputDir, { recursive: true, force: true }); }
});
