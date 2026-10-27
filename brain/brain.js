import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Body, configPath, option } from './body.js';
import { addressed, routeRule } from './rules.js';
import { ClaudeBackend } from './claude-backend.js';
import { CodexBackend } from './codex-backend.js';
export { buildClaudeArgs } from './claude-backend.js';

const brainDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(brainDir);

export function createBackend(name, options) {
  if (name === 'claude') return new ClaudeBackend(options);
  if (name === 'codex') return new CodexBackend(options);
  throw new Error('--backend must be claude or codex');
}

/** Acknowledge only turns which are still waiting after the short grace period. */
export async function sendWithAck(backend, text, say, { delayMs = 700, ack = 'On it.' } = {}) {
  let acknowledgement;
  const timer = setTimeout(() => {
    acknowledgement = Promise.resolve().then(() => say(ack)).catch(error => process.stderr.write(`${error.message}\n`));
  }, delayMs);
  try { return await backend.send(text); }
  finally { clearTimeout(timer); await acknowledgement; }
}

/** Keep inventory/held/low-health facts from the revision-based changes tool. */
export class CompactState {
  facts = { inventory: 'unknown', held: 'empty', health: '' };
  previous = '';
  update(delta) {
    const text = String(delta ?? '');
    const body = text.match(/body r\d+: ([^\n]*)/u)?.[1];
    if (body) {
      const health = body.match(/health ([\d.]+)\/([\d.]+)/u);
      this.facts.health = health && +health[1] / +health[2] < 0.4 ? `health low (${health[1]}/${health[2]})` : '';
    }
    const inventory = text.match(/inventory r\d+: ([\s\S]*?)(?=\n(?:body|task) r\d+:|$)/u)?.[1];
    if (inventory) {
      this.facts.held = inventory.match(/^main: (?:\d+ )?([\w:./-]+)/mu)?.[1] ?? 'empty';
      this.facts.inventory = inventory.split('\n').filter(line => line.includes(' slots [')).map(line => {
        const match = line.match(/^(?:(\d+) )?([\w:./-]+)/u);
        return match ? `${match[1] ?? 1} ${match[2]}` : '';
      }).filter(Boolean).join(', ') || 'empty';
    }
    const current = `inventory: ${this.facts.inventory}; held: ${this.facts.held}${this.facts.health ? `; ${this.facts.health}` : ''}`;
    if (current === this.previous) return '';
    this.previous = current;
    return `\nState: ${current}`;
  }
  reset() { this.previous = ''; }
}

function shortReply(text) {
  return String(text ?? '').trim().replace(/\s+/gu, ' ').split(' ').slice(0, 12).join(' ').slice(0, 200);
}

export async function main() {
  const bot = option('bot', process.env.BLOCKNET_BOT ?? 'Kirill');
  const aliases = option('alias', process.env.BLOCKNET_ALIASES ?? '').split(',').map(s => s.trim()).filter(Boolean);
  if (!/^[A-Za-z0-9_]{3,16}$/.test(bot)) throw new Error('Invalid Minecraft bot name');
  const config = configPath();
  const body = new Body(config);
  body.on('diagnostic', text => process.stderr.write(`${text}\n`));
  const mcpConfig = bot === 'Kirill' ? 'brain/mcp.json' : `brain/mcp.${bot}.json`;
  const auditFile = resolve(brainDir, `logs/tool-calls.${bot}.jsonl`);
  const mcpServer = { command: process.execPath, args: [resolve(brainDir, 'mcp-server.js')],
    env: { BLOCKNET_CONFIG: config, BLOCKNET_BOT: bot, BLOCKNET_AUDIT: auditFile } };
  await writeFile(resolve(rootDir, mcpConfig), `${JSON.stringify({ mcpServers: { blocknet: { type: 'stdio', ...mcpServer } } }, null, 2)}\n`);
  await mkdir(resolve(brainDir, 'logs'), { recursive: true });
  const backend = createBackend(option('backend', process.env.BLOCKNET_BACKEND ?? 'codex'), {
    cwd: rootDir, mcpConfig, mcpServer, prompt: await readFile(resolve(brainDir, 'prompt.md'), 'utf8'),
    maxTurns: Number(option('max-turns', '30')),
  });
  const compact = new CompactState();
  let busy = false;
  let generation = 0;
  const queue = [];
  const say = text => body.call(bot, 'say', { text });

  async function processQueue() {
    if (busy) return;
    busy = true;
    try {
      while (queue.length) {
        const message = queue.shift();
        if (message.rule) {
          await runRule(message.rule).catch(error => process.stderr.write(`${error.message}\n`));
          continue;
        }
        const turnGeneration = generation;
        const started = Date.now();
        let result;
        let failure;
        let called = false;
        try {
          if (!backend.process || backend.process.error || backend.turns >= backend.maxTurns) compact.reset();
          const delta = await body.call(bot, 'changes');
          if (turnGeneration !== generation) continue;
          const prompt = `You are ${bot}. ${message.from}: ${message.text}${compact.update(delta.result)}`;
          await writeFile(auditFile, '');
          if (turnGeneration !== generation) continue;
          called = true;
          const acks = /[а-яё]/iu.test(message.text) ? ['Щас.', 'Ок.', 'Делаю.'] : ['On it.'];
          result = await sendWithAck(backend, prompt, text => turnGeneration === generation ? say(text) : undefined,
            { ack: acks[Math.floor(Math.random() * acks.length)] });
          if (turnGeneration !== generation) continue;
          const reply = shortReply(result.text);
          if (reply) await say(reply);
        } catch (error) {
          failure = error.message;
          compact.reset();
          if (turnGeneration === generation) process.stderr.write(`${failure}\n`);
        } finally {
          if (called) {
            const audit = await readFile(auditFile, 'utf8').catch(() => '');
            await appendFile(resolve(brainDir, 'logs/usage.jsonl'), `${JSON.stringify({
              time: new Date(started).toISOString(), bot, backend: backend.backend, model: backend.model,
              ms: result?.ms ?? Date.now() - started, ttft_ms: result?.ttftMs ?? null,
              usage: result?.usage ?? null,
              tool_calls: audit.trim() ? audit.trim().split('\n').length : result?.toolCalls ?? 0,
              error: failure ?? null,
            })}\n`);
          }
        }
      }
    } finally { busy = false; }
  }

  async function runRule(rule) {
    const result = await body.call(bot, rule.tool, rule.args);
    await say(result.result && /failed|blocked/i.test(result.result) ? result.result : rule.ack);
    if (result.task_id) {
      body.waitTask(result.task_id).then(text => {
        if (/failed|blocked/i.test(text)) return say(text);
      }).catch(error => process.stderr.write(`${error.message}\n`));
    }
  }

  function cancel() {
    generation++;
    backend.close();
    compact.reset();
    queue.length = 0;
  }
  body.on('event', event => {
    if (event.type === 'bot.died' && event.bot.toLowerCase() === bot.toLowerCase()) {
      cancel();
      process.stderr.write(`${bot} died; spawn it again to continue.\n`);
      return;
    }
    if (event.type !== 'chat') return;
    const order = addressed(event.text, bot, aliases);
    if (order === null || !order) return;
    const rule = routeRule(order, event.from);
    if (rule?.tool === 'stop') {
      cancel();
      runRule(rule).catch(error => process.stderr.write(`${error.message}\n`));
      return;
    }
    queue.push(rule ? { rule } : { from: event.from, text: order });
    processQueue().catch(error => process.stderr.write(`${error.stack}\n`));
  });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { cancel(); body.close(); });
  await body.ready();
  process.stderr.write(`Brain ready for ${bot}: ${backend.backend}/${backend.model}.\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.stack}\n`); process.exit(1); });
}
