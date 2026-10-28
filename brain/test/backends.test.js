import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { createInterface } from 'node:readline';
import { ClaudeBackend } from '../claude-backend.js';
import { CodexBackend, buildCodexArgs } from '../codex-backend.js';
import { sendWithAck, CompactState } from '../brain.js';
import { routeRule } from '../rules.js';

function fakeProcess(receive) {
  const child = new EventEmitter();
  child.stdin = new PassThrough();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  const send = value => {
    const line = `${JSON.stringify(value)}\n`;
    child.stdout.write(line.slice(0, 9));
    child.stdout.write(line.slice(9));
  };
  const lines = createInterface({ input: child.stdin });
  lines.on('line', line => receive(JSON.parse(line), send));
  child.kill = () => { lines.close(); child.emit('close', 0); };
  return child;
}

test('gather rules parse only unambiguous quantities and items', () => {
  assert.deepEqual(routeRule('накопай 10 булыжника', 'Alex'), {
    tool: 'gather', args: { item: 'cobblestone', count: 10 },
  });
  assert.equal(routeRule('добудь 4 земли', 'Alex').args.item, 'dirt');
  assert.equal(routeRule('накопай 0 булыжника', 'Alex'), null);
  assert.equal(routeRule('накопай 10 булыжника и сломай дом', 'Alex'), null);
});

test('Claude fake stream returns text and usage, reuses process and rotates after N turns', async () => {
  let spawns = 0;
  const messages = [];
  const backend = new ClaudeBackend({ maxTurns: 2, spawnProcess(command, args, options) {
    spawns++;
    assert.ok(args.includes('stream-json'));
    assert.equal(options.shell, false);
    return fakeProcess((message, send) => {
      messages.push(message);
      send({ type: 'stream_event', event: { delta: { type: 'text_delta', text: 'Готово.' } } });
      send({ type: 'assistant', message: { content: [{ type: 'tool_use', name: 'gather' }] } });
      send({ type: 'result', result: 'Готово.', usage: { input_tokens: 25, output_tokens: 3 } });
    });
  } });
  try {
    const first = await backend.send('Dig');
    assert.equal(first.text, 'Готово.');
    assert.equal(first.usage.input_tokens, 25);
    assert.equal(first.toolCalls, 1);
    assert.ok(first.ttftMs >= 0);
    await backend.send('Again');
    assert.equal(spawns, 1);
    await backend.send('New session');
    assert.equal(spawns, 2);
    assert.deepEqual(messages[0], { type: 'user', message: { role: 'user', content: 'Dig' } });
  } finally { backend.close(); }
});

test('Codex fake JSON-RPC initializes one thread, starts turns, approves MCP and returns turn usage', async () => {
  let spawns = 0;
  let turns = 0;
  const requests = [];
  const backend = new CodexBackend({ prompt: 'Be a buddy.', cwd: 'C:/game', spawnProcess(command, args) {
    spawns++;
    assert.equal(args[0], 'app-server');
    return fakeProcess((message, send) => {
      requests.push(message);
      if (message.method === 'initialize') send({ id: message.id, result: {} });
      if (message.method === 'thread/start') send({ id: message.id, result: { thread: { id: 'thread-1' } } });
      if (message.method === 'turn/start') {
        turns++;
        send({ id: message.id, result: { turn: { id: `turn-${turns}` } } });
        send({ method: 'item/agentMessage/delta', params: { threadId: 'thread-1', delta: 'Done.' } });
        send({ id: 'approval', method: 'mcpServer/elicitation/request', params: { serverName: 'blocknet' } });
        send({ method: 'thread/tokenUsage/updated', params: { tokenUsage: {
          total: { inputTokens: turns * 20, outputTokens: turns * 4 }, last: { inputTokens: 5 } } } });
        send({ method: 'item/completed', params: { item: { type: 'mcpToolCall', id: 'tool-1' } } });
        send({ method: 'item/completed', params: { item: { type: 'agentMessage', text: 'Done.', phase: 'final_answer' } } });
        send({ method: 'turn/completed', params: { turn: { status: 'completed' } } });
      }
    });
  } });
  try {
    for (let i = 0; i < 2; i++) {
      const result = await backend.send('Dig');
      assert.equal(result.text, 'Done.');
      assert.deepEqual(result.usage, { inputTokens: 20, outputTokens: 4 });
      assert.equal(result.toolCalls, 1);
      assert.ok(result.ttftMs >= 0);
    }
    assert.equal(spawns, 1);
    const start = requests.find(r => r.method === 'thread/start').params;
    assert.equal(start.baseInstructions, 'Be a buddy.');
    assert.equal(start.approvalPolicy, 'never');
    assert.equal(start.sandbox, 'read-only');
    assert.equal(start.model, 'gpt-6-luna');
    assert.equal(start.serviceTier, 'priority');
    assert.equal(requests.find(r => r.id === 'approval' && r.result).result.content.decision, 'approve');
  } finally { backend.close(); }
  const args = buildCodexArgs({ mcpServer: { command: 'node', args: ['mcp-server.js'], env: { BLOCKNET_BOT: 'Kirill' } } });
  assert.ok(args.includes('features.skip_host_skill_discovery=true'));
  assert.ok(args.includes('features.plugins=false'));
  assert.ok(args.find(a => a.startsWith('mcp_servers=')).includes('default_tools_approval_mode="approve"'));
});

for (const Backend of [ClaudeBackend, CodexBackend]) {
  test(`${Backend.name} rejects a dead child and starts a fresh process on the next send`, async () => {
    let child;
    let spawns = 0;
    const backend = new Backend({ spawnProcess() { spawns++; return child = fakeProcess(() => {}); } });
    const first = backend.send('one');
    child.emit('close', 1);
    await assert.rejects(first, /exited/);
    const second = backend.send('two');
    assert.equal(spawns, 2);
    backend.close();
    await assert.rejects(second, /cancelled|closed/);
  });
}

test('preamble streams from a fake child before the final result; absent text stays quiet', async () => {
  for (const streaming of [false, true]) {
    const said = [];
    const backend = new ClaudeBackend({ spawnProcess: () => fakeProcess((message, send) => {
      if (streaming) send({ type: 'stream_event', event: { delta: { type: 'text_delta', text: 'Checking the cave.\n' } } });
      setTimeout(() => { assert.equal(said.length, streaming ? 1 : 0); send({ type: 'result', result: 'Done.', usage: {} }); }, 10);
    }) });
    try {
      assert.equal((await sendWithAck(backend, 'Dig', text => said.push(text))).text, 'Done.');
      assert.deepEqual(said, streaming ? ['Checking the cave.'] : []);
    } finally { backend.close(); }
  }
});

test('compact state emits inventory/held and low health only when meaningful state changes', () => {
  const state = new CompactState();
  const first = state.update('body r1: health 20/20, food 20, pos 1, 2, 3\ninventory r1: selected 0\nmain: wooden_pickaxe\nwooden_pickaxe slots [0]\n3 cobblestone slots [1]\nsummary: free slots 34\ntask r1: idle');
  assert.equal(first, '\nState: inventory: 1 wooden_pickaxe, 3 cobblestone; held: wooden_pickaxe');
  assert.equal(state.update('body r2: health 19/20, pos 2, 2, 3'), '');
  assert.match(state.update('body r3: health 3/20'), /health low/);
  assert.equal(state.update('no changes'), '');
  state.reset();
  assert.match(state.update('no changes'), /inventory:/);
});

test('compact state preserves mining depth on inventory-only deltas and clears the ore hint when idle', () => {
  const state = new CompactState();
  assert.match(state.update('body r1: health 20/20, pos 2, 72, 3, y=72, surface y=72, iron best ~16, 56 below; task r1: mining'), /y=72, surface y=72, iron best ~16, 56 below/);
  assert.match(state.update('inventory r2: selected 0; main: stone_pickaxe; 3 cobblestone slots [1]'), /iron best ~16, 56 below/);
  assert.match(state.update('body r2: health 20/20, pos 2, 16, 3, y=16, surface y=72; task r2: idle'), /y=16, surface y=72/);
  assert.doesNotMatch(state.previous, /iron best/);
  assert.match(state.update('- body r2: health 20/20, y=16, surface y=72; + body r3: health 20/20, y=12, surface y=72, iron best ~-16, 28 below'), /y=12, surface y=72, iron best ~-16, 28 below/);
});
