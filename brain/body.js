import net from 'node:net';
import { EventEmitter } from 'node:events';
import { readFile } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { resolve } from 'node:path';

export function option(name, fallback, argv = process.argv.slice(2)) {
  const index = argv.indexOf(`--${name}`);
  return index < 0 ? fallback : argv[index + 1];
}

export function configPath() {
  return resolve(option('config', process.env.BLOCKNET_CONFIG ?? 'mod/run/config/blocknet_ai-bridge.json'));
}

/** Reconnects the transport, never replays actions whose outcome is unknown. */
export class Body extends EventEmitter {
  constructor(config = configPath()) {
    super();
    this.config = config;
    this.pending = new Map();
    this.tasks = new Map();
    this.completed = new Map();
    this.nextId = 1;
    this.closed = false;
    this.hello = null;
  }

  connect() {
    if (this.connecting || this.closed || this.socket) return;
    this.connecting = true;
    this.open().catch(error => {
      this.emit('diagnostic', error.message);
      this.retry();
    }).finally(() => { this.connecting = false; });
  }

  async open() {
    const config = JSON.parse(await readFile(this.config, 'utf8'));
    if (this.closed) return;
    const socket = net.createConnection({ host: '127.0.0.1', port: config.port });
    this.socket = socket;
    socket.setNoDelay(true);
    const lines = createInterface({ input: socket });
    lines.on('error', error => { this.emit('diagnostic', error.message); socket.destroy(); });
    lines.on('line', line => {
      try { this.receive(JSON.parse(line)); }
      catch (error) { this.emit('diagnostic', `Invalid bridge message: ${error.message}`); }
    });
    socket.on('error', error => this.emit('diagnostic', error.message));
    socket.on('close', () => {
      lines.close();
      this.socket = null;
      this.hello = null;
      for (const entry of this.pending.values()) entry.reject(new Error('Body disconnected; action was not replayed'));
      for (const entry of this.tasks.values()) entry.reject(new Error('Body disconnected; task outcome unknown'));
      this.pending.clear();
      this.tasks.clear();
      this.completed.clear();
      this.emit('disconnected');
      this.retry();
    });
    socket.once('connect', async () => {
      try {
        this.hello = await this.request('hello', { protocol: 1, token: config.token });
        this.emit('ready', this.hello);
      } catch (error) {
        this.emit('diagnostic', error.message);
        socket.destroy();
      }
    });
  }

  retry() {
    if (this.closed || this.retryTimer) return;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      this.connect();
    }, 1000);
  }

  ready(timeout = 30_000) {
    if (this.hello) return Promise.resolve(this.hello);
    return new Promise((resolveReady, reject) => {
      const onReady = hello => { clearTimeout(timer); resolveReady(hello); };
      const timer = setTimeout(() => {
        this.off('ready', onReady);
        reject(new Error('Bridge unavailable; check server and --config'));
      }, timeout);
      this.once('ready', onReady);
      this.connect();
    });
  }

  request(method, params) {
    if (!this.socket || this.socket.destroyed) return Promise.reject(new Error('Body disconnected'));
    const id = this.nextId++;
    return new Promise((resolveRequest, rejectRequest) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        rejectRequest(new Error(`Bridge request timed out: ${method}`));
      }, 30_000);
      const settle = fn => value => { clearTimeout(timer); fn(value); };
      this.pending.set(id, { resolve: settle(resolveRequest), reject: settle(rejectRequest) });
      this.socket.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  receive(message) {
    if (message.method === 'event') {
      const event = message.params;
      if (event.type === 'bots.changed' && this.hello) this.hello.bots = event.bots;
      if (event.type === 'task.finished') {
        const entry = this.tasks.get(event.task_id);
        if (entry) {
          this.tasks.delete(event.task_id);
          entry.resolve(event.result);
        } else {
          // A synchronous failure can arrive in the same TCP chunk as task_id.
          this.completed.set(event.task_id, event.result);
          if (this.completed.size > 128) this.completed.delete(this.completed.keys().next().value);
        }
      }
      this.emit('event', event);
      return;
    }
    const pending = this.pending.get(message.id);
    if (!pending) return;
    this.pending.delete(message.id);
    if (message.error) pending.reject(new Error(message.error.message));
    else pending.resolve(message.result);
  }

  /** Fire-and-forget debug note for the bot's in-game trace and overlay; never throws, never waits. */
  note(bot, source, text) {
    if (!this.hello || !this.socket || this.socket.destroyed) return;
    try {
      this.socket.write(`${JSON.stringify({ jsonrpc: '2.0', method: 'debug.note', params: { bot, source, text: String(text ?? '') } })}\n`);
    } catch { /* A lost note must never disturb the brain. */ }
  }

  async call(bot, tool, args = {}) {
    await this.ready();
    return this.request('tool.call', { bot, tool, args });
  }

  async action(bot, tool, args = {}, timeout = 600_000) {
    const result = await this.call(bot, tool, args);
    if (!result.task_id) return result.result;
    return this.waitTask(result.task_id, timeout);
  }

  waitTask(id, timeout = 600_000) {
    if (this.completed.has(id)) {
      const text = this.completed.get(id);
      this.completed.delete(id);
      return Promise.resolve(text);
    }
    if (!this.hello) return Promise.reject(new Error('Body disconnected; task outcome unknown'));
    return new Promise((resolveTask, rejectTask) => {
      const timer = setTimeout(() => {
        this.tasks.delete(id);
        rejectTask(new Error('Task timed out after 10 minutes; use status or stop'));
      }, timeout);
      const settle = fn => value => { clearTimeout(timer); fn(value); };
      this.tasks.set(id, { resolve: settle(resolveTask), reject: settle(rejectTask) });
    });
  }

  close() {
    this.closed = true;
    clearTimeout(this.retryTimer);
    this.socket?.destroy();
  }
}
