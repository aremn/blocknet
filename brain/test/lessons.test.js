// ADR-0017: what the brain took from GregTech Arena.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Worker, checkJob } from '../brain.js';
import { Notebook, Refused, NOTES_MAX } from '../notebook.js';
import { Instincts, hint } from '../instincts.js';
import { pickTurn, seancePrompt, sessionModel } from '../seance.js';
import { isolationProblems } from '../check-isolation.js';
import { findings } from '../../tools/check-public.mjs';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, timeout = 3000) {
  const start = Date.now();
  while (!predicate()) { if (Date.now() - start > timeout) throw new Error('condition timed out'); await sleep(5); }
}

class FakeBody extends EventEmitter {
  hello = { bots: ['Kirill'] }; calls = []; reports = {};
  async ready() { return this.hello; }
  async call(bot, tool, args = {}) {
    this.calls.push({ tool, args });
    if (tool === 'inv') return { result: 'inventory r1: selected 0\nsummary: free slots 30' };
    if (tool === 'status') return { result: 'health 20/20; task: idle' };
    if (tool === 'item') return { result: args.query };
    if (['say', 'stop', 'follow'].includes(tool)) return { result: 'ok' };
    return { task_id: `t${this.calls.length}`, outcome: Promise.resolve(this.reports[tool] ?? `${tool} done`) };
  }
  waitTask() { return Promise.resolve(''); }
  close() {}
  said() { return this.calls.filter(c => c.tool === 'say').map(c => c.args.text); }
  actions() { return this.calls.filter(c => !['say', 'inv', 'status', 'item'].includes(c.tool)).map(c => [c.tool, c.args]); }
}
function setup(answers, options = {}) {
  const body = new FakeBody();
  const prompts = [];
  const backend = { backend: 'fake', model: 'fake', close() {}, send: async text => {
    prompts.push(text); const next = answers.shift();
    return { text: typeof next === 'function' ? next(text) : JSON.stringify(next ?? { reply: '', jobs: [] }) };
  } };
  const w = new Worker({ body, backend, bot: 'Kirill', ...options });
  const chat = (text, extra = {}) => body.emit('event', { type: 'chat', from: 'Alex', text, ...extra });
  const idle = () => until(() => !w.running && !w.current && w.queue.length === 0);
  return { body, prompts, w, chat, idle };
}

test('a named chest is remembered on disk and later orders use the name instead of pointing', async () => {
  const file = join(mkdtempSync(join(tmpdir(), 'bn-notebook-')), 'notebook.Kirill.json');
  const { body, prompts, w, chat, idle } = setup([
    { reply: 'Запомнил.', jobs: [{ job: 'name', target: 'p4', name: 'руда', done: 'Это рудный.' }] },
    text => { assert.match(text, /Named places: руда \(block 12 64 -3\)/u); return JSON.stringify({ reply: 'Ща.', jobs: [{ job: 'store', target: 'руда', what: 'loot' }] }); },
  ], { notebook: new Notebook(file) });
  chat('это сундук для руды', { pointed_ref: 'p4', pointed_block: '12, 64, -3' });
  await until(() => body.said().length === 2); await idle();
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')).places.map(p => [p.name, p.pos]), [['руда', [12, 64, -3]]]);
  chat('скинь добычу в рудный');
  await until(() => body.actions().length === 1); await idle();
  assert.deepEqual(body.actions(), [['deposit', { x: 12, y: 64, z: -3, filter: 'loot' }]]);
  assert.equal(prompts.length, 2);
  w.close();
  assert.deepEqual(new Notebook(file).place('РУДА').pos, [12, 64, -3], 'names survive a restart, in any case');
});

test('the notebook takes only fixed fields: limits, a repeated wish updates the old one, unknown names fail', () => {
  const book = new Notebook(null);
  assert.equal(book.note('не трогай берёзы у дома', 'Alex'), 'noted N1');
  assert.equal(book.note('не трогай берёзы возле дома', 'Alex'), 'updated note N1', 'the same wish in other words');
  assert.throws(() => book.note('x'.repeat(201)), Refused);
  assert.throws(() => book.name('дом', null), /look at the block/u);
  assert.throws(() => book.forget({ name: 'шахта' }), /no place named шахта/u);
  for (let i = 0; book.data.notes.length < NOTES_MAX; i++) book.note(`желание номер ${i} про ${'абвгдежзик'[i % 10]}${i}`);
  assert.throws(() => book.note('совсем другое желание'), /forget one first/u);
  assert.match(book.card(), /Player's standing wishes: N1 "не трогай берёзы возле дома"/u);
  assert.equal(checkJob({ job: 'store', target: 'сундук с рудой', what: 'loot' }), null);
  assert.match(checkJob({ job: 'move', from: 'руда', to: 'p2' }), /bad arguments/u, 'move stays on pointed refs');
  assert.match(checkJob({ job: 'name', target: 'руда', name: 'x' }), /bad arguments/u, 'naming needs a pointed block');
});

test('a store into a name the notebook lacks fails with a hint, said once in plain words', async () => {
  const { body, prompts, w, chat, idle } = setup([
    { reply: 'Ща.', jobs: [{ job: 'store', target: 'шахта', what: 'loot' }] },
    text => {
      assert.match(text, /The body reported: failed: no place named шахта/u);
      assert.match(text, /Hint \(where to look, not the answer\): The notebook has no place by that name/u);
      return '{"reply":"Не знаю, где это. Глянь на него и скажи.","jobs":[]}';
    },
  ]);
  chat('скинь всё в шахту');
  await until(() => body.said().length === 2); await idle();
  assert.deepEqual(body.actions(), [], 'nothing reached the body');
  assert.equal(prompts.length, 2);
  w.close();
});

test('instincts: a hint says where to look; the same failure again says repeating will not help', () => {
  assert.match(hint('blocked: need a pickaxe for minecraft:iron_ore'), /right tool/u);
  assert.match(hint('failed: inventory full; dropped 3'), /bag is full/u);
  assert.equal(hint('failed: something nobody planned for'), null);
  const instincts = new Instincts();
  const job = { job: 'get', item: 'minecraft:iron_ingot' };
  assert.equal(instincts.lines(job, 'failed: step 1: stuck in open at 1 64 2', 0).length, 1);
  const again = instincts.lines(job, 'failed: step 1: stuck in open at 7 70 9', 5 * 60_000);
  assert.match(again[1], /failed the same way 2 times in 5 min: do not suggest simply trying again/u, 'other coordinates, same failure');
  assert.equal(instincts.lines(job, 'failed: step 1: stuck in open at 1 64 2', 60 * 60_000).length, 1, 'forgotten after half an hour');
});

test('a death is remembered in the next translations, with where and how', async () => {
  const { body, prompts, w, chat } = setup([{ reply: 'Там лава была.', jobs: [] }]);
  body.emit('event', { type: 'bot.died', bot: 'Kirill', cause: 'lava', position: '10 12 -40', items_dropped: true });
  chat('ты чё умер?');
  await until(() => prompts.length === 1);
  assert.match(prompts[0], /Last death: lava at 10 12 -40, 0 min ago, items dropped there/u);
  w.close();
});

test('seance: a logged turn goes back to the model with its own answer and the question', () => {
  const entries = [
    { type: 'turn', backend: 'claude', model: 'haiku' },
    { type: 'translate', time: 't1', prompt: 'Player says: "дай угля"', raw: '{"reply":"Ща."}' },
    { type: 'explain', time: 't2', prompt: 'the job ended', raw: '{"reply":"Не вышло."}' },
  ];
  assert.equal(pickTurn(entries).time, 't2');
  assert.equal(pickTurn(entries, 1).time, 't1');
  assert.throws(() => pickTurn(entries, 5), /this session has 2/u);
  assert.equal(sessionModel(entries), 'claude:haiku');
  const prompt = seancePrompt(pickTurn(entries, 1), 'почему без deliver?');
  assert.match(prompt, /Player says: "дай угля"[\s\S]*\{"reply":"Ща\."\}[\s\S]*The developer asks: почему без deliver\?/u);
});

test('the isolation check fails on anything loaded beyond the prompt', () => {
  const clean = { cwd: join(tmpdir(), 'blocknet-model'), tools: [], mcp_servers: [], skills: [], slash_commands: [],
    plugins: [{ name: 'cc-plugin-telemetry' }] };
  assert.deepEqual(isolationProblems(clean), []);
  const leaky = { ...clean, skills: ['blocknet-dev'], plugins: [{ name: 'cc-plugin-agents-md' }],
    memory_paths: { auto: 'memory/' }, cwd: join(process.cwd(), 'brain') };
  assert.deepEqual(isolationProblems(leaky).map(p => p.split(':')[0]), ['skills', 'plugins', 'memory', 'working folder inside the repo']);
  assert.deepEqual(isolationProblems(undefined), ['the CLI sent no init report']);
});

test('the public check catches home paths, e-mails and tokens, not placeholders or noreply', () => {
  const what = text => findings('f', text).map(f => f.what);
  assert.deepEqual(what('export JAVA_HOME="/c/Users/alice/.jdks/jdk-21"'), ['user home path']); // public-check: allow
  assert.deepEqual(what('C:\\Users\\alice\\AppData\\Roaming'), ['user home path']); // public-check: allow
  assert.deepEqual(what('mail me: alice@mail.example.ru'), ['e-mail']); // public-check: allow
  assert.deepEqual(what(`token sk-ant-${'x'.repeat(20)}`), ['anthropic key']);
  assert.deepEqual(what('C:/Users/alice/x // public-check: allow'), [], 'the marker lets a fixture through');
  for (const fine of ['export JAVA_HOME="$HOME/.jdks/jdk-21"', 'C:/Users/<ты>/AppData', '46087989+aremn@users.noreply.github.com',
    '@Override', 'Co-Authored-By: Claude <noreply@anthropic.com>', 'C:\\Projects\\blocknet\\brain']) assert.deepEqual(what(fine), [], fine);
});
