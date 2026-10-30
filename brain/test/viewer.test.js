import test from 'node:test';
import assert from 'node:assert/strict';
import { createViewer } from '../viewer.js';

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
