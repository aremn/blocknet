import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { appendFile } from 'node:fs/promises';
import { Body, option } from './body.js';

export const PROTOCOL_VERSION = '2026-07-28';
const LEGACY_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
const serverInfo = { name: 'blocknet', version: '1.0.0' };

/** JSON lines only on stdout; concurrent requests let stop interrupt a waiting action. */
export function serveMcp(body, bot, input = process.stdin, output = process.stdout) {
  const lines = createInterface({ input });
  let initialized = false;
  const send = message => output.write(`${JSON.stringify(message)}\n`);
  lines.on('line', async line => {
    let request;
    try {
      request = JSON.parse(line);
    } catch {
      send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON' } });
      return;
    }
    if (request.id === undefined) return;
    const reply = result => send({ jsonrpc: '2.0', id: request.id, result });
    const error = (code, message, data) => send({ jsonrpc: '2.0', id: request.id, error: { code, message, ...(data && { data }) } });
    if (request.jsonrpc !== '2.0' || typeof request.method !== 'string') {
      error(-32600, 'Invalid request');
      return;
    }
    const version = request.params?._meta?.['io.modelcontextprotocol/protocolVersion'];
    if (version && version !== PROTOCOL_VERSION) {
      error(-32022, 'Unsupported protocol version', { supported: [PROTOCOL_VERSION, ...LEGACY_VERSIONS], requested: version });
      return;
    }
    try {
      switch (request.method) {
        case 'initialize': {
          const requested = request.params?.protocolVersion;
          initialized = true;
          reply({ protocolVersion: LEGACY_VERSIONS.includes(requested) ? requested : PROTOCOL_VERSION,
            capabilities: { tools: {} }, serverInfo });
          break;
        }
        case 'server/discover':
          reply({ resultType: 'complete', supportedVersions: [PROTOCOL_VERSION, ...LEGACY_VERSIONS],
            capabilities: { tools: {} }, _meta: { 'io.modelcontextprotocol/serverInfo': serverInfo } });
          break;
        case 'ping':
          reply({});
          break;
        case 'tools/list': {
          if (!initialized && !version) throw new Error('Initialize first');
          const hello = await body.ready();
          reply({ tools: hello.tools.map(tool => ({ name: tool.name, description: tool.description, inputSchema: tool.params })) });
          break;
        }
        case 'tools/call': {
          if (!initialized && !version) throw new Error('Initialize first');
          const { name, arguments: args = {} } = request.params ?? {};
          if (typeof name !== 'string') { error(-32602, 'Tool name required'); break; }
          try {
            if (process.env.BLOCKNET_AUDIT) {
              await appendFile(process.env.BLOCKNET_AUDIT, `${JSON.stringify({ time: Date.now(), tool: name })}\n`);
            }
            const text = await body.action(bot, name, args);
            reply({ content: [{ type: 'text', text }], isError: /^(?:\[[^\]]+\] )?(failed|blocked|cancelled)\b/i.test(text) });
          } catch (failure) {
            reply({ content: [{ type: 'text', text: failure.message }], isError: true });
          }
          break;
        }
        default:
          error(-32601, 'Unknown method');
      }
    } catch (failure) {
      error(-32603, failure.message);
    }
  });
  lines.on('close', () => body.close());
  return lines;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const body = new Body();
  body.on('diagnostic', text => process.stderr.write(`${text}\n`));
  serveMcp(body, option('bot', process.env.BLOCKNET_BOT ?? 'Kirill'));
}
