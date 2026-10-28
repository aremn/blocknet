import { spawn } from 'node:child_process';
import { JsonProcess, processOptions } from './json-process.js';

export function buildClaudeArgs({ mcpConfig = 'brain/mcp.json', promptFile = 'brain/prompt.md', model = 'haiku', effort, role = 'actor' } = {}) {
  return ['-p', '--model', model, '--mcp-config', mcpConfig, '--strict-mcp-config',
    '--tools', role === 'planner' ? 'WebSearch' : '', '--allowedTools', role === 'planner' ? 'mcp__blocknet,WebSearch' : role === 'chat' ? '' : 'mcp__blocknet', '--permission-mode', 'dontAsk',
    '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
    '--include-partial-messages', '--system-prompt-file', promptFile, ...(effort ? ['--effort', effort] : [])];
}

// The only spawning site for Claude; injectable for protocol tests, never invokes a shell.
function startProcess(options, spawnProcess) {
  const prefix = process.env.CLAUDE_SCRIPT ? [process.env.CLAUDE_SCRIPT] : [];
  return spawnProcess(process.env.CLAUDE_BIN ?? 'claude', [...prefix, ...buildClaudeArgs(options)], processOptions(options.cwd));
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
    const turn = this.active;
    if (!turn) return;
    if (event.type === 'stream_event' && event.event?.delta?.type === 'text_delta') {
      turn.ttftMs ??= Date.now() - turn.started;
    }
    if (event.type === 'assistant') {
      for (const block of event.message?.content ?? []) {
        if (block.type === 'tool_use') turn.toolCalls++;
        if (block.type === 'text') {
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
