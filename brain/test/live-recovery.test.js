import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { setTimeout as sleep } from 'node:timers/promises';
import { BrainSession } from '../brain.js';
import { routeRule } from '../rules.js';
import { PlanRunner, PlanLibrary, toolCatalog, validatePlan } from '../plans.js';

async function until(check, ms = 1000) {
  const deadline = Date.now() + ms;
  while (!check()) { assert.ok(Date.now() < deadline, 'condition timed out'); await sleep(5); }
}
class Body extends EventEmitter {
  hello = { bots: ['Kirill'], tools: [] };
  calls = [];
  async ready() { return this.hello; }
  async call(bot, tool, args = {}) {
    this.calls.push({ tool, args, at: Date.now() });
    if (tool === 'stop') { clearTimeout(this.obtainTimer); this.finish?.('failed: cancelled'); }
    if (tool === 'obtain') {
      this.pending = new Promise(resolve => {
        this.finish = resolve; this.obtainTimer = setTimeout(() => resolve('obtained coal'), 5000);
      });
      return { task_id: 'coal' };
    }
    return { result: tool === 'changes' ? 'inventory r1: 32 coal slots [0]' : 'ok' };
  }
  waitTask() { return this.pending; }
  close() { clearTimeout(this.obtainTimer); }
  chat(text) { this.emit('event', { type: 'chat', from: 'Alex', text }); }
}
const backend = send => ({ send, close() {}, backend: 'fake', model: 'fake' });

test('stop phrases bypass both actor and classifier, preserving a following order', async () => {
  for (const text of ['стоп', 'бля, СТОП!!!', 'хватит!', 'не надо, блять', 'отмена.', 'остановись нахуй', 'stop!!!', 'fuck, cancel!']) {
    assert.equal(routeRule(text, 'Alex')?.tool, 'stop', text);
    const body = new Body(); let models = 0;
    const session = new BrainSession({ body, bot: 'Kirill', backend: backend(async () => { models++; return { text: '' }; }),
      chatBackend: backend(async () => { models++; return { text: '' }; }) });
    try { await session.start(); body.chat(text); await until(() => body.calls.some(c => c.tool === 'stop')); assert.equal(models, 0, text); }
    finally { session.close(); }
  }
  assert.equal(routeRule('хватит копать, принеси уголь', 'Alex').nextOrder, 'принеси уголь');
});

for (const text of ['хватит копать, принеси уголь', 'you already have it, bring it']) {
  test(`a player replaces a five-second obtain within one second: ${text}`, async () => {
    const body = new Body(), prompts = []; let session;
    const actor = backend(async prompt => {
      prompts.push(prompt);
      if (prompts.length === 1) { const r = await session.call('obtain', { item: 'coal', count: 64 }); await r.outcome; return { text: 'stale mining reply' }; }
      await session.call('give', { player: 'Alex', item: 'coal' });
      return { text: 'Держи.' };
    });
    session = new BrainSession({ body, bot: 'Kirill', backend: actor,
      chatBackend: backend(async () => ({ text: '{"kind":"new_order","reply":"Несу."}' })) });
    try {
      await session.start(); body.chat('mine coal'); await until(() => body.calls.some(c => c.tool === 'obtain'));
      const start = Date.now(); body.chat(text);
      await until(() => body.calls.some(c => c.tool === 'give'));
      assert.ok(body.calls.find(c => c.tool === 'stop').at - start < 1000);
      assert.equal(body.calls.filter(c => c.tool === 'obtain').length, 1);
      await until(() => !session.busy);
      assert.ok(!body.calls.some(c => c.tool === 'say' && /stale/u.test(c.args.text)));
    } finally { session.close(); }
  });
}

test('questions during an actor call get dialogue context and do not stop the body', async () => {
  const body = new Body(); let finish, classified;
  const session = new BrainSession({ body, bot: 'Kirill', backend: backend(() => new Promise(resolve => { finish = resolve; })),
    chatBackend: backend(async prompt => { classified = prompt; return { text: '{"kind":"question","reply":"Уже есть уголь."}' }; }) });
  try {
    await session.start(); body.chat('mine coal'); await until(() => finish);
    await session.say('Что именно принести?', 'Alex'); body.chat('у тебя есть уголь?');
    await until(() => body.calls.some(c => c.args.text === 'Уже есть уголь.'));
    assert.match(classified, /bot Kirill.*Что именно принести/u);
    assert.equal(body.calls.some(c => c.tool === 'stop'), false);
    finish({ text: '' }); await until(() => !session.busy);
  } finally { session.close(); }
});

test('two rapid corrections keep the newest order instead of discarding it after cancellation', async () => {
  const body = new Body(), prompts = []; let session, classify;
  const actor = backend(async prompt => {
    prompts.push(prompt);
    if (prompts.length === 1) { const r = await session.call('obtain', { item: 'coal', count: 64 }); await r.outcome; }
    return { text: '' };
  });
  let classifications = 0;
  const chat = backend(async () => {
    if (++classifications === 1) await new Promise(resolve => { classify = resolve; });
    return { text: '{"kind":"new_order","reply":""}' };
  });
  session = new BrainSession({ body, bot: 'Kirill', backend: actor, chatBackend: chat });
  try {
    await session.start(); body.chat('mine coal'); await until(() => body.calls.some(c => c.tool === 'obtain'));
    body.chat('bring it here'); await until(() => classify);
    body.chat('actually give all the coal to Alex'); classify();
    await until(() => !session.busy && prompts.length === 2);
    assert.match(prompts[1], /Alex: actually give all the coal to Alex/u);
    assert.equal(body.calls.filter(c => c.tool === 'stop').length, 1);
  } finally { session.close(); }
});

test('each actor request includes player answers and bot/planner questions even after session rotation', async () => {
  const body = new Body(), prompts = [];
  const session = new BrainSession({ body, bot: 'Kirill', backend: backend(async prompt => {
    prompts.push(prompt); return { text: prompts.length === 1 ? 'Что именно принести?\nУточни.' : 'Держи.' };
  }) });
  try {
    await session.start(); body.chat('принеси его сюда'); await until(() => !session.busy);
    session.sessionTurns = 10; body.chat('уголь'); await until(() => !session.busy);
    assert.match(prompts[1], /player Alex.*принеси его сюда/u);
    assert.match(prompts[1], /bot Kirill.*Что именно принести/u);
    assert.match(prompts[1], /player Alex.*уголь/u);
  } finally { session.close(); }
});

test('an idle actor is aborted, reports its request as a bug, and can accept another turn', async () => {
  const body = new Body(), entries = []; let calls = 0, closes = 0;
  const actor = backend(async () => { if (++calls === 1) return new Promise(() => {}); return { text: 'Держи.' }; });
  actor.close = () => { closes++; };
  const session = new BrainSession({ body, bot: 'Kirill', backend: actor, actorIdleMs: 30,
    log: { write: (type, data) => entries.push({ type, ...data }) } });
  try {
    await session.start(); body.chat('кинь мне весь уголь'); await until(() => !session.busy);
    assert.ok(closes > 0);
    assert.ok(entries.some(e => e.type === 'tool_feedback' && e.kind === 'bug' && e.request === 'кинь мне весь уголь'));
    assert.equal(body.calls.filter(c => c.tool === 'say').length, 1);
    body.chat('уголь'); await until(() => !session.busy); assert.equal(calls, 2);
  } finally { session.close(); }
});

test('a preamble does not disable the idle cap, but starting a tool allows a long body wait', async () => {
  for (const tool of [false, true]) {
    const body = new Body(), entries = [];
    const actor = backend(async () => {
      actor.onText?.('Сейчас принесу.\n');
      if (tool) actor.onTrace('tool', { tool: 'give', status: 'started' });
      await sleep(100);
      return { text: 'Сейчас принесу.\nДержи.' };
    });
    const session = new BrainSession({ body, bot: 'Kirill', backend: actor, actorIdleMs: 30,
      log: { write: (type, data) => entries.push({ type, ...data }) } });
    try {
      await session.start(); body.chat('принеси уголь'); await until(() => !session.busy);
      assert.equal(entries.some(e => e.type === 'tool_feedback' && e.kind === 'bug'), !tool);
      assert.ok(!body.calls.some(c => c.args.text === 'Держи.') || tool, 'stale final text is suppressed');
    } finally { session.close(); }
  }
});

const plan = done => ({ goal: 'bring coal', budget_min: 1, steps: [{ id: 1, do: 'give Alex coal', done }] });

class PlanningBody extends Body {
  hello = { bots: ['Kirill'], tools: toolCatalog };
  async call(bot, tool, args) {
    const response = await super.call(bot, tool, args);
    if (tool === 'inv') return { result: '32 coal slots [0]' };
    if (tool === 'status') return { result: 'dimension overworld, pos 0, 64, 0\nplayers: Alex pos 15, 67, 0 dimension minecraft:overworld' };
    if (tool === 'give') return { result: 'gave 32 coal to Alex' };
    return response;
  }
}

for (const retry of [false, true]) {
  for (const kind of ['question', 'new_goal']) {
    test(`${kind} is handled within one second while ${retry ? 'retrying invalid' : 'initially preparing'} planner output`, async () => {
      const body = new PlanningBody(), prompts = [];
      let rejectPlanner, waiting = false;
      const planner = backend(async prompt => {
        prompts.push(prompt);
        if (retry && prompts.length === 1) return { text: '{"steps":[]}' };
        if (prompts.length === (retry ? 2 : 1)) {
          waiting = true;
          return new Promise((resolve, reject) => { rejectPlanner = reject; });
        }
        return { text: JSON.stringify(plan('pos 15 67 0 within 1')) };
      });
      planner.close = () => { rejectPlanner?.(new Error('Planner cancelled')); rejectPlanner = null; };
      let classified;
      const session = new BrainSession({ body, bot: 'Kirill', plannerBackend: planner,
        backend: backend(async () => ({ text: '' })), chatBackend: backend(async prompt => {
          classified = prompt;
          return { text: JSON.stringify({ kind, reply: kind === 'question' ? 'Составляю план.' : '', goal: 'bring coal' }) };
        }) });
      session.plans.library = new PlanLibrary();
      try {
        await session.start(); body.chat('find coal'); await until(() => waiting);
        const started = Date.now(); body.chat(kind === 'question' ? 'почему ты так долго думаешь?' : 'принеси уголь вместо этого');
        if (kind === 'question') {
          await until(() => body.calls.some(c => c.tool === 'say' && c.args.text === 'Составляю план.'));
          assert.equal(body.calls.some(c => c.tool === 'stop'), false);
          assert.equal(prompts.length, retry ? 2 : 1);
        } else {
          await until(() => body.calls.some(c => c.tool === 'give'));
          assert.equal(body.calls.filter(c => c.tool === 'stop').length, 1);
          await until(() => !session.busy);
        }
        assert.ok(Date.now() - started < 1000);
        assert.match(classified, /Goal: find coal/u);
        assert.match(classified, /Current plan: null/u);
      } finally { session.close(); await until(() => !session.busy); }
    });
  }
}

test('a hint waiting for initial planning does not block a later question or new order', async () => {
  const body = new PlanningBody(); let rejectPlanner, waiting = false, calls = 0;
  const planner = backend(async () => {
    if (++calls > 1) return { text: JSON.stringify(plan('pos 15 67 0 within 1')) };
    waiting = true;
    return new Promise((resolve, reject) => { rejectPlanner = reject; });
  });
  planner.close = () => { rejectPlanner?.(new Error('Planner cancelled')); rejectPlanner = null; };
  const session = new BrainSession({ body, bot: 'Kirill', plannerBackend: planner,
    backend: backend(async () => ({ text: '' })), chatBackend: backend(async prompt => ({ text: JSON.stringify(
      prompt.includes('message: "вместо этого принеси уголь"') ? { kind: 'new_goal', goal: 'bring coal' }
        : prompt.includes('message: "почему долго?"') ? { kind: 'question', reply: 'Ещё составляю план.' }
          : { kind: 'hint', reply: 'Уголь уже в инвентаре.' }) })) });
  session.plans.library = new PlanLibrary();
  try {
    await session.start(); body.chat('find coal'); await until(() => waiting);
    body.chat('уголь уже есть'); await until(() => session.plans.hints.length === 1);
    body.chat('почему долго?'); await until(() => body.calls.some(c => c.args.text === 'Ещё составляю план.'));
    body.chat('вместо этого принеси уголь'); await until(() => body.calls.some(c => c.tool === 'give'));
    await until(() => !session.busy);
    assert.equal(body.calls.filter(c => c.tool === 'stop').length, 1);
  } finally { session.close(); await until(() => !session.busy); }
});

test('an outdated body without give is reported before a model is asked to deliver items', async () => {
  const body = new PlanningBody(), entries = [];
  body.hello.tools = toolCatalog.filter(t => t.name !== 'give');
  const session = new BrainSession({ body, bot: 'Kirill',
    backend: backend(async () => { throw new Error('No model needed to detect an old jar'); }),
    log: { write: (type, data) => entries.push({ type, ...data }) } });
  try {
    await session.start(); body.emit('ready', body.hello);
    await until(() => body.calls.some(c => c.tool === 'say'));
    assert.equal(body.calls.filter(c => c.tool === 'say').length, 1);
    assert.match(body.calls.find(c => c.tool === 'say').args.text, /старый мод/u);
    assert.ok(entries.some(e => e.type === 'body_capabilities' && e.missing_actor_tools.includes('give')));
  } finally { session.close(); }
});

test('planner retries its own starting position and receives exact online player positions', async () => {
  const prompts = [];
  const runner = new PlanRunner({ tools: toolCatalog, say: async () => {}, call: async tool => ({ result: tool === 'inv'
    ? '32 coal slots [0]' : 'dimension overworld, pos 0, 64, 0\nplayers: Alex pos 15, 67, 0 dimension minecraft:overworld' }),
    planner: async prompt => { prompts.push(prompt); return { text: JSON.stringify(plan(prompts.length === 1 ? 'pos 0 64 0' : 'pos 15 67 0 within 1')) }; } });
  runner.controller = new AbortController(); runner.hints = [];
  await runner.snapshot(); await runner.makePlan('bring coal', [], '');
  assert.equal(prompts.length, 2);
  assert.match(prompts[1], /Invalid output:.*own position/u);
  assert.match(prompts[0], /State:.*Alex pos 15, 67, 0 dimension minecraft:overworld/u);
  assert.throws(() => validatePlan(plan('pos 0 64 0'), toolCatalog, [0, 64, 0]), /own position/u);
});

test('direct give success completes player arrival, but failures and inventory/kill checks stay strict', async () => {
  for (const [done, report, passes] of [['pos 15 67 0 within 1', 'gave 32 coal to Alex', true],
    ['pos 15 67 0 within 1', 'failed: blocked', false], ['inv diamond >= 1', 'gave 32 coal to Alex', false],
    ['killed sheep >= 1', 'gave 32 coal to Alex', false]]) {
    const runner = new PlanRunner({ tools: toolCatalog, call: async tool => ({ result: tool === 'give' ? report : tool === 'inv'
      ? '32 coal slots [0]' : 'dimension minecraft:overworld, pos 14, 64, 0\nplayers: Alex pos 15, 67, 0 dimension minecraft:overworld' }) });
    runner.controller = new AbortController(); await runner.snapshot();
    const work = runner.executeStep(plan(done).steps[0], 'bring coal', '', 1000, new AbortController());
    if (passes) await work; else await assert.rejects(work);
  }
});
