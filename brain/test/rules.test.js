import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addressed, routeRule } from '../rules.js';
import { buildClaudeArgs } from '../claude-backend.js';

test('rules route Russian and English short orders without the model', () => {
  for (const [tool, orders] of Object.entries({ stop: ['stop', 'СТОП!'],
    come: ['come', 'come here', 'come to me', 'ко мне', 'сюда'],
    follow: ['follow', 'follow me', 'за мной', 'следуй', 'следуй за мной'],
    look: ['look', 'look around', 'осмотрись'] })) {
    for (const order of orders) {
      const route = routeRule(order, 'Alex');
      assert.equal(route.tool, tool, order);
      assert.deepEqual(route.args, ['come', 'follow'].includes(tool) ? { player: 'Alex' } : {});
      assert.equal(route.ack, undefined);
    }
  }
  assert.equal(addressed('kIrIlL, ко мне', 'Kirill'), 'ко мне');
  assert.equal(addressed('Кир, осмотрись', 'Kirill', ['Кир']), 'осмотрись');
  assert.equal(addressed('Kirillson stop', 'Kirill'), null);
  assert.equal(addressed('hello Kirill', 'Kirill'), null);
  assert.equal(routeRule('stop and mine coal', 'Alex').nextOrder, 'mine coal');
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
