import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { BrainSession } from '../brain.js';
import { parseRole, parseTool, checkDone, inventoryCounts, PlanLibrary, toolCatalog, validatePlan, parseCondition } from '../plans.js';
import { pickaxePlan } from '../bench/role-scenarios.js';
import { buildCodexArgs } from '../codex-backend.js';
import { buildClaudeArgs } from '../claude-backend.js';

const goal = 'железная кирка с нуля';
const backend = send => ({ backend: 'fake', model: 'fake', send, close() {} });
class FakeBody extends EventEmitter {
  hello = { bots: ['Kirill'], tools: toolCatalog };
  inventory = {}; calls = []; tasks = new Map();
  async ready() { return this.hello; }
  async call(bot, tool, args = {}) {
    this.calls.push({ tool, args });
    if (tool === 'inv') return { result: `inventory r1: selected 0\nmain: stone_pickaxe\n${Object.entries(this.inventory).map(([item, n], i) => `${n === 1 ? '' : n + ' '}${item} slots [${i}]`).join('\n')}\nsummary: best pickaxe stone_pickaxe, free slots 30` };
    if (tool === 'status') return { result: 'health 20/20, food 20, pos 0, 64, 0' };
    if (tool === 'scene') return { result: 'forest, day; observed trees t1; stone underfoot' };
    if (tool === 'changes') return { result: 'no changes' };
    if (['obtain', 'craft', 'mine_ore'].includes(tool)) {
      const task_id = `task-${this.calls.length}`;
      this.tasks.set(task_id, Promise.resolve().then(() => {
        if (this.fail && args.item === 'iron_ingot') return 'failed: lava blocks the tunnel';
        if (this.noGain && args.item === 'iron_ingot') { this.noGain = false; return 'obtained iron'; }
        this.inventory[args.item] = args.count ?? 1;
        if (tool === 'craft' && args.item === 'iron_pickaxe') delete this.inventory.iron_ingot;
        return `obtained ${args.item}`;
      }));
      return { task_id };
    }
    return { result: 'ok' };
  }
  waitTask(id) { return this.tasks.get(id); }
  close() {}
}
async function fixture(run) {
  await mkdir(resolve('build'), { recursive: true });
  const dir = await mkdtemp(resolve('build/plan-test-'));
  const sessions = [];
  try { await run({ dir, sessions }); }
  finally { for (const session of sessions) session.close(); await rm(dir, { recursive: true, force: true }); }
}
async function request({ body = new FakeBody(), planner, actor = backend(async () => { throw new Error('unexpected actor call'); }), dir, sessions, text = goal, persona,
  chat = backend(async () => ({ text: JSON.stringify({ reply: 'Working on the next step.' }) })) }) {
  const events = [];
  const session = new BrainSession({ body, backend: actor, plannerBackend: planner, bot: 'Kirill',
    chatBackend: chat,
    plansFile: join(dir, 'plans.jsonl'), persona, log: { write: (type, data) => events.push({ type, ...data }) } });
  sessions.push(session); await session.start();
  body.emit('event', { type: 'chat', from: 'Alex', text });
  // event() starts processing synchronously, so joining the pending queue needs no polling timer.
  while (session.busy) await new Promise(resolve => setImmediate(resolve));
  return { session, body, events };
}

test('goal chat calls planner, retries invalid JSON once, executes through fake body and critic verifies inventory', async t => fixture(async f => {
  const prompts = [];
  const planner = backend(async text => { prompts.push(text); return { text: prompts.length === 1 ? '{invalid' : JSON.stringify(pickaxePlan) }; });
  const { session, body, events } = await request({ ...f, planner });
  assert.equal(prompts.length, 2);
  assert.match(prompts[1], /Invalid output:/u);
  assert.match(prompts[0], /knowledge.*ore heights/iu);
  assert.equal(events.filter(e => e.type === 'plan_step' && e.pass).length, 3);
  assert.equal(body.inventory.iron_pickaxe, 1);
  assert.equal(session.plans.status(), 'план: выполнен');
  assert.deepEqual(body.calls.filter(c => ['obtain', 'craft'].includes(c.tool)).map(c => [c.tool, c.args.item]),
    [['obtain', 'stone_pickaxe'], ['obtain', 'iron_ingot'], ['craft', 'iron_pickaxe']]);
  // The only speech is the completion line: the planner's goal, so the player knows the job is done.
  const said = body.calls.filter(c => c.tool === 'say');
  assert.equal(said.length, 1);
  assert.match(said[0].args.text, /^✔ /u);
  assert.deepEqual(inventoryCounts('main: 3 iron_ingot\n3 iron_ingot slots [0]\niron_pickaxe slots [1]\nsummary: building blocks 10'), { iron_ingot: 3, iron_pickaxe: 1 });
  assert.equal(checkDone('pos 0 64 0', { position: [0, 64, 0] }).pass, true);
  assert.throws(() => validatePlan({ ...pickaxePlan, steps: [{ id: 1, do: 'obtain iron_ingot 3', done: 'looks good' }] }));
  const sample = JSON.parse((await readFile(join(f.dir, 'plans.jsonl'), 'utf8')).trim());
  assert.equal(sample.goal_key, 'железная кирка нуля с');
  assert.ok(sample.duration_ms >= 0);
  t.diagnostic(`Fake planner sample: ${JSON.stringify(pickaxePlan)}`);
  const english = await request({ ...f, text: 'craft an iron pickaxe from scratch',
    planner: backend(async () => ({ text: JSON.stringify(pickaxePlan) })) });
  assert.equal(english.body.inventory.iron_pickaxe, 1);
}));

test('planner preamble streams before completion with one parallel scene; absent preamble stays quiet', async () => fixture(async f => {
  for (const streaming of [true, false]) {
    const body = new FakeBody(); let preparing = false, finished = false;
    const call = body.call.bind(body);
    body.call = async (bot, tool, args) => {
      if (tool === 'say') assert.equal(finished, false);
      if (tool === 'scene') { await new Promise(resolve => setImmediate(resolve)); assert.ok(preparing); }
      return call(bot, tool, args);
    };
    const planner = backend(async text => {
      assert.match(text, /Scene observed while preparing this plan: forest/u);
      assert.equal(body.calls.filter(c => c.tool === 'scene').length, 1);
      if (streaming) {
        planner.onText('I will check '); planner.onText('the caves.\n');
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(body.calls.filter(c => c.tool === 'say').length, 1);
      }
      finished = true;
      return { text: JSON.stringify(pickaxePlan) };
    });
    planner.prepare = async () => { preparing = true; await new Promise(resolve => setImmediate(resolve)); preparing = false; };
    await request({ ...f, body, text: streaming ? 'make an iron pickaxe' : 'make iron tools', planner });
    assert.equal(body.calls.filter(c => c.tool === 'say').length, streaming ? 1 : 0);
  }
}));

test('model preambles preserve text, deliver once and suppress cancelled speech', async () => {
  const { Thinking } = await import('../thinking.js');
  const said = [], transport = {};
  const thinking = new Thinking({ say: text => said.push(text) });
  const work = async () => {
    transport.onText('Checking '); transport.onText('the cave.\n'); transport.onText('more text', true);
    return { text: 'Checking the cave.\n{"goal":"iron"}' };
  };
  const result = await thinking.run(work, { backend: transport });
  assert.deepEqual(said, ['Checking the cave.']);
  assert.equal(result.text, '{"goal":"iron"}');
  await thinking.run(work, { backend: transport, isActive: () => false });
  assert.equal(said.length, 1);
  await assert.rejects(thinking.run(() => Promise.reject(new Error('cancelled')), { backend: transport }), /cancelled/u);
  assert.equal(transport.onText, undefined);
});

test('only planner backend args enable live web search, with the same role restriction for Claude', () => {
  assert.deepEqual(parseTool('wiki ME controller'), { tool: 'wiki', args: { query: 'ME controller' } });
  const actor = buildCodexArgs(), planner = buildCodexArgs({ role: 'planner' });
  assert.ok(planner.includes('web_search="live"'));
  assert.ok(actor.includes('web_search="disabled"'));
  assert.ok(!actor.includes('web_search="live"'));
  const claudeActor = buildClaudeArgs(), claudePlanner = buildClaudeArgs({ role: 'planner' });
  assert.equal(claudePlanner[claudePlanner.indexOf('--tools') + 1], 'WebSearch');
  assert.equal(claudePlanner[claudePlanner.indexOf('--allowedTools') + 1], 'mcp__blocknet,WebSearch');
  assert.equal(claudeActor[claudeActor.indexOf('--tools') + 1], '');
  const chat = buildCodexArgs({ role: 'chat', mcpServer: { command: 'unused', args: [] } });
  assert.ok(chat.includes('mcp_servers={}'));
  assert.ok(chat.includes('web_search="disabled"'));
  const claudeChat = buildClaudeArgs({ role: 'chat' });
  assert.equal(claudeChat[claudeChat.indexOf('--allowedTools') + 1], '');
});

test('failed steps replan with reasons, critic rejects unearned success, third failure tells player', async () => fixture(async f => {
  const prompts = [], body = new FakeBody(); body.fail = true;
  const planner = backend(async text => { prompts.push(text); return { text: JSON.stringify(pickaxePlan) }; });
  // The chat model says the final failure in character; tool reports never reach the player raw.
  const chats = [], line = 'Лава в туннеле, не пройти. Дай ведро воды.';
  const chat = backend(async text => { chats.push(text); return { text: line }; });
  const { events } = await request({ ...f, body, planner, chat });
  assert.equal(prompts.length, 3);
  assert.match(prompts[1], /Previous failures:.*lava blocks the tunnel/u);
  assert.equal(events.filter(e => e.type === 'plan_failure').length, 3);
  assert.match(chats.at(-1), /You gave up on the order.*lava blocks the tunnel/u);
  assert.ok(body.calls.some(c => c.tool === 'say' && c.args.text === line), 'the player hears the in-character line');
  assert.ok(!body.calls.some(c => c.tool === 'say' && /Не получилось:/u.test(c.args.text)), 'no raw failure text');
  assert.equal((await new PlanLibrary(join(f.dir, 'plans.jsonl')).matches(goal)).length, 0);
  const recovering = new FakeBody(); recovering.noGain = true; const repairPrompts = [];
  await request({ ...f, body: recovering, planner: backend(async text => { repairPrompts.push(text); return { text: JSON.stringify(pickaxePlan) }; }) });
  assert.equal(repairPrompts.length, 2);
  assert.match(repairPrompts[1], /inv iron_ingot >= 3: 0\/3; no verified completion/u);
  assert.equal(recovering.inventory.iron_pickaxe, 1);
}));

test('a repeated exact or near goal reuses persisted successful plan without calling planner', async () => fixture(async f => {
  let plannerCalls = 0;
  const planner = backend(async () => { plannerCalls++; return { text: JSON.stringify(pickaxePlan) }; });
  await request({ ...f, planner });
  for (const text of [goal, 'пожалуйста сделай железная кирка с нуля']) {
    const { body, events } = await request({ ...f, planner, text });
    assert.equal(body.inventory.iron_pickaxe, 1);
    assert.ok(events.some(e => e.type === 'plan_reused'));
  }
  assert.equal(plannerCalls, 1);
  const library = new PlanLibrary(join(f.dir, 'plans.jsonl'));
  assert.equal((await library.matches(goal)).length, 2);
  assert.ok((await library.matches('железная кирка с нуля 2')).every(m => m.score === 0));
}));

test('tool steps never call actor; free text restarts actor per step with tiny context and plan status', async () => fixture(async f => {
  let actorCalls = 0, resets = 0;
  const body = new FakeBody();
  const actor = backend(async text => {
    actorCalls++;
    assert.match(text, /Goal:.*железная кирка/u); assert.match(text, /State:.*inventory:/u); assert.match(text, /Scene: forest/u);
    assert.match(text, /план: шаг [12]\/2/u); assert.ok(text.length < 2000); // Includes six speaker-labelled conversation lines.
    assert.ok(resets >= actorCalls);
    // Status must bypass the occupied actor queue.
    body.emit('event', { type: 'chat', from: 'Alex', text: 'план?' });
    await body.call('Kirill', 'obtain', { item: actorCalls === 1 ? 'iron_ingot' : 'iron_pickaxe', count: actorCalls === 1 ? 3 : 1 });
    return { text: '<silent>' };
  });
  actor.close = () => { resets++; };
  const direct = await request({ ...f, body, actor, planner: backend(async () => ({ text: JSON.stringify(pickaxePlan) })) });
  assert.equal(actorCalls, 0);
  body.inventory = {};
  // A different goal avoids reuse from the first request.
  const plan = { ...pickaxePlan, steps: [
    { id: 1, do: 'Smelt three iron ingots from the available materials.', done: 'inv iron_ingot >= 3' },
    { id: 2, do: 'Make the requested iron pickaxe.', done: 'inv iron_pickaxe >= 1' },
  ] };
  direct.session.close();
  await request({ ...f, body, actor, text: 'сделай железная кирка из материалов', planner: backend(async () => ({ text: JSON.stringify(plan) })) });
  assert.equal(actorCalls, 2);
  assert.ok(body.calls.some(c => c.tool === 'say' && c.args.text === 'Working on the next step.'));
  assert.deepEqual(parseTool('goto 2 64 -3'), { tool: 'goto', args: { x: 2, y: 64, z: -3 } });
  assert.deepEqual(parseTool('gather cobblestone 3').args, { item: 'cobblestone', count: 3 });
  assert.deepEqual(parseRole('codex:gpt-6-luna:priority'), { backend: 'codex', model: 'gpt-6-luna', serviceTier: 'priority' });
  assert.ok(buildCodexArgs(parseRole('codex:gpt-6.1-sol:medium')).includes('model_reasoning_effort="medium"'));
  assert.ok(buildClaudeArgs(parseRole('claude:sonnet:high')).includes('--effort'));
}));

const oneStep = (goal, item) => ({ goal, budget_min: 2,
  steps: [{ id: 1, do: `obtain ${item} 1`, done: `inv ${item} >= 1` }] });
async function until(predicate) {
  const deadline = Date.now() + 3000;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error('condition timed out');
    await new Promise(resolve => setImmediate(resolve));
  }
}
async function runningPlan(f, { review, classify = () => ({ kind: 'hint', reply: 'I will check that.' }), autoplay = false } = {}) {
  const body = new FakeBody(), prompts = [], chatPrompts = [], events = [];
  const call = body.call.bind(body);
  let finish;
  body.call = async (bot, tool, args) => {
    if (tool === 'obtain' && args.item === 'iron_ingot') {
      body.calls.push({ tool, args });
      const task_id = 'running-iron';
      body.tasks.set(task_id, new Promise(resolve => { finish = result => {
        if (!result) body.inventory.iron_ingot = 1;
        resolve(result ?? 'obtained iron_ingot');
      }; }));
      return { task_id };
    }
    if (tool === 'stop') finish?.('cancelled');
    return call(bot, tool, args);
  };
  const planner = backend(async text => {
    prompts.push(text);
    return { text: JSON.stringify(text.includes('Hint review:') ? review
      : oneStep(text.includes('Goal: get coal') ? 'get coal' : 'find iron', text.includes('Goal: get coal') ? 'coal' : 'iron_ingot')) };
  });
  const session = new BrainSession({ body, backend: backend(async () => ({ text: '<silent>' })),
    chatBackend: backend(async text => { chatPrompts.push(text); return { text: JSON.stringify(classify(text)) }; }),
    plannerBackend: planner, bot: 'Kirill', goal: autoplay ? 'find iron' : '', pauseMs: 1,
    plansFile: join(f.dir, 'hints.jsonl'), log: { write: (type, data) => events.push({ type, ...data }) } });
  f.sessions.push(session); await session.start();
  if (!autoplay) body.emit('event', { type: 'chat', from: 'Alex', text: 'find iron' });
  await until(() => finish);
  return { session, body, prompts, chatPrompts, events, finish: result => finish(result),
    chat: text => body.emit('event', { type: 'chat', from: 'Alex', text }) };
}

test('hint classifier acknowledges once, reviews once, and keep never cancels the body', async () => fixture(async f => {
  const r = await runningPlan(f, { review: { action: 'keep' } });
  r.chat('железо глубже, на y=16');
  assert.equal(r.body.calls.filter(c => c.tool === 'say').length, 0, 'no local phrase before classification');
  await r.session.plans.interactions;
  assert.equal(r.prompts.length, 2, 'one initial plan and exactly one hint review');
  assert.match(r.prompts[1], /железо глубже, на y=16/u);
  assert.match(r.prompts[1], /Current plan:.*obtain iron_ingot/u);
  assert.match(r.prompts[1], /Current step:.*iron_ingot/u);
  assert.match(r.prompts[1], /State:.*pos 0/u);
  assert.match(r.prompts[1], /Scene: forest/u);
  assert.equal(r.body.calls.filter(c => c.tool === 'stop').length, 0);
  assert.equal(r.body.calls.filter(c => c.tool === 'say').length, 1);
  assert.equal(r.session.plans.running, true);
  r.finish(); await until(() => !r.session.busy);
}));

test('a hint cannot replace the original objective with an easier inventory condition', async () => fixture(async f => {
  const r = await runningPlan(f, { review: oneStep('find iron', 'coal') });
  r.chat('сундук с углём слева');
  await r.session.plans.interactions;
  assert.equal(r.session.plans.running, true);
  assert.equal(r.events.filter(e => e.type === 'plan_revision_rejected').length, 1);
  r.finish(); await until(() => !r.session.busy);
  assert.equal(r.prompts.length, 2);
  assert.equal(r.body.calls.filter(c => c.tool === 'stop').length, 0);
  assert.equal(r.body.inventory.coal, undefined);
  assert.equal(r.session.plans.plan.steps[0].do, 'obtain iron_ingot 1');
  assert.equal(r.events.filter(e => e.type === 'plan_failure').length, 0);
}));

test('a blocked hint review stops work and reports the blocker without saving success', async () => fixture(async f => {
  const r = await runningPlan(f, { review: { status: 'blocked', reason: 'Source was removed; select another chest.' } });
  r.chat('the source chest was removed');
  await until(() => !r.session.busy);
  assert.equal(r.body.calls.filter(c => c.tool === 'stop').length, 1);
  assert.ok(r.events.some(e => e.type === 'plan_blocked'));
  assert.equal(r.events.filter(e => e.type === 'plan_step' && e.pass).length, 0);
  assert.equal(r.session.plans.label, 'blocked');
}));

test('revising later steps preserves the running step and remembers hints on failure replan', async () => fixture(async f => {
  const review = oneStep('find iron', 'iron_ingot');
  review.steps.push({ id: 2, do: 'obtain coal 1', done: 'inv coal >= 1' });
  const r = await runningPlan(f, { review });
  r.chat('сундук с углём слева');
  await r.session.plans.interactions;
  assert.equal(r.body.calls.filter(c => c.tool === 'stop').length, 0);
  r.finish('failed: tunnel blocked');
  await until(() => r.prompts.length === 3 && r.body.calls.filter(c => c.tool === 'obtain').length === 2);
  assert.match(r.prompts[2], /Player hints for this plan:.*сундук с углём слева/u);
  r.finish(); await until(() => !r.session.busy);
}));

test('question is answered while working without replanning or local acks', async () => fixture(async f => {
  const r = await runningPlan(f, { classify: () => ({ kind: 'question', reply: 'Сейчас добываю железо.' }) });
  r.chat('что ты делаешь?'); r.chat('и где ты?');
  await r.session.plans.interactions;
  assert.equal(r.prompts.length, 1);
  assert.equal(r.chatPrompts.length, 2);
  assert.ok(r.chatPrompts.every(p => p.includes('Current plan:') && p.includes('Scene: forest')));
  assert.equal(r.body.calls.filter(c => c.tool === 'say').length, 2);
  assert.equal(r.body.calls.filter(c => c.tool === 'say' && c.args.text === 'Сейчас добываю железо.').length, 2);
  assert.equal(r.body.calls.filter(c => c.tool === 'stop').length, 0);
  r.finish(); await until(() => !r.session.busy);
}));

test('new player goal replans immediately, then resumes the unchanged long-term autoplay goal', async () => fixture(async f => {
  const r = await runningPlan(f, { autoplay: true,
    classify: () => ({ kind: 'new_goal', goal: 'get coal', reply: 'Хорошо, сначала добуду уголь.' }) });
  r.chat('вместо этого добудь уголь');
  await until(() => r.body.inventory.coal === 1 && r.body.calls.filter(c => c.tool === 'obtain' && c.args.item === 'iron_ingot').length === 2);
  assert.equal(r.session.goal, 'find iron');
  assert.equal(r.session.ended, false);
  assert.equal(r.body.calls.filter(c => c.tool === 'stop').length, 1);
  assert.equal(r.prompts.length, 3);
  assert.match(r.prompts[1], /Goal: get coal/u);
  assert.match(r.prompts[2], /Goal: find iron/u);
  assert.ok(r.body.calls.some(c => c.tool === 'say' && c.args.text === 'Хорошо, сначала добуду уголь.'));
  r.finish(); await until(() => r.session.ended && !r.session.busy);
}));

test('before player chat an English autoplay goal still uses persona default ru for speech', async () => fixture(async f => {
  const { persona, personaPrompt } = await import('../thinking.js');
  assert.equal(persona.defaultLanguage, 'ru');
  assert.match(personaPrompt, /Russian before they\s+speak/u);
  const r = await runningPlan(f, { autoplay: true });
  assert.match(r.session.languageRule(), /Before any player message, use ru/u);
  assert.equal(r.session.lastPlayerMessage, undefined);
  r.session.cancel();
}));

test('each turn can stream its own preamble without phrase pools or cooldown', async () => {
  const { Thinking, persona } = await import('../thinking.js');
  const said = [], transport = {};
  const thinking = new Thinking({ say: text => said.push(text) });
  for (let i = 0; i < 6; i++) await thinking.run(async () => {
    transport.onText(`Checking cave ${i}.\n`);
    return { text: '<silent>' };
  }, { backend: transport });
  assert.equal(said.length, 6);
  assert.deepEqual(Object.keys(persona), ['defaultLanguage']);
});

test('initial autoplay planning streams model speech before any player message', async () => fixture(async f => {
  const body = new FakeBody(); let finish;
  const planner = backend(() => new Promise(resolve => {
    planner.onText('Let me check the cave.\n');
    finish = () => resolve({ text: JSON.stringify(oneStep('find coal', 'coal')) });
  }));
  const session = new BrainSession({ body, backend: backend(async () => ({ text: '<silent>' })), plannerBackend: planner,
    chatBackend: backend(async () => ({ text: JSON.stringify({ kind: 'question', reply: 'Still here.' }) })),
    bot: 'Kirill', goal: 'find coal', pauseMs: 1, plansFile: join(f.dir, 'default.jsonl') });
  f.sessions.push(session); await session.start();
  await until(() => body.calls.some(c => c.tool === 'say'));
  assert.equal(body.calls.find(c => c.tool === 'say').args.text, 'Let me check the cave.');
  finish(); await until(() => session.ended && !session.busy);
}));

test('debug trace: full model I/O, reasoning and in-turn tools reach the session log and the body overlay', async () => fixture(async f => {
  const body = new FakeBody();
  body.notes = [];
  body.note = (bot, source, text) => body.notes.push({ bot, source, text });
  const longScene = 'x'.repeat(1000);
  const planner = backend(async text => {
    planner.onTrace?.('reasoning', { text: 'need stone tools before iron' });
    planner.onTrace?.('tool', { tool: 'knowledge', arguments: { item: 'iron_ore' }, result: { content: [{ type: 'text', text: 'peak 16' }] } });
    return { text: JSON.stringify({ ...pickaxePlan, goal: `${pickaxePlan.goal} ${longScene}`.slice(0, 900) }) };
  });
  const { events } = await request({ ...f, body, planner });
  const traces = events.filter(e => e.type === 'model_trace');
  const input = traces.find(e => e.model_role === 'planner' && e.kind === 'input');
  assert.ok(input && input.text.length > 300, 'planner input is logged in full, not cut at 300 characters');
  assert.ok(traces.some(e => e.kind === 'output' && e.text.length > 300), 'planner output is logged in full');
  assert.ok(traces.some(e => e.kind === 'reasoning' && e.text === 'need stone tools before iron'));
  assert.ok(traces.some(e => e.kind === 'tool' && e.tool === 'knowledge'));
  assert.ok(body.notes.every(n => n.bot === 'Kirill' && n.text.length <= 600));
  assert.ok(body.notes.some(n => n.source === 'planner' && /thinks: need stone tools/u.test(n.text)));
  assert.ok(body.notes.some(n => n.source === 'planner' && /calls knowledge .*-> peak 16/u.test(n.text)));
  assert.ok(body.notes.some(n => n.source === 'plan' && /obtain stone_pickaxe/u.test(n.text)));
  assert.equal(body.notes.filter(n => n.source === 'step').length, pickaxePlan.steps.length);
  assert.equal(body.notes.filter(n => n.source === 'critic' && /verified/u.test(n.text)).length, pickaxePlan.steps.length);
}));

test('killed done counts attack reports during the plan, and the actor sees only high-level tools', async () => {
  const { actorTools, ACTOR_TOOLS, PlanRunner } = await import('../plans.js');
  assert.deepEqual(parseCondition('killed villager >= 2'), { type: 'killed', creature: 'villager', count: 2 });
  assert.equal(checkDone('killed minecraft:villager >= 1', { inventory: {}, kills: {} }).pass, false);
  const runner = new PlanRunner({ call: async () => ({}), planner: async () => ({}), actor: async () => ({}) });
  runner.recordKill('villager');
  assert.deepEqual(checkDone('killed villager >= 1', runner.state), { pass: true, progress: '1/1 killed' });
  const names = actorTools(toolCatalog).map(t => t.name);
  assert.ok(names.includes('obtain') && names.includes('pickup') && names.includes('map'));
  for (const hidden of ['mine_ore', 'explore_caves', 'descend', 'tunnel', 'scene'])
    assert.ok(!names.includes(hidden), hidden);
  assert.ok(names.includes('give'));
  for (const needed of ['inspect', 'look-in', 'memory', 'transfer', 'withdraw', 'deposit'])
    assert.ok(names.includes(needed), `container workflow needs ${needed}`);
  assert.equal(new Set(ACTOR_TOOLS).size, ACTOR_TOOLS.length, 'no duplicate tools');
  assert.ok(ACTOR_TOOLS.every(name => names.includes(name)), 'all advertised tools exist in the body registry');
});

test('an action step with done "ok" passes on its success report and fails on a failed one', async () => {
  const { PlanRunner } = await import('../plans.js');
  const calls = [];
  const runner = new PlanRunner({ call: async (tool, args) => {
    calls.push(tool);
    if (tool === 'inv') return { result: 'inventory r1: 10 iron_ingot slots [0]' };
    if (tool === 'status') return { result: 'pos 0, 64, 0' };
    if (tool === 'deposit') return { result: args.filter === 'iron_ingot' ? 'deposited 10 iron_ingot into chest at 1 64 0' : 'failed: no room' };
    return { result: 'ok' };
  }, planner: async () => ({}), actor: async () => ({ text: '' }), tools: toolCatalog });
  runner.controller = new AbortController(); runner.isActive = () => true;
  assert.deepEqual(parseCondition('ok'), { type: 'ok' });
  await runner.executeStep({ id: 1, do: 'deposit {"ref":"h1","filter":"iron_ingot"}', done: 'ok' }, 'store iron', '', 5_000, new AbortController());
  assert.ok(calls.includes('deposit'));
  await assert.rejects(runner.executeStep({ id: 2, do: 'deposit {"ref":"h1","filter":"coal"}', done: 'ok' }, 'store coal', '', 5_000, new AbortController()), /no room/u);
});

test('replanning after two failed plans goes to the strong planner when one is configured', async () => {
  const { PlanRunner, ESCALATE_AFTER } = await import('../plans.js');
  const used = [];
  const plan = JSON.stringify({ goal: 'g', budget_min: 5, steps: [{ id: 1, do: 'look', done: 'ok' }] });
  const runner = new PlanRunner({ call: async () => ({ result: 'ok' }), actor: async () => ({ text: '' }), tools: toolCatalog,
    planner: async () => { used.push('normal'); return { text: plan }; },
    strongPlanner: async () => { used.push('strong'); return { text: plan }; } });
  runner.controller = new AbortController(); runner.isActive = () => true; runner.hints = []; runner.stateLine = '';
  runner.library = { matches: async () => [] };
  await runner.makePlan('g', [], '');
  await runner.makePlan('g', ['one'], '');
  await runner.makePlan('g', Array(ESCALATE_AFTER).fill('failed'), '');
  assert.deepEqual(used, ['normal', 'normal', 'strong']);
  const plain = new PlanRunner({ call: async () => ({ result: 'ok' }), actor: async () => ({ text: '' }), tools: toolCatalog,
    planner: async () => { used.push('only'); return { text: plan }; } });
  plain.controller = new AbortController(); plain.isActive = () => true; plain.hints = []; plain.stateLine = '';
  plain.library = { matches: async () => [] };
  await plain.makePlan('g', ['a', 'b'], '');
  assert.equal(used.at(-1), 'only', 'without a strong planner the usual one keeps planning');
});
