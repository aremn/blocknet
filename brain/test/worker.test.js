import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Worker, parseReply, checkJob, inventoryCounts, parseModel, systemPrompt } from '../brain.js';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, timeout = 3000) {
  const start = Date.now();
  while (!predicate()) { if (Date.now() - start > timeout) throw new Error('condition timed out'); await sleep(5); }
}

/** A body whose tasks finish when the test says so (or at once with a canned report). */
class FakeBody extends EventEmitter {
  hello = { bots: ['Kirill'] }; calls = []; tasks = new Map(); bag = ''; reports = {}; hold = new Set();
  async ready() { return this.hello; }
  async call(bot, tool, args = {}) {
    this.calls.push({ tool, args });
    if (tool === 'inv') return { result: `inventory r1: selected 0\n${this.bag}\nsummary: free slots 30` };
    if (tool === 'status') return { result: 'health 20/20, food 20, pos 0, 64, 0; task: idle' };
    if (['say', 'speak', 'heard', 'stop', 'follow'].includes(tool)) {
      if (tool === 'stop') for (const task of this.tasks.values()) this.settle(task, 'failed: cancelled');
      return { result: 'ok' };
    }
    const task_id = `t${this.calls.length}`, task = { tool };
    task.outcome = new Promise(resolve => { task.resolve = resolve; });
    this.tasks.set(task_id, task);
    if (!this.hold.has(tool)) setImmediate(() => this.finish(tool, this.reports[tool] ?? `${tool} done`));
    return { task_id };
  }
  waitTask(id) { return this.tasks.get(id).outcome; }
  settle(task, report) { if (!task.done) { task.done = true; task.resolve(report); } }
  finish(tool, report) {
    const task = [...this.tasks.values()].find(t => t.tool === tool && !t.done);
    if (task) this.settle(task, report);
  }
  close() {}
  said() { return this.calls.filter(c => c.tool === 'say').map(c => c.args.text); }
  actions() { return this.calls.filter(c => !['say', 'inv', 'status', 'speak', 'heard'].includes(c.tool)).map(c => [c.tool, c.args]); }
}
function model(answers) {
  const prompts = [];
  return { prompts, backend: 'fake', model: 'fake', close() {},
    send: async text => { prompts.push(text); const next = answers.shift(); return { text: typeof next === 'function' ? next(text) : JSON.stringify(next ?? { reply: '', jobs: [] }) }; } };
}
async function worker(answers, setup = () => {}) {
  const body = new FakeBody(); setup(body);
  const backend = model(answers);
  const w = new Worker({ body, backend, bot: 'Kirill' });
  await w.start();
  const chat = (text, extra = {}) => body.emit('event', { type: 'chat', from: 'Alex', text, ...extra });
  return { body, backend, w, chat, idle: () => until(() => !w.running && !w.current && w.queue.length === 0) };
}

test('"принеси 32 железа": one model turn, obtain the total, hand it over, two lines in all', async () => {
  const { body, backend, w, chat, idle } = await worker([{ reply: 'Ща.', jobs: [{ job: 'get', item: 'minecraft:iron_ingot', count: 32, deliver: true, source: 'any', done: 'Держи.' }] }],
    b => { b.bag = '10 iron_ingot slots [1]'; b.reports.give = 'gave 32 minecraft:iron_ingot to Alex'; });
  chat('принеси 32 железа');
  await until(() => body.said().length === 2); await idle();
  assert.equal(backend.prompts.length, 1, 'one model call, no planner, no step actor');
  assert.match(backend.prompts[0], /says: "принеси 32 железа"[\s\S]*Bag: 10 iron_ingot[\s\S]*Work: idle/u);
  assert.deepEqual(body.actions(), [['obtain', { item: 'minecraft:iron_ingot', count: 32 }], ['give', { player: 'Alex', item: 'minecraft:iron_ingot', count: 32 }]]);
  assert.deepEqual(body.said(), ['Ща.', 'Держи.']);
  w.close();
});

test('"добудь 20 угля" mines 20 more than owned, from the world, by rule and without the model', async () => {
  const { body, backend, w, chat, idle } = await worker([], b => { b.bag = '5 coal slots [2]'; });
  chat('накопай 20 угля');
  await until(() => body.said().length === 1); await idle();
  assert.equal(backend.prompts.length, 0);
  assert.deepEqual(body.actions(), [['obtain', { item: 'coal', count: 25, source: 'world' }]]);
  assert.deepEqual(body.said(), ['Готово.']);
  w.close();
});

test('a failed or partial job is told once in plain words, never as "done"', async () => {
  const { body, backend, w, chat, idle } = await worker([
    { reply: 'Ага.', jobs: [{ job: 'clear', radius: 4, done: 'Площадка готова.' }] },
    text => { assert.match(text, /The body reported: partly cleared 9x9/u); return '{"reply":"Почти всё, две ямы засыпать нечем.","jobs":[]}'; },
  ], b => { b.reports.clear = 'partly cleared 9x9 around 0 0 to floor y=63: broke 40, filled 3 holes; 2 holes left: no dirt or stone to fill them'; });
  chat('расчисти тут площадку');
  await until(() => body.said().length === 2); await idle();
  assert.deepEqual(body.actions(), [['clear', { radius: 4, player: 'Alex' }]]);
  assert.deepEqual(body.said(), ['Ага.', 'Почти всё, две ямы засыпать нечем.']);
  assert.ok(!body.said().includes('Площадка готова.'));
  assert.equal(backend.prompts.length, 2);
  w.close();
});

test('orders queue behind the running job; "вместо этого" replaces it; "стоп" drops everything', async () => {
  const { body, w, chat } = await worker([
    { reply: 'Иду копать.', jobs: [{ job: 'get', item: 'minecraft:coal', count: 8, source: 'world', done: 'Уголь есть.' }] },
    { reply: 'Потом переплавлю.', jobs: [{ job: 'smelt', done: 'Переплавил.' }] },
    { reply: 'Ладно, бросаю.', jobs: [{ job: 'store', target: 'p3', what: 'loot', done: 'Сложил.' }], now: true },
  ], b => { b.hold.add('obtain'); b.hold.add('deposit'); });
  chat('мне угля бы, штук восемь');
  await until(() => body.actions().length === 1);
  chat('потом переплавь руду');
  await until(() => w.queue.length === 1);
  assert.equal(w.queue[0].job, 'smelt', 'waits behind the coal');
  chat('вместо этого сложи всё сюда', { pointed_ref: 'p3', pointed_block: '1, 64, 2' });
  await until(() => body.actions().some(([tool]) => tool === 'deposit'));
  assert.deepEqual(body.actions().map(([tool]) => tool), ['obtain', 'stop', 'deposit'], 'the queued smelt was dropped with the coal job');
  assert.ok(!body.said().includes('Уголь есть.'), 'a replaced job says nothing');
  chat('стоп');
  await until(() => body.actions().filter(([tool]) => tool === 'stop').length === 2);
  await until(() => !w.running);
  assert.deepEqual(body.said(), ['Иду копать.', 'Потом переплавлю.', 'Ладно, бросаю.']);
  w.close();
});

test('questions are answered from state without jobs; unknown work is declined honestly', async () => {
  const { body, backend, w, chat } = await worker([
    { reply: 'Копаю уголь, почти всё.', jobs: [] },
    { reply: 'Строить пока не умею.', jobs: [] },
  ], b => b.hold.add('obtain'));
  w.queue.push({ job: 'get', item: 'minecraft:coal', count: 8, player: 'Alex' }); w.pump();
  await until(() => w.current);
  chat('что делаешь?');
  chat('построй дом');
  await until(() => body.said().length === 2);
  assert.match(backend.prompts[0], /Work: get 8 minecraft:coal/u);
  assert.deepEqual(body.actions().map(([tool]) => tool), ['obtain'], 'no new work started');
  w.close();
});

test('the model text is checked: bad or unknown jobs never reach the body', () => {
  const parsed = parseReply('Вот: ```json\n{"reply":"Ок","jobs":[{"job":"get","item":"minecraft:coal","count":8},{"job":"build","what":"house"},{"job":"clear","radius":40},{"job":"store","target":"chest","what":"loot"}]}\n```');
  assert.equal(parsed.reply, 'Ок');
  assert.deepEqual(parsed.jobs, [{ job: 'get', item: 'minecraft:coal', count: 8 }]);
  assert.equal(parsed.invalid.length, 3);
  assert.equal(parseReply('не json').jobs.length, 0);
  assert.equal(checkJob({ job: 'take', target: 'p1', item: 'minecraft:coal' }), null);
  assert.deepEqual(inventoryCounts('inventory r1: selected 0\nmain: stone_pickaxe\nstone_pickaxe slots [0]\n3 minecraft:coal slots [1]\nsummary: free slots 30'),
    { stone_pickaxe: 1, coal: 3 });
  assert.deepEqual(parseModel('codex:gpt-6.1-sol:low'), { backend: 'codex', model: 'gpt-6.1-sol', effort: 'low' });
  assert.match(systemPrompt, /"job":"clear"/u);
});
