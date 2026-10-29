// Local, read-only memory viewer. The bridge token stays in this process, never in the browser.
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { Body, option } from './body.js';

export function createViewer(body) {
  const assets = new Map([['/', ['viewer/index.html', 'text/html']], ['/viewer.js', ['viewer/viewer.js', 'text/javascript']], ['/viewer.css', ['viewer/viewer.css', 'text/css']],
    ['/3d', ['viewer/3d.html', 'text/html']], ['/3d.js', ['viewer/3d.js', 'text/javascript']], ['/3d.css', ['viewer/3d.css', 'text/css']]]);
  let busy = false;
  return http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
    const authority = `127.0.0.1:${res.socket.localPort}`;
    if (req.headers.host !== authority || (req.headers.origin && req.headers.origin !== `http://${authority}`)) {
      res.writeHead(403); res.end('Local access only'); return;
    }
    if (req.method !== 'GET') { res.writeHead(405); res.end(); return; }
    const url = new URL(req.url, `http://${authority}`);
    try {
      if (assets.has(url.pathname)) {
        const [path, type] = assets.get(url.pathname);
        const content = await readFile(new URL(path, import.meta.url));
        res.setHeader('Content-Type', `${type}; charset=utf-8`); res.end(content); return;
      }
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      if (url.pathname === '/api/bots') {
        res.end(JSON.stringify({ connected: Boolean(body.hello), bots: body.hello?.bots ?? [] })); return;
      }
      if (url.pathname !== '/api/memory') { res.writeHead(404); res.end('{}'); return; }
      if (!body.hello) { res.writeHead(503); res.end(JSON.stringify({ error: 'Нет соединения с игрой. Запусти мир с обновлённым модом.' })); return; }
      if (busy) { res.writeHead(429); res.end(JSON.stringify({ error: 'Снимок уже запрашивается' })); return; }
      const mode = url.searchParams.get('mode');
      const params = { bot: url.searchParams.get('bot'), mode: ['slice', 'voxels'].includes(mode) ? mode : 'top' };
      for (const key of ['x', 'y', 'z', 'radius']) if (url.searchParams.has(key)) {
        const n = Number(url.searchParams.get(key));
        if (!Number.isSafeInteger(n) || Math.abs(n) > 30_000_000) throw new Error(`Invalid ${key}`);
        params[key] = n;
      }
      busy = true;
      try { res.end(JSON.stringify(await body.request('debug.memory', params))); }
      finally { busy = false; }
    } catch (error) {
      res.writeHead(502); res.end(JSON.stringify({ error: error.message === 'Unknown method'
        ? 'В игре старый мод: обнови jar и перезапусти мир.' : error.message }));
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const snapshotFile = option('snapshot', null);
  const saved = snapshotFile ? JSON.parse(await readFile(snapshotFile, 'utf8')) : null;
  const body = saved ? { hello: { bots: [saved.bot] }, request: async (_, params) => ({ ...saved,
    savedSnapshot: true, x: params.x ?? saved.x, z: params.z ?? saved.z }), close() {} } : new Body();
  if (!saved) { body.on('diagnostic', message => process.stderr.write(`${message}\n`)); body.connect(); }
  const server = createViewer(body);
  server.listen(Number(option('port', '25600')), '127.0.0.1', () => console.log(`Memory viewer: http://127.0.0.1:${server.address().port} (3D: /3d)`));
  const close = () => { body.close(); server.close(); };
  process.on('SIGINT', close); process.on('SIGTERM', close);
}
