import { spawn } from 'node:child_process';
import { JsonProcess, processOptions } from './json-process.js';

// Values are TOML, passed as separate argv entries (JSON strings also quote TOML strings).
export function buildCodexArgs({ mcpServer, model = 'gpt-6-luna', serviceTier = 'priority', effort = 'low' } = {}) {
  const config = {
    model: JSON.stringify(model), service_tier: JSON.stringify(serviceTier), model_reasoning_effort: JSON.stringify(effort),
    approval_policy: '"never"', sandbox_mode: '"read-only"', project_doc_max_bytes: '0',
    'features.skip_host_skill_discovery': 'true', 'features.skill_search': 'false',
    'features.plugins': 'false', 'features.apps': 'false', 'features.memories': 'false',
    'features.shell_tool': 'false', 'features.multi_agent': 'false', web_search: '"disabled"',
  };
  if (mcpServer) {
    const env = Object.entries(mcpServer.env ?? {}).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(',');
    config.mcp_servers = `{blocknet={command=${JSON.stringify(mcpServer.command)},args=${JSON.stringify(mcpServer.args)},env={${env}},default_tools_approval_mode="approve",tool_timeout_sec=600}}`;
  }
  return ['app-server', '--listen', 'stdio://', ...Object.entries(config).flatMap(([k, v]) => ['-c', `${k}=${v}`])];
}

// The only spawning site for Codex; subscription auth stays in the normal Codex home.
function startProcess(options, spawnProcess) {
  const prefix = process.env.CODEX_SCRIPT ? [process.env.CODEX_SCRIPT] : [];
  return spawnProcess(process.env.CODEX_BIN ?? 'codex', [...prefix, ...buildCodexArgs(options)], processOptions(options.cwd));
}

/** Protocol shapes follow _src/codex-app-server/{ClientRequest,ServerNotification,ServerRequest}.ts. */
export class CodexBackend {
  backend = 'codex';
  constructor(options = {}) {
    this.options = options;
    this.model = options.model ?? 'gpt-6-luna';
    this.spawnProcess = options.spawnProcess ?? spawn;
    this.maxTurns = options.maxTurns ?? 10;
    this.turns = 0;
    this.totalUsage = {};
  }

  async initialize() {
    this.process = new JsonProcess(startProcess(this.options, this.spawnProcess),
      event => this.onEvent(event), error => this.active?.reject(error));
    await this.process.request('initialize', { clientInfo: { name: 'blocknet', version: '1.0' }, capabilities: null });
    this.process.write({ method: 'initialized' });
    const result = await this.process.request('thread/start', {
      model: this.model, serviceTier: this.options.serviceTier ?? 'priority', cwd: this.options.cwd,
      approvalPolicy: 'never', sandbox: 'read-only', baseInstructions: this.options.prompt,
      developerInstructions: 'Use only the blocknet MCP tools to act in Minecraft.', ephemeral: true,
    });
    this.threadId = result.thread.id;
  }

  async send(text) {
    if (this.active) throw new Error('Codex already has an active turn');
    if (!this.process || this.process.error || this.turns >= this.maxTurns) this.close();
    const started = Date.now();
    let timer;
    try {
      const result = new Promise((resolve, reject) => {
        timer = setTimeout(() => this.close(), this.options.timeoutMs ?? 660_000);
        this.active = { resolve, reject, started, text: '', deltas: '', usage: {},
          beforeUsage: { ...this.totalUsage }, toolIds: new Set(), ttftMs: null };
      });
      // Observe rejection during startup as well as during a turn.
      const launch = async () => {
        if (!this.process) await this.initialize();
        await this.process.request('turn/start', { threadId: this.threadId,
          input: [{ type: 'text', text, text_elements: [] }], effort: this.options.effort ?? 'low', serviceTier: this.options.serviceTier ?? 'priority',
          approvalPolicy: 'never' });
      };
      const turn = this.active;
      launch().catch(error => turn.reject(error));
      const value = await result;
      this.turns++;
      return value;
    } catch (error) {
      this.process?.close();
      throw error;
    } finally {
      clearTimeout(timer);
      this.active = null;
    }
  }

  onEvent(event) {
    const p = event.params ?? {};
    if (event.id !== undefined && event.method) {
      // MCP tool consent is elicitation in this binding version. Never approve shell/file requests.
      if (event.method === 'mcpServer/elicitation/request' && p.serverName === 'blocknet') {
        this.process.write({ id: event.id, result: { action: 'accept', content: { decision: 'approve' }, _meta: null } });
      } else {
        this.process.write({ id: event.id, error: { code: -32601, message: 'Only blocknet MCP approvals are supported' } });
      }
      return;
    }
    const turn = this.active;
    if (!turn || p.threadId && this.threadId && p.threadId !== this.threadId) return;
    if (event.method === 'item/agentMessage/delta') {
      turn.ttftMs ??= Date.now() - turn.started;
      turn.deltas += p.delta;
    }
    if (event.method === 'item/completed') {
      if (p.item?.type === 'agentMessage') this.options.onMessage?.(p.item.text);

      if (p.item.type === 'agentMessage' && p.item.phase !== 'commentary') turn.text = p.item.text;
      if (p.item.type === 'mcpToolCall') turn.toolIds.add(p.item.id);
    }
    if (event.method === 'thread/tokenUsage/updated') {
      this.totalUsage = p.tokenUsage.total;
      turn.usage = Object.fromEntries(Object.entries(this.totalUsage).map(([key, value]) =>
        [key, value - (turn.beforeUsage[key] ?? 0)]));
    }
    if (event.method === 'error' && !p.willRetry) turn.reject(new Error(p.error?.message ?? 'Codex failed'));
    if (event.method === 'turn/completed') {
      if (p.turn.status !== 'completed') {
        turn.reject(new Error(p.turn.error?.message ?? `Codex turn ${p.turn.status}`));
        return;
      }
      turn.resolve({ text: turn.text || turn.deltas, usage: turn.usage,
        toolCalls: turn.toolIds.size, ms: Date.now() - turn.started, ttftMs: turn.ttftMs });
    }
  }

  close() {
    this.active?.reject(new Error('Codex session cancelled'));
    this.process?.close();
    this.process = null;
    this.threadId = null;
    this.turns = 0;
    this.totalUsage = {};
  }
}
