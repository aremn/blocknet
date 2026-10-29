import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runScript, handleSkillTool, listSkills } from '../skills.js';

const tools = ['obtain', 'goto', 'look-in', 'inv', 'stop', 'deposit'].map(name => ({ name }));

/** A fake body: obtain adds items, deposit removes them, inv reports them like the real body. */
function fakeBody(script = {}) {
  const items = { iron_pickaxe: 1, ...(script.items ?? {}) };
  const calls = [];
  const call = async (tool, args) => {
    calls.push([tool, args]);
    if (script[tool]) return script[tool](args, items);
    if (tool === 'inv') return `+ inventory r1: selected 0; ${Object.entries(items).map(([k, v]) => `${v} ${k} slots [0]`).join('; ')}; free slots 30`;
    if (tool === 'obtain') { items[args.item] = Math.max(items[args.item] ?? 0, args.count); return `[Bot] obtained ${args.count} ${args.item}`; }
    if (tool === 'deposit') { const n = items[args.filter] ?? 0; delete items[args.filter]; return `deposited ${n} ${args.filter}`; }
    return `[Bot] ${tool} ok`;
  };
  return { call, calls, items };
}

test('a script calls body tools in order, reports its result and the inventory change', async () => {
  const body = fakeBody();
  const run = await runScript({ tools, call: body.call, args: { count: 5 }, code: `
    await bot.obtain({ item: 'coal', count: args.count });
    const have = await bot.count('coal');
    await bot.lookIn({ ref: 'h1' });
    return 'have ' + have + ' coal';` });
  assert.equal(run.ok, true, run.error);
  assert.equal(run.result, 'have 5 coal');
  assert.deepEqual(run.gained, { coal: 5 });
  assert.deepEqual(body.calls.filter(([t]) => t !== 'inv').map(([t]) => t), ['obtain', 'look-in']);
});

test('a failed tool throws into the script, which can fall back', async () => {
  const body = fakeBody({ goto: () => 'failed: no path to h1' });
  const run = await runScript({ tools, call: body.call, code: `
    try { await bot.goto({ ref: 'h1' }); } catch (e) { bot.log('goto failed: ' + e.message); return 'fallback'; }
    return 'arrived';` });
  assert.equal(run.result, 'fallback');
  assert.ok(run.log.some(line => line.includes('goto failed: failed: no path')));
});

test('a stop ends the run even when the script swallows errors in a loop', async () => {
  let n = 0;
  const body = fakeBody({ obtain: () => (++n > 2 ? 'failed: cancelled' : 'failed: no coal nearby') });
  const run = await runScript({ tools, call: body.call, budgetMs: 5_000, code: `
    while (true) { try { await bot.obtain({ item: 'coal', count: 1 }); } catch {} }` });
  assert.equal(run.ok, false);
  assert.match(run.error, /cancelled/u);
  assert.ok(run.ms < 3_000, `stopped promptly (${run.ms} ms)`);
});

test('scripts cannot spin, eval or reach unknown tools', async () => {
  const body = fakeBody();
  const spin = await runScript({ tools, call: body.call, code: 'while (true) {}' });
  assert.equal(spin.ok, false);
  assert.match(spin.error, /timed out/u);
  const evil = await runScript({ tools, call: body.call, code: "return eval('1 + 1');" });
  assert.equal(evil.ok, false);
  const unknown = await runScript({ tools, call: body.call, code: 'await bot.fly({});' });
  assert.match(unknown.error, /unknown tool bot.fly/u);
});

test('skills are saved, listed, run with args and counted', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'blocknet-skills-'));
  process.env.BLOCKNET_SKILLS = dir;
  try {
    const body = fakeBody();
    const context = { call: body.call, tools };
    assert.equal(await handleSkillTool('skills', {}, context), 'no saved skills yet');
    await assert.rejects(handleSkillTool('save_skill', { name: 'Bad Name', description: 'x', code: 'return 1' }, context), /name/u);
    await assert.rejects(handleSkillTool('save_skill', { name: 'broken', description: 'x', code: 'return (' }, context));
    assert.equal(await handleSkillTool('save_skill', { name: 'store_item', description: 'Obtain an item and store it in a chest.',
      params: { item: 'item id', chest: 'container ref' }, code: `
        await bot.obtain({ item: args.item, count: 3 });
        await bot.deposit({ ref: args.chest, filter: args.item });
        return 'stored ' + args.item;` }, context), 'saved skill store_item');
    assert.match(await handleSkillTool('skills', { query: 'chest' }, context), /^store_item\(item: item id, chest: container ref\): Obtain an item/u);
    const result = await handleSkillTool('skill', { name: 'store_item', args: { item: 'coal', chest: 'h2' } }, context);
    assert.match(result, /^store_item: ok: stored coal/u);
    assert.deepEqual(body.calls.find(([t]) => t === 'deposit')[1], { ref: 'h2', filter: 'coal' });
    const [saved] = await listSkills('store');
    assert.equal(saved.runs, 1); assert.equal(saved.successes, 1);
  } finally {
    delete process.env.BLOCKNET_SKILLS;
    await rm(dir, { recursive: true, force: true });
  }
});
