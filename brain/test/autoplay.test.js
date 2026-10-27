import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import { PassThrough } from 'node:stream';
import { createInterface } from 'node:readline';
import net from 'node:net';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Body } from '../body.js';
import { BrainSession } from '../brain.js';
import { ChatRouter } from '../rules.js';
import { serveMcp } from '../mcp-server.js';
import { SessionLog, readSession, perception } from '../session-log.js';
import { summarize, mergeDamageMoments } from '../report.js';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, timeout = 3000) {
  const start = Date.now();
  while (!predicate()) { if (Date.now()-start > timeout) throw new Error('condition timed out'); await sleep(5); }
}
class FakeBody extends EventEmitter {
  hello = { bots: ['Kirill'], tools: [] };
  calls = [];
  async ready() { return this.hello; }
  async call(bot, tool, args) {
    this.calls.push({ tool, args });
    return { result: tool === 'changes' ? 'body r1: health 20/20, food 14; inventory r1: empty' : 'ok' };
  }
  close() {}
}
function fakeBackend(send = async () => ({ text: '<silent>', usage: { inputTokens: 10, outputTokens: 2 } })) {
  return { send, close() {}, backend: 'fake', model: 'fake', maxTurns: 30 };
}
function mcpClient(body, options) {
  const input = new PassThrough(), output = new PassThrough();
  const lines = createInterface({ input: output });
  const waiting = new Map(); let id = 0;
  lines.on('line', line => { const r = JSON.parse(line); waiting.get(r.id)?.(r); waiting.delete(r.id); });
  serveMcp(body, 'Kirill', input, output, options);
  return { request(method, params) { return new Promise(resolve => { waiting.set(++id, resolve); input.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`); }); },
    close() { input.end(); output.end(); lines.close(); } };
}

test('autoplay with fake backend/body respects max turns and records brain-only tool feedback', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'blocknet-auto-'));
  const body = new FakeBody(), log = new SessionLog(dir, 'Kirill', 'survive');
  const client = mcpClient(body, { feedbackFile: join(dir, 'tool-feedback.jsonl'), sessionFile: log.file, sessionId: log.id });
  await client.request('initialize', {});
  let turns = 0, reflections = 0;
  const backend = fakeBackend(async text => {
    if (text.includes('Give 3-5')) { reflections++; return { text: '1. Look first. 2. Eat. 3. Need combat.', usage: {} }; }
    turns++;
    if (turns === 1) assert.equal((await client.request('tools/call', { name: 'tool_feedback', arguments: { kind: 'missing', tool: 'attack', note: 'I need to attack the zombie.' } })).result.isError, false);
    return { text: '<silent>', usage: { inputTokens: 10, outputTokens: 2 } };
  });
  const session = new BrainSession({ body, backend, bot: 'Kirill', goal: 'survive', maxTurns: 3, pauseMs: 5, log, lessonsFile: join(dir, 'lessons.md') });
  try {
    await session.start();
    await until(() => session.ended && !session.busy);
    assert.equal(turns, 3); assert.equal(reflections, 1);
    await sleep(30); assert.equal(turns, 3);
    assert.equal(body.calls.some(c => c.tool === 'tool_feedback'), false);
    const feedback = JSON.parse((await readFile(join(dir, 'tool-feedback.jsonl'), 'utf8')).trim());
    assert.equal(feedback.kind, 'missing'); assert.equal(feedback.session, log.id);
    assert.ok(readSession(log.file).some(e => e.type === 'tool_feedback'));
    assert.match(await readFile(join(dir, 'lessons.md'), 'utf8'), /Look first/);
  } finally { session.close(); client.close(); await rm(dir, { recursive: true, force: true }); }
});

test('bare stop pauses autoplay immediately and continue resumes', async () => {
  const body = new FakeBody(); let turns = 0;
  const session = new BrainSession({ body, backend: fakeBackend(async () => { turns++; return { text: '<silent>' }; }), bot: 'Kirill', goal: 'survive', maxTurns: 4, pauseMs: 25 });
  try {
    await session.start(); await until(() => turns === 1);
    body.emit('event', { type: 'chat', from: 'Alex', text: 'стоп' });
    assert.equal(session.paused, true);
    await until(() => body.calls.some(c => c.tool === 'stop'));
    await sleep(80); assert.equal(turns, 1);
    body.emit('event', { type: 'chat', from: 'Alex', text: 'continue' });
    await until(() => session.ended && !session.busy);
    assert.equal(session.turns, 4);
  } finally { session.close(); }
});

test('bare chat reaches the model only in all mode and silent sends no chat', async () => {
  for (const listen of ['all', 'name']) {
    const body = new FakeBody(), prompts = [];
    const session = new BrainSession({ body, backend: fakeBackend(async text => { prompts.push(text); return { text: '<silent>' }; }), bot: 'Kirill', listen });
    try {
      await session.start(); body.emit('event', { type: 'chat', from: 'Alex', text: 'hello there' });
      await until(() => !session.busy);
      assert.equal(prompts.length, listen === 'all' ? 1 : 0);
      assert.equal(body.calls.filter(c => c.tool === 'say').length, 0);
    } finally { session.close(); }
  }
  const router = new ChatRouter('Kirill');
  assert.equal(router.route('hello', 'Alex', ['Kirill', 'Boris']), null);
  router.spoke('Kirill', 'Alex');
  assert.equal(router.route('hello', 'Alex', ['Kirill', 'Boris']), 'hello');
  assert.equal(router.route('Boris, hello', 'Alex', ['Kirill', 'Boris']), null);
});

test('fake body closes, refuses connections and comes back without crashing brain or MCP', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'blocknet-reconnect-'));
  let sockets = new Set();
  const createServer = () => net.createServer(socket => {
    sockets.add(socket); socket.on('close', () => sockets.delete(socket));
    const lines = createInterface({ input: socket });
    lines.on('error', () => {});
    lines.on('line', line => {
      const request = JSON.parse(line);
      const result = request.method === 'hello' ? { protocol: 1, bots: ['Kirill'], tools: [] } : { result: 'no changes' };
      socket.write(`${JSON.stringify({ jsonrpc: '2.0', id: request.id, result })}\n`);
    });
  });
  let server = createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const port = server.address().port;
  const config = join(dir, 'config.json'); await writeFile(config, JSON.stringify({ port, token: 'test' }));
  const body = new Body(config); const diagnostics = []; body.on('diagnostic', text => diagnostics.push(text));
  const session = new BrainSession({ body, backend: fakeBackend(), bot: 'Kirill' });
  const client = mcpClient(body, {});
  try {
    await session.start(); await client.request('initialize', {});
    const disconnected = once(body, 'disconnected');
    for (const s of sockets) s.destroy();
    await new Promise(resolve => server.close(resolve)); await disconnected;
    await sleep(1300);
    assert.ok(diagnostics.some(text => /ECONNREFUSED/u.test(text)), diagnostics.join('; '));
    server = createServer(); server.listen(port, '127.0.0.1'); await once(server, 'listening');
    await body.ready(5000);
    const response = await client.request('tools/call', { name: 'status' });
    assert.equal(response.result.isError, false);
    assert.equal(response.result.content[0].text, 'no changes');
  } finally {
    session.close(); client.close(); for (const s of sockets) s.destroy();
    await new Promise(resolve => server.close(resolve)); await rm(dir, { recursive: true, force: true });
  }
});

test('report timeline includes merged damage and death with preceding actions and snapshots', () => {
  const snapshot = { pos: '-218 57 -30', task: 'gathering cobblestone', time: 14000, light: 3, food: 14,
    held: 'pickaxe', entities: [], tools: [], unseen: ['zombie behind wall'] };
  const entries = [
    { time: '2026-10-27T12:40:59Z', type: 'session', goal: 'survive', bot: 'Kirill' },
    { time: '2026-10-27T12:41:00Z', type: 'tool_call', id: '1', tool: 'gather', args: { item: 'cobblestone', count: 10 } },
    { time: '2026-10-27T12:41:01Z', type: 'event', event: { type: 'moment', bot: 'Kirill', kind: 'damage', source: 'mob', attacker: 'zombie', attacker_distance: 2, amount: 3, health_before: 20, health_after: 17, snapshot } },
    { time: '2026-10-27T12:41:02Z', type: 'event', event: { type: 'moment', bot: 'Kirill', kind: 'damage', source: 'mob', attacker: 'zombie', amount: 4, health_before: 17, health_after: 13, snapshot } },
    { time: '2026-10-27T12:41:03Z', type: 'tool_result', id: '1', tool: 'gather', failed: true, result: 'failed: zombie' },
    { time: '2026-10-27T12:41:04Z', type: 'event', event: { type: 'moment', kind: 'death', note: 'zombie', snapshot } },
  ];
  const merged = mergeDamageMoments(entries).filter(e => e.event?.kind === 'damage');
  assert.equal(merged.length, 1); assert.equal(merged[0].event.amount, 7);
  assert.equal(merged[0].event.health_before, 20); assert.equal(merged[0].event.health_after, 13);
  const report = summarize(entries);
  assert.match(report, /took 7 damage from zombie \(2 m\).*gathering cobblestone.*-218 57 -30, night, light 3/u);
  assert.match(report, /health 20 -> 13/u); assert.match(report, /Death[s]?: preceding 30 seconds/u);
  assert.match(report, /\| gather \| 1 \| 1 \|/u); assert.match(report, /unseen:/u);
  assert.equal(JSON.stringify(perception(entries)).includes('behind wall'), false);
});
