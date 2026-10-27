import { spawn } from 'node:child_process';
import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Body, configPath, option } from './body.js';
import { addressed, routeRule } from './rules.js';

const brainDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(brainDir);

/** All Claude switches live here so CLI changes are easy to adjust and test. */
export function buildClaudeArgs({ sessionId, mcpConfig = 'brain/mcp.json', promptFile = 'brain/prompt.md' } = {}) {
  const args = ['-p', '--model', 'haiku', '--mcp-config', mcpConfig, '--strict-mcp-config',
    '--tools', '', '--allowedTools', 'mcp__blocknet', '--permission-mode', 'dontAsk',
    '--output-format', 'json', '--system-prompt-file', promptFile];
  if (sessionId) args.push('--resume', sessionId);
  return args;
}

function runClaude(args, prompt, onChild) {
  return new Promise((resolveCall, reject) => {
    // Native claude/claude.exe, or node + CLAUDE_SCRIPT for Windows npm installs; never a shell.
    const command = process.env.CLAUDE_BIN ?? 'claude';
    const prefix = process.env.CLAUDE_SCRIPT ? [process.env.CLAUDE_SCRIPT] : [];
    const child = spawn(command, [...prefix, ...args], { cwd: rootDir, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    onChild(child);
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8').on('data', text => { stdout += text; });
    child.stderr.setEncoding('utf8').on('data', text => { stderr = (stderr + text).slice(-8000); });
    child.on('error', reject);
    child.on('close', code => {
      if (code !== 0) { reject(new Error(`Claude exited ${code}: ${stderr}`)); return; }
      try { resolveCall(JSON.parse(stdout)); }
      catch { reject(new Error('Claude did not return JSON; check CLI flags in buildClaudeArgs')); }
    });
    child.stdin.on('error', () => {});
    child.stdin.end(prompt);
  });
}

function shortReply(text) {
  return String(text ?? '').trim().split(/\r?\n/).filter(Boolean).slice(0, 2).join(' ').slice(0, 300);
}

export async function main() {
  const bot = option('bot', process.env.BLOCKNET_BOT ?? 'Kirill');
  const aliases = option('alias', process.env.BLOCKNET_ALIASES ?? '').split(',').map(s => s.trim()).filter(Boolean);
  const config = configPath();
  const body = new Body(config);
  body.on('diagnostic', text => process.stderr.write(`${text}\n`));
  const stateFile = resolve(brainDir, 'state.json');
  let state = {};
  try { state = JSON.parse(await readFile(stateFile, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const session = state[bot] ?? { turns: 0 };
  state[bot] = session;
  // Keep different bots' generated MCP configurations independent.
  const mcpConfig = bot === 'Kirill' ? 'brain/mcp.json' : `brain/mcp.${bot}.json`;
  const auditFile = resolve(brainDir, `logs/tool-calls.${bot}.jsonl`);
  if (!/^[A-Za-z0-9_]{3,16}$/.test(bot)) throw new Error('Invalid Minecraft bot name');
  await writeFile(resolve(rootDir, mcpConfig), `${JSON.stringify({ mcpServers: { blocknet: {
    type: 'stdio', command: process.execPath, args: [resolve(brainDir, 'mcp-server.js')],
    env: { BLOCKNET_CONFIG: config, BLOCKNET_BOT: bot, BLOCKNET_AUDIT: auditFile },
  } } }, null, 2)}\n`);
  await mkdir(resolve(brainDir, 'logs'), { recursive: true });
  let child;
  let busy = false;
  let generation = 0;
  const queue = [];
  const say = text => body.call(bot, 'say', { text });
  const save = async () => {
    // Other bots can have their own brain process and sessions in this file.
    const current = await readFile(stateFile, 'utf8').then(JSON.parse).catch(error => {
      if (error.code !== 'ENOENT') throw error;
      return {};
    });
    current[bot] = session;
    await writeFile(stateFile, `${JSON.stringify(current, null, 2)}\n`);
  };

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
          if (session.turns >= 30) { delete session.sessionId; session.turns = 0; }
          const delta = await body.call(bot, 'changes');
          if (turnGeneration !== generation) continue;
          const prompt = `You are ${bot}. ${message.from}: ${message.text}\nChanges: ${delta.result}`;
          await writeFile(auditFile, '');
          if (turnGeneration !== generation) continue;
          called = true;
          result = await runClaude(buildClaudeArgs({ sessionId: session.sessionId, mcpConfig }), prompt, value => { child = value; });
          if (turnGeneration !== generation) continue;
          if (result.is_error) throw new Error(shortReply(result.result) || 'Claude returned an error');
          session.sessionId = result.session_id;
          session.turns++;
          await save();
          const reply = shortReply(result.result);
          if (reply) await say(reply);
        } catch (error) {
          failure = error.message;
          if (turnGeneration === generation) process.stderr.write(`${failure}\n`);
        } finally {
          child = null;
          const usage = result?.usage ?? {};
          const toolUsage = result?.modelUsage ?? {};
          if (called) {
            const audit = await readFile(auditFile, 'utf8').catch(() => '');
            await appendFile(resolve(brainDir, 'logs/usage.jsonl'), `${JSON.stringify({
              time: new Date(started).toISOString(), bot, duration_ms: Date.now() - started,
              input_tokens: usage.input_tokens ?? null, output_tokens: usage.output_tokens ?? null,
              cache_creation_input_tokens: usage.cache_creation_input_tokens ?? null,
              cache_read_input_tokens: usage.cache_read_input_tokens ?? null,
              total_cost_usd: result?.total_cost_usd ?? null, modelUsage: toolUsage,
              usage, cost_usd: result?.cost_usd ?? null,
              tool_calls: audit.trim() ? audit.trim().split('\n').length : 0, error: failure ?? null,
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

  body.on('event', event => {
    if (event.type === 'bot.died' && event.bot.toLowerCase() === bot.toLowerCase()) {
      generation++;
      child?.kill();
      queue.length = 0;
      delete session.sessionId;
      session.turns = 0;
      save().catch(error => process.stderr.write(`${error.message}\n`));
      process.stderr.write(`${bot} died; spawn it again to continue.\n`);
      return;
    }
    if (event.type !== 'chat') return;
    const order = addressed(event.text, bot, aliases);
    if (order === null || !order) return;
    const rule = routeRule(order, event.from);
    if (rule) {
      if (rule.tool === 'stop') {
        generation++;
        child?.kill();
        delete session.sessionId;
        session.turns = 0;
        queue.length = 0;
        save().catch(error => process.stderr.write(`${error.message}\n`));
        runRule(rule).catch(error => process.stderr.write(`${error.message}\n`));
        return;
      }
      queue.push({ rule });
      processQueue().catch(error => process.stderr.write(`${error.stack}\n`));
      return;
    }
    queue.push({ from: event.from, text: event.text });
    processQueue().catch(error => process.stderr.write(`${error.stack}\n`));
  });
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
    child?.kill();
    body.close();
  });
  await body.ready();
  process.stderr.write(`Brain ready for ${bot}${aliases.length ? ` (${aliases.join(', ')})` : ''}.\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.stack}\n`); process.exit(1); });
}
