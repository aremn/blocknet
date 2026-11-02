// Does the translator's Claude CLI load anything but its prompt? (ADR-0017, after GregTech Arena's
// `arena check-isolation`.) One tiny turn, then the CLI's own init report is checked.
//   node brain/check-isolation.js [--model claude:haiku]
import { writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { option } from './body.js';

const repoDir = dirname(dirname(fileURLToPath(import.meta.url)));
/** Built in, always on, and harmless: it sends nothing to the model. */
const ALLOWED_PLUGINS = new Set(['cc-plugin-telemetry']);

/** What the init report shows beyond the prompt; empty means isolated. */
export function isolationProblems(init, repo = repoDir) {
  if (!init) return ['the CLI sent no init report'];
  const problems = [];
  const list = (what, value) => { if (value?.length) problems.push(`${what}: ${value.map(v => v.name ?? v).join(', ')}`); };
  list('tools', init.tools);
  list('MCP servers', init.mcp_servers);
  list('skills', init.skills);
  list('slash commands', init.slash_commands);
  list('plugins', (init.plugins ?? []).filter(p => !ALLOWED_PLUGINS.has(p.name)));
  if (init.memory_paths && Object.keys(init.memory_paths).length) problems.push(`memory: ${JSON.stringify(init.memory_paths)}`);
  const path = init.cwd && relative(resolve(repo), resolve(init.cwd));
  if (init.cwd && !path.startsWith('..') && !isAbsolute(path)) problems.push(`working folder inside the repo: ${init.cwd}`);
  return problems;
}

export async function main(argv = process.argv.slice(2)) {
  const { createBackend, parseModel } = await import('./brain.js');
  const spec = parseModel(option('model', 'claude:haiku', argv));
  if (spec.backend !== 'claude') throw new Error('only the claude backend reports what it loaded; codex is isolated by its -c flags');
  const promptFile = join(tmpdir(), 'blocknet-isolation-prompt.md');
  writeFileSync(promptFile, 'Reply with exactly one word: ok');
  const backend = createBackend('claude', { ...spec, promptFile, maxTurns: 1, timeoutMs: 120_000 });
  try {
    const result = await backend.send('ok?');
    const problems = isolationProblems(backend.init);
    const tokens = (result.usage?.input_tokens ?? 0) + (result.usage?.cache_creation_input_tokens ?? 0) + (result.usage?.cache_read_input_tokens ?? 0);
    process.stdout.write(`${problems.length ? `ISOLATION FAIL\n- ${problems.join('\n- ')}` : 'ISOLATION PASS'}\n`
      + `model ${backend.init?.model}, cwd ${backend.init?.cwd}, input tokens for a one-line prompt: ${tokens}\n`);
    return problems.length ? 1 : 0;
  } finally { backend.close(); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().then(code => process.exit(code), error => { process.stderr.write(`${error.message}\n`); process.exit(2); });
}
