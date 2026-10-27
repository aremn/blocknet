import { test } from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { createInterface } from 'node:readline';
import { addressed, routeRule } from '../rules.js';
import { buildClaudeArgs } from '../brain.js';
import { PROTOCOL_VERSION, serveMcp } from '../mcp-server.js';

test('rules route Russian and English short orders without the model', () => {
  for (const [tool, orders] of Object.entries({ stop: ['stop', 'СТОП!'],
    come: ['come', 'come here', 'come to me', 'ко мне', 'сюда'],
    follow: ['follow', 'follow me', 'за мной', 'следуй', 'следуй за мной'],
    look: ['look', 'look around', 'осмотрись'] })) {
    for (const order of orders) {
      const route = routeRule(order, 'Alex');
      assert.equal(route.tool, tool, order);
      assert.deepEqual(route.args, ['come', 'follow'].includes(tool) ? { player: 'Alex' } : {});
      assert.ok(route.ack);
    }
  }
  assert.equal(addressed('kIrIlL, ко мне', 'Kirill'), 'ко мне');
  assert.equal(addressed('Кир, осмотрись', 'Kirill', ['Кир']), 'осмотрись');
  assert.equal(addressed('Kirillson stop', 'Kirill'), null);
  assert.equal(addressed('hello Kirill', 'Kirill'), null);
  assert.equal(routeRule('stop and mine coal', 'Alex'), null);
});

test('Claude arguments disable built-ins, restrict MCP and stream a persistent Haiku session', () => {
  const args = buildClaudeArgs();
  const value = name => args[args.indexOf(name) + 1];
  assert.equal(value('--model'), 'haiku');
  assert.equal(value('--tools'), '');
  assert.equal(value('--allowedTools'), 'mcp__blocknet');
  assert.equal(value('--permission-mode'), 'dontAsk');
  assert.equal(value('--mcp-config'), 'brain/mcp.json');
  assert.equal(value('--system-prompt-file'), 'brain/prompt.md');
  assert.equal(value('--input-format'), 'stream-json');
  assert.equal(value('--output-format'), 'stream-json');
  assert.ok(args.includes('--verbose'));
  assert.ok(args.includes('--strict-mcp-config'));
  assert.ok(!args.includes('--bare'));
  assert.ok(!args.includes('--resume'));
});

test('MCP JSON-lines initialize and tools/list use the body registry; calls await outcome', async () => {
  const input = new PassThrough();
  const output = new PassThrough();
  const replies = createInterface({ input: output })[Symbol.asyncIterator]();
  let closed = false;
  let finish;
  const body = {
    ready: async () => ({ tools: [{ name: 'goto', description: 'Walk', params: { type: 'object' } }] }),
    action: async (bot, name, args) => {
      assert.equal(bot, 'Kirill');
      assert.equal(name, 'goto');
      assert.deepEqual(args, { x: 1, y: 2, z: 3 });
      return new Promise(resolve => { finish = resolve; });
    },
    close: () => { closed = true; },
  };
  serveMcp(body, 'Kirill', input, output);
  const send = message => input.write(`${JSON.stringify({ jsonrpc: '2.0', ...message })}\n`);
  const next = async () => JSON.parse((await replies.next()).value);
  // Fragmented input and several messages in one chunk still have one response per line.
  const hello = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: PROTOCOL_VERSION } });
  input.write(hello.slice(0, 15));
  input.write(`${hello.slice(15)}\n${JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' })}\n`);
  assert.equal((await next()).result.protocolVersion, PROTOCOL_VERSION);
  send({ id: 2, method: 'tools/list' });
  assert.deepEqual((await next()).result.tools, [{ name: 'goto', description: 'Walk', inputSchema: { type: 'object' } }]);
  send({ id: 3, method: 'tools/call', params: { name: 'goto', arguments: { x: 1, y: 2, z: 3 } } });
  await new Promise(resolve => setImmediate(resolve));
  send({ id: 4, method: 'ping' });
  assert.equal((await next()).id, 4, 'task response is held until completion');
  finish('arrived');
  assert.deepEqual((await next()).result.content, [{ type: 'text', text: 'arrived' }]);
  send({ id: 5, method: 'initialize', params: { protocolVersion: '2025-11-25' } });
  assert.equal((await next()).result.protocolVersion, '2025-11-25');
  input.end();
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(closed);
  output.end();
});
