import { readFile, writeFile, appendFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { resolve, join, dirname, basename } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import { CodexBackend } from '../codex-backend.js';
import { ClaudeBackend } from '../claude-backend.js';
import { FakeBody } from './fake-body.js';
import { evaluate, wordCount } from './rules.js';
import { renderReport } from './report.js';
import { roleScenarios } from './role-scenarios.js';
import { plannerInput } from '../plans.js';
export { roleScenarios } from './role-scenarios.js';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
export const scenarios = JSON.parse(await readFile(join(here, 'scenarios.json'), 'utf8'));
export const defaultModels = 'codex:gpt-6-luna,codex:gpt-6.1-terra,codex:gpt-reserve,codex:gpt-5.6-luna,claude:haiku,claude:sonnet';

export function parseArgs(argv) {
  const options = { models: defaultModels.split(','), tiers: ['default'], repeat: 3, timeoutMs: 120_000 };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    if (flag === '--fake') { options.fake = true; continue; }
    if (flag === '--help') { options.help = true; continue; }
    if (!['--models', '--tiers', '--repeat', '--timeout-ms', '--role'].includes(flag)) throw new Error(`Unknown option: ${flag}`);
    const value = argv[++i];
    if (!value || value.startsWith('--')) throw new Error(`Value required for ${flag}`);
    if (flag === '--models') options.models = value.split(',');
    if (flag === '--tiers') options.tiers = value.split(',');
    if (flag === '--repeat') options.repeat = Number(value);
    if (flag === '--timeout-ms') options.timeoutMs = Number(value);
    if (flag === '--role') options.role = value;
  }
  validateOptions(options);
  return options;
}

function validateOptions({ models, tiers, repeat, timeoutMs, role }) {
  if (role != null && !['planner', 'actor'].includes(role)) throw new Error('Role must be planner or actor');
  if (!models.length || models.some(m => !/^(codex|claude):[^:\s,]+$/.test(m))) throw new Error('Use backend:model pairs (codex or claude)');
  if (!tiers.length || tiers.some(t => !['default', 'priority'].includes(t))) throw new Error('Tiers must be default or priority');
  if (!Number.isInteger(repeat) || repeat < 1) throw new Error('Repeat must be a positive integer');
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1) throw new Error('Timeout must be positive milliseconds');
}

export function normalizeUsage(usage = {}, backend) {
  if (backend === 'claude') {
    const cached = usage.cache_read_input_tokens;
    return { input: usage.input_tokens == null ? null : usage.input_tokens + (cached ?? 0) + (usage.cache_creation_input_tokens ?? 0),
      cached: usage.input_tokens == null ? null : cached ?? 0, output: usage.output_tokens ?? null };
  }
  return { input: usage.inputTokens ?? null, cached: usage.cachedInputTokens ?? null, output: usage.outputTokens ?? null };
}

export function scenarioPrompt(scenario, role) {
  if (role === 'planner') return plannerInput({ goal: scenario.message, state: scenario.state, scene: scenario.scene });
  if (scenario.step) return `Goal: ${scenario.goal}\nStep: ${scenario.step}\nDone: ${scenario.done}\nState: ${scenario.state}\nScene: ${scenario.scene}\n${scenario.planStatus}`;
  return scenario.autoplay ? `${scenario.message}\nState: ${scenario.state}\nScene: ${scenario.scene}`
    : `You are BenchBot. Alex: ${scenario.message}\nState: ${scenario.state}\nScene: ${scenario.scene}`;
}

export const createBackend = options => options.backend === 'codex' ? new CodexBackend(options) : new ClaudeBackend(options);

export async function runBench({ models = defaultModels.split(','), tiers = ['default'], repeat = 3, timeoutMs = 120_000,
  role, cases = role ? roleScenarios[role] : scenarios, outputDir = join(root, 'build'), backendFactory = createBackend, onProgress = () => {} } = {}) {
  validateOptions({ models, tiers, repeat, timeoutMs, role });
  await mkdir(outputDir, { recursive: true });
  const workspace = await mkdtemp(join(outputDir, 'model-bench-work-'));
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const base = join(outputDir, `model-bench-${stamp}`);
  const rawFile = `${base}.jsonl`, reportFile = `${base}.md`;
  const records = [];
  const body = new FakeBody();
  const configFile = join(workspace, 'bridge.json'), auditFile = join(workspace, 'audit.jsonl');
  const promptFile = join(root, role === 'planner' ? 'brain/planner-prompt.md' : 'brain/prompt.md');
  const prompt = await readFile(promptFile, 'utf8');
  try {
    await body.start(configFile);
    const mcpServer = { command: process.execPath, args: [join(here, 'mcp.js')], env: {
      BLOCKNET_CONFIG: configFile, BLOCKNET_BOT: 'BenchBot', BLOCKNET_AUDIT: auditFile,
      BLOCKNET_SESSION: join(workspace, 'session.jsonl'), BLOCKNET_SESSION_ID: 'benchmark',
      ...(role === 'planner' && { BLOCKNET_ROLE: 'planner' }),
    } };
    const mcpConfig = join(workspace, 'mcp.json');
    await writeFile(mcpConfig, JSON.stringify({ mcpServers: { blocknet: mcpServer } }));
    await writeFile(rawFile, '');
    for (const spec of models) for (const tier of tiers) for (const scenario of cases) {
      const [backend, model] = spec.split(':');
      const options = { backend, model, serviceTier: tier, prompt, promptFile, mcpConfig, mcpServer,
        cwd: workspace, maxTurns: repeat + 1, timeoutMs, scenario, role };
      let session;
      let warm = false;
      try {
        for (let index = 1; index <= repeat; index++) {
          body.reset(scenario);
          await writeFile(auditFile, '');
          const input = scenarioPrompt(scenario, role);
          const started = performance.now();
          let result, error, timer;
          try {
            session ??= await backendFactory(options);
            result = await Promise.race([session.send(input), new Promise((_, reject) => {
              timer = setTimeout(() => reject(new Error(`Benchmark turn timeout after ${timeoutMs}ms`)), timeoutMs);
            })]);
          } catch (failure) { error = failure.message; }
          finally { clearTimeout(timer); }
          const ms = Math.round(performance.now() - started);
          const calls = (await readFile(auditFile, 'utf8')).split('\n').filter(Boolean).map(line => JSON.parse(line));
          const remaining = [...body.calls];
          for (const call of calls) {
            const found = remaining.findIndex(c => c.tool === call.tool && JSON.stringify(c.args) === JSON.stringify(call.args));
            if (found >= 0) call.result = remaining.splice(found, 1)[0].result;
          }
          const answer = result?.text ?? '';
          const verdict = error ? { pass: false, failedRules: ['backendError'], words: wordCount(answer) } : evaluate(scenario, calls, answer);
          const row = { time: new Date().toISOString(), simulated: backendFactory !== createBackend,
            role: role ?? 'actor',
            backend, model, tier, effectiveTier: backend === 'claude' ? null : tier,
            scenario: scenario.id, repeat: index, temperature: warm ? 'warm' : 'cold', ms,
            // Claude starts its internal clock after spawn; account for that startup outside send's clock.
            ttftMs: result?.ttftMs == null ? null : result.ttftMs + Math.max(0, ms - (result.ms ?? ms)),
            tokens: normalizeUsage(result?.usage, backend), usage: result?.usage ?? null,
            toolCalls: calls.length, backendToolCalls: result?.toolCalls ?? null, calls, input, answer,
            ...verdict, ...(error && { error }) };
          records.push(row);
          await appendFile(rawFile, `${JSON.stringify(row)}\n`);
          onProgress(row);
          if (error) { await session?.close(); session = null; warm = false; }
          else warm = true;
        }
      } finally { await session?.close(); }
    }
    await writeFile(reportFile, renderReport(records, basename(rawFile)));
    return { records, rawFile, reportFile };
  } finally {
    await body.close();
    await rm(workspace, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.help) console.log('node brain/bench/run.js --models backend:model,... [--role planner|actor] [--tiers default,priority] [--repeat 3] [--timeout-ms 120000] [--fake]');
    else {
      if (options.fake) options.backendFactory = (await import('./test/fake-backend.js')).fakeBackendFactory;
      const result = await runBench({ ...options, onProgress: row => process.stderr.write(`${row.backend}:${row.model}/${row.tier} ${row.scenario} #${row.repeat}: ${row.pass ? 'PASS' : row.failedRules.join(',')} ${row.ms}ms\n`) });
      console.log(`${result.reportFile}\n${result.rawFile}`);
    }
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
