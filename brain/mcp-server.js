import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { appendFile, mkdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { appendSession, ResultSummary, failed } from './session-log.js';
import { Body, option } from './body.js';

export const PROTOCOL_VERSION = '2026-07-28';
const LEGACY_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05'];
export const TURN_TOOL_LIMIT = 8;
export const TURN_LIMIT_MESSAGE = 'turn tool limit reached; answer the player or wait for the next turn';
const serverInfo = { name: 'blocknet', version: '1.0.0' };

/** JSON lines only on stdout; concurrent requests let stop interrupt a waiting action. */
export const feedbackTool = { name: 'tool_feedback', description: 'Report a missing, confusing, redundant or buggy tool in one concrete sentence. Brain-only; does not act in the game.',
  inputSchema: { type: 'object', properties: { kind: { type: 'string', enum: ['missing', 'confusing', 'redundant', 'bug'] },
    tool: { type: 'string' }, note: { type: 'string', maxLength: 500 } }, required: ['kind', 'note'], additionalProperties: false } };

export function serveMcp(body, bot, input = process.stdin, output = process.stdout, options = {}) {
  const lines = createInterface({ input });
  lines.on('error', error => { process.stderr.write(`MCP input: ${error.message}\n`); lines.close(); });
  const summary = new ResultSummary();
  const log = entry => appendSession(options.sessionFile ?? process.env.BLOCKNET_SESSION, { bot, ...entry });
  const feedbackFile = options.feedbackFile ?? resolve(dirname(fileURLToPath(import.meta.url)), 'logs/tool-feedback.jsonl');
  let initialized = false;
  let calls = 0, reservation = Promise.resolve();
  const auditFile = options.auditFile ?? process.env.BLOCKNET_AUDIT;
  const plannerOnly = (options.role ?? process.env.BLOCKNET_ROLE) === 'planner';
  // Serialize reservations, including concurrent calls. The brain clears this ledger before each
  // model turn; reading it also preserves the cap if an MCP subprocess reconnects mid-turn.
  const reserve = (name, args) => {
    const next = reservation.then(async () => {
      if (auditFile) {
        const ledger = await readFile(auditFile, 'utf8').catch(error => { if (error.code === 'ENOENT') return ''; throw error; });
        calls = ledger.split('\n').filter(Boolean).length;
      }
      if (calls >= TURN_TOOL_LIMIT) return false;
      calls++;
      if (auditFile) await appendFile(auditFile, `${JSON.stringify({ time: Date.now(), tool: name, args })}\n`);
      return true;
    });
    reservation = next.catch(() => {});
    return next;
  };
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
          reply({ tools: hello.tools.filter(t => !plannerOnly || t.name === 'knowledge')
            .map(tool => ({ name: tool.name, description: tool.description, inputSchema: tool.params })).concat(plannerOnly ? [] : feedbackTool) });
          break;
        }
        case 'tools/call': {
          if (!initialized && !version) throw new Error('Initialize first');
          const { name, arguments: args = {} } = request.params ?? {};
          if (typeof name !== 'string') { error(-32602, 'Tool name required'); break; }
          if (plannerOnly && name !== 'knowledge') { error(-32602, 'Planner may only call knowledge'); break; }
          if (!await reserve(name, args)) {
            reply({ content: [{ type: 'text', text: TURN_LIMIT_MESSAGE }], isError: true });
            break;
          }
          const started = Date.now();
          const id = `${process.pid}-${started}-${request.id}`;
          log({ type: 'tool_call', id, tool: name, args });
          try {
            let text;
            if (name === 'tool_feedback') {
              if (!['missing', 'confusing', 'redundant', 'bug'].includes(args.kind) || typeof args.note !== 'string'
                || !args.note.trim() || args.note.length > 500 || args.tool != null && typeof args.tool !== 'string'
                || Object.keys(args).some(k => !['kind', 'tool', 'note'].includes(k))) throw new Error('Invalid tool_feedback: kind and a concrete note (<=500 chars) required');
              const feedback = { type: 'tool_feedback', bot, session: process.env.BLOCKNET_SESSION_ID ?? options.sessionId,
                kind: args.kind, ...(args.tool && { tool: args.tool }), note: args.note.trim() };
              await mkdir(dirname(feedbackFile), { recursive: true });
              await appendFile(feedbackFile, `${JSON.stringify({ time: new Date().toISOString(), ...feedback })}\n`);
              log(feedback);
              text = 'Feedback recorded';
            } else text = await body.action(bot, name, args);
            const isError = failed(text);
            log({ type: 'tool_result', id, tool: name, result: summary.summarize(name, text), failed: isError, ms: Date.now()-started });
            reply({ content: [{ type: 'text', text }], isError });
          } catch (failure) {
            log({ type: 'tool_result', id, tool: name, result: failure.message, failed: true, ms: Date.now()-started });
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
