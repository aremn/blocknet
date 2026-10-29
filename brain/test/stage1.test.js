import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PlanRunner, PlanLibrary, parseTool, validatePlan, checkDone } from '../plans.js';
import { createViewer } from '../viewer.js';

const transfer = { goal: 'move contents', budget_min: 1, steps: [
  { id: 1, do: 'transfer h10 h11', done: 'transferred h10 h11' },
] };

test('container refs and coordinate calls both parse with filters and counts', () => {
  assert.deepEqual(parseTool('withdraw h10 all 3').args, { ref: 'h10', filter: 'all', count: 3 });
  assert.deepEqual(parseTool('deposit -2 64 3 all 4').args, { x: -2, y: 64, z: 3, filter: 'all', count: 4 });
  assert.deepEqual(parseTool('look-in h10').args, { ref: 'h10' });
  assert.throws(() => parseTool('withdraw {"ref":"h10","x":1,"y":2,"z":3,"filter":"all"}'));
  assert.throws(() => validatePlan({ ...transfer, steps: [{ ...transfer.steps[0], done: 'pos 1 64 1' }] }), /cannot prove delivery/u);
});

test('failed transfer cannot be replaced with come or saved as a successful plan', async () => {
  let planned = 0, saved = 0;
  const said = [];
  const runner = new PlanRunner({
    call: async tool => ({ result: tool === 'status' ? 'dimension overworld, pos 0, 64, 0'
      : tool === 'inv' ? 'empty' : 'failed: no room' }),
    planner: async () => ({ text: JSON.stringify(++planned === 1 ? transfer : {
      goal: 'come instead', budget_min: 1, steps: [{ id: 1, do: 'come Alex', done: 'pos 8 64 8' }],
    }) }), actor: async () => { throw new Error('unexpected actor'); },
    library: { matches: async () => [], save: async () => { saved++; } }, say: async text => said.push(text),
  });
  assert.equal(await runner.run('move contents', 'Alex'), false);
  assert.equal(saved, 0);
  assert.match(said.at(-1), /Preserve final done/u);
  assert.equal(checkDone('transferred h10 h11', runner.state).pass, false);
});

test('blocked plans ask their concrete question and never act or enter successful memory', async () => {
  const calls = [], said = [];
  const runner = new PlanRunner({ call: async tool => { calls.push(tool); return { result: '' }; },
    planner: async () => ({ text: JSON.stringify({ status: 'blocked', reason: 'Which chest is the source?' }) }),
    actor: async () => { throw new Error('unexpected actor'); },
    library: { matches: async () => [], save: async () => { throw new Error('must not save'); } },
    say: async text => said.push(text) });
  assert.equal(await runner.run('move contents'), false);
  assert.deepEqual(calls, ['inv', 'status']);
  assert.deepEqual(said, ['Which chest is the source?']);
  assert.equal(runner.running, false);
});

test('a late cancelled transfer result cannot complete a new plan with the same refs', async () => {
  let resolveOld, planned=0;
  const runner=new PlanRunner({call:async tool=>({result:tool==='status'?'pos 0 64 0':tool==='inv'?'empty':'failed: no room'}),
    planner:async()=>({text:JSON.stringify(transfer)}),actor:async()=>({text:'<silent>'}),say:async()=>{},
    library:{matches:async()=>[],save:async()=>{throw new Error('must not cache spatial jobs');}}});
  runner.call=async tool=>{
    if(tool==='status')return {result:'pos 0 64 0'};
    if(tool==='inv')return {result:'empty'};
    if(tool==='transfer' && ++planned===1)return {outcome:new Promise(r=>{resolveOld=r;})};
    return {result:'failed: no room'};
  };
  const old=runner.run('move contents');
  while(!resolveOld) await new Promise(r=>setImmediate(r));
  runner.cancel(); assert.equal(await old,null);
  const current=runner.run('move contents');
  resolveOld('transferred h10 h11; source empty; delivered 1 iron_ingot; trips 1');
  assert.equal(await current,false);
  assert.equal(checkDone('transferred h10 h11',runner.state).pass,false);
});

test('legacy successful plans and spatial substitutes are excluded from reuse', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'blocknet-stage1-'));
  try {
    const file = join(dir, 'plans.jsonl');
    await writeFile(file, JSON.stringify({ goal: 'move contents', budget_min: 1,
      steps: [{ id: 1, do: 'come Alex', done: 'pos 8 64 8' }] }) + '\n');
    const library = new PlanLibrary(file);
    assert.deepEqual(await library.matches('move contents'), []);
    await library.save('move contents', transfer, 1);
    assert.deepEqual(await library.matches('move contents'), []);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('memory viewer serves assets and forwards only read-only memory queries', async () => {
  const requests = [];
  const body = { hello: { bots: ['Test'] }, request: async (method, params) => {
    requests.push({ method, params }); return { bot: 'Test', cells: [] };
  } };
  const server = createViewer(body);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal((await fetch(base)).status, 200);
    for (const asset of ['/viewer.js', '/viewer.css', '/3d', '/3d.js', '/3d.css']) assert.equal((await fetch(base + asset)).status, 200);
    const response = await fetch(base + '/api/memory?bot=Test&mode=slice&y=64');
    assert.equal(response.status, 200);
    assert.equal((await fetch(base + '/api/memory?bot=Test&mode=voxels&radius=96')).status, 200);
    assert.deepEqual(requests, [{ method: 'debug.memory', params: { bot: 'Test', mode: 'slice', y: 64 } },
      { method: 'debug.memory', params: { bot: 'Test', mode: 'voxels', radius: 96 } }]);
    assert.equal((await fetch(base + '/api/memory?bot=Test', { method: 'POST' })).status, 405);
    assert.equal((await fetch(base + '/api/bots', { headers: { Origin: 'https://example.org' } })).status, 403);
    body.hello = null;
    assert.equal((await fetch(base + '/api/memory?bot=Test')).status, 503);
    assert.equal(requests.length, 2);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
