import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { JsonProcess, modelHome, processOptions } from './json-process.js';

/** Plugins, auto-memory and tools the translator's CLI must not load; brain/check-isolation.js proves it. */
export const MODEL_SETTINGS = fileURLToPath(new URL('./model-settings.json', import.meta.url));

/**
 * The translator sees its prompt and the message, nothing else (ADR-0017): no tools, MCP servers, skills
 * or slash commands, no host settings, CLAUDE.md/AGENTS.md or memory, no saved session. Subscription
 * auth stays in the user's normal config (no --bare).
 */
export function buildClaudeArgs({ promptFile = 'brain/prompt.md', model = 'haiku', effort, settings = MODEL_SETTINGS } = {}) {
  return ['-p', '--model', model, '--mcp-config', '{"mcpServers":{}}', '--strict-mcp-config',
    '--tools', '', '--allowedTools', '', '--permission-mode', 'dontAsk',
    '--setting-sources', 'local', '--settings', settings, '--disable-slash-commands', '--no-session-persistence',
    '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
    '--include-partial-messages', '--system-prompt-file', promptFile, ...(effort ? ['--effort', effort] : [])];
}

/** No update checks, telemetry or error reports from a process that only translates. */
export const modelEnv = (env = process.env) => ({ ...env, DISABLE_AUTOUPDATER: '1', CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1' });

// The only spawning site for Claude; injectable for protocol tests, never invokes a shell.
function startProcess(options, spawnProcess) {
  const prefix = process.env.CLAUDE_SCRIPT ? [process.env.CLAUDE_SCRIPT] : [];
  return spawnProcess(process.env.CLAUDE_BIN ?? 'claude', [...prefix, ...buildClaudeArgs(options)],
    processOptions(options.cwd ?? modelHome(), modelEnv()));
}

export class ClaudeBackend {
  backend = 'claude';
  model = 'haiku';
  constructor(options = {}) {
    this.options = options;
    this.model = options.model ?? 'haiku';
    this.turns = 0;
    this.maxTurns = options.maxTurns ?? 10;
    this.spawnProcess = options.spawnProcess ?? spawn;
  }

  prepare() {
    if (!this.process || this.process.error || this.turns >= this.maxTurns) {
      this.close();
      this.process = new JsonProcess(startProcess(this.options, this.spawnProcess),
        event => this.onEvent(event), error => this.active?.reject(error));
    }
  }

  async send(text) {
    if (this.active) throw new Error('Claude already has an active turn');
    this.prepare();
    const started = Date.now();
    try {
      return await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { this.close(); }, this.options.timeoutMs ?? 660_000);
        this.active = { started, resolve, reject, timer, text: '', toolCalls: 0, ttftMs: null };
        this.process.write({ type: 'user', message: { role: 'user', content: text } });
      });
    } catch (error) {
      this.process?.close();
      throw error;
    } finally {
      clearTimeout(this.active?.timer);
      this.active = null;
    }
  }

  onEvent(event) {
    // What the CLI loaded (tools, servers, skills, plugins, memory): the isolation check reads it.
    if (event.type === 'system' && event.subtype === 'init') this.init = event;
    const turn = this.active;
    if (!turn) return;
    if (event.type === 'stream_event' && event.event?.type === 'content_block_start' && event.event.content_block?.type === 'tool_use')
      this.onTrace?.('tool', { tool: event.event.content_block.name, arguments: null, status: 'started' });
    if (event.type === 'stream_event' && event.event?.delta?.type === 'text_delta') {
      turn.ttftMs ??= Date.now() - turn.started;
      turn.streamed = true;
      this.onText?.(event.event.delta.text ?? '');
    }
    if (event.type === 'user') {
      for (const block of event.message?.content ?? []) {
        if (block?.type === 'tool_result') this.onTrace?.('tool_result', { result: block.content ?? null });
      }
    }
    if (event.type === 'assistant') {
      for (const block of event.message?.content ?? []) {
        if (block.type === 'thinking' && block.thinking) this.onTrace?.('reasoning', { text: block.thinking });
        if (block.type === 'tool_use') this.onTrace?.('tool', { tool: block.name, arguments: block.input ?? null });
        if (block.type === 'text') this.onTrace?.('message', { text: block.text ?? '' });
        if (block.type === 'tool_use') turn.toolCalls++;
        if (block.type === 'text') {
          if (!turn.streamed) this.onText?.(block.text, true);
          else this.onText?.('', true);
          this.options.onMessage?.(block.text);
          turn.ttftMs ??= Date.now() - turn.started;
          turn.text = block.text;
        }
      }
    }
    if (event.type !== 'result') return;
    if (event.is_error) { turn.reject(new Error(event.result ?? event.errors?.join('; ') ?? 'Claude failed')); return; }
    this.turns++;
    turn.resolve({ text: event.result ?? turn.text, usage: event.usage ?? {},
      toolCalls: turn.toolCalls, ms: Date.now() - turn.started, ttftMs: turn.ttftMs });
  }

  close() {
    this.active?.reject(new Error('Claude session cancelled'));
    this.process?.close();
    this.process = null;
    this.turns = 0;
  }
}
