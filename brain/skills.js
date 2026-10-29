// Skill scripts: the model writes a small async JavaScript program over the body's high-level tools
// (obtain, goto, use, menu_click, deposit, ...), runs it, and saves the ones that work as named,
// reusable skills. Movement, digging and fighting stay in the body's Java skills; a script only
// decides what to do in which order. Learned skills live in brain/logs/skills/ (one JSON each).
import vm from 'node:vm';
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { failed } from './session-log.js';
import { inventoryCounts } from './plans.js';

export const SCRIPT_BUDGET_MS = 8 * 60_000;
const SYNC_TIMEOUT_MS = 2_000;
const NAME = /^[a-z][a-z0-9_]{2,47}$/u;

export function skillsDir() {
  return process.env.BLOCKNET_SKILLS ?? resolve(dirname(fileURLToPath(import.meta.url)), 'logs/skills');
}

export class SkillError extends Error {}

/** Short API reference for prompts and tool descriptions; keep it in step with scriptApi. */
export const SCRIPT_API = [
  'Write the BODY of an async function (bot, args). await every bot call; each returns the tool\'s text report',
  'and throws SkillError (message = the report) when it failed, so try/catch handles fallbacks.',
  'bot.<tool>({...}) calls any body tool with its usual arguments: obtain {item, count} (count is a TOTAL), craft,',
  'goto {ref} or {x,y,z}, come, attack, pickup, place {item,x,y,z,face}, use {ref} (opens machines),',
  'menu {filter?, page?} (read the open menu; network terminals add stored contents), menu_click {menu_id, slot, button?, click?}, deposit/withdraw {ref, filter, count?},',
  'transfer {source, destination}, give {player, item, count?}, mine {x,y,z}, look, map {focus}, inv, status, wiki {query}.',
  'Hyphenated tools are camelCase (lookIn = look-in). Helpers: await bot.count(item) -> owned count,',
  'await bot.inventory() -> {item: count}, await bot.wait(seconds), bot.log(text). Return a short result string.',
  'No require/import, no timers, no infinite loops; a run is limited to 8 minutes.',
].join('\n');

const camel = name => name.replace(/-([a-z])/gu, (_, c) => c.toUpperCase());
const oneLine = text => String(text).replace(/\s+/gu, ' ').trim();

/** Parses the body's inventory report into {item: count}, with the plan critic's rules. */
export function parseInventory(text) {
  return inventoryCounts(String(text).replace(/^[+-]\s*/u, ''));
}

/**
 * Runs a script. {@code call(tool, args)} performs one body tool and resolves with its text.
 * Returns {ok, result, error, log, gained, lost, ms}; never throws.
 */
export async function runScript({ code, args = {}, call, tools = [], budgetMs = SCRIPT_BUDGET_MS }) {
  const started = Date.now(), log = [];
  let stopped = false, stop;
  const halted = new Promise((_, reject) => { stop = reason => { stopped = true; reject(new SkillError(reason)); }; });
  halted.catch(() => {});
  const act = async (tool, toolArgs = {}) => {
    // After a stop the script freezes at its next call: a catch-all loop can neither act nor spin.
    if (stopped) return new Promise(() => {});
    // Plain JSON crosses the sandbox boundary; objects from the script's realm never reach the bridge.
    toolArgs = JSON.parse(JSON.stringify(toolArgs ?? {}));
    const text = String(await call(tool, toolArgs));
    log.push(`${tool} ${JSON.stringify(toolArgs)} -> ${oneLine(text).slice(0, 160)}`);
    if (failed(text)) {
      // A player's stop ends the whole run, even if the script catches errors.
      if (/\b(?:cancelled|stopped)\b/iu.test(text)) stop(text);
      throw new SkillError(text);
    }
    return text;
  };
  const names = new Map(tools.map(t => [camel(t.name), t.name]));
  const api = {
    count: async item => (await api.inventory())[String(item).replace(/^minecraft:/u, '')] ?? 0,
    inventory: async () => parseInventory(await act('inv')),
    wait: seconds => new Promise(done => setTimeout(done, Math.max(0, Math.min(60, Number(seconds) || 0)) * 1000)),
    log: text => { log.push(`log: ${oneLine(text).slice(0, 200)}`); },
  };
  const bot = new Proxy(api, {
    get(target, key) {
      if (typeof key !== 'string') return undefined;
      if (key in target) return target[key];
      const tool = names.get(key) ?? (tools.length ? null : key);
      if (!tool) return () => Promise.reject(new SkillError(`unknown tool bot.${key}; see the API list`));
      return toolArgs => act(tool, toolArgs ?? {});
    },
  });
  let before = {};
  try { before = parseInventory(await call('inv', {})); } catch { /* inventory diff is best effort */ }
  let outcome;
  try {
    const context = vm.createContext({ __bot: bot, __args: structuredClone(args) }, { codeGeneration: { strings: false, wasm: false } });
    const script = new vm.Script(`(async (bot, args) => {\n${code}\n})(__bot, __args)`, { filename: 'skill.js' });
    const run = script.runInContext(context, { timeout: SYNC_TIMEOUT_MS });
    let timer;
    const budget = new Promise((_, reject) => { timer = setTimeout(() => reject(new SkillError(`script ran out of time (${Math.round(budgetMs / 1000)} s)`)), budgetMs); });
    try {
      const value = await Promise.race([run, budget, halted]);
      outcome = { ok: true, result: value == null ? 'done' : oneLine(typeof value === 'string' ? value : JSON.stringify(value)).slice(0, 500) };
    } finally { clearTimeout(timer); }
  } catch (error) {
    if (!stopped) stop(error.message);
    // Stop whatever the body was still doing for this script.
    if (/ran out of time/u.test(error.message)) await call('stop', {}).catch(() => {});
    outcome = { ok: false, error: oneLine(error.message).slice(0, 500) };
  }
  let after = before;
  try { after = parseInventory(await call('inv', {})); } catch { /* best effort */ }
  const gained = {}, lost = {};
  for (const item of new Set([...Object.keys(before), ...Object.keys(after)])) {
    const delta = (after[item] ?? 0) - (before[item] ?? 0);
    if (delta > 0) gained[item] = delta; else if (delta < 0) lost[item] = -delta;
  }
  return { ...outcome, log, gained, lost, ms: Date.now() - started };
}

/** One line per run for the model: outcome, inventory change and the last steps. */
export function describeRun(run) {
  const items = map => Object.entries(map).map(([k, v]) => `${v} ${k}`).join(', ');
  return [run.ok ? `ok: ${run.result}` : `failed: ${run.error}`,
    Object.keys(run.gained).length ? `gained ${items(run.gained)}` : '',
    Object.keys(run.lost).length ? `used ${items(run.lost)}` : '',
    `${Math.round(run.ms / 1000)} s`, `steps: ${run.log.slice(-8).join(' | ')}`].filter(Boolean).join('; ');
}

// ---- library ---------------------------------------------------------------------------------
export async function listSkills(query = '') {
  const dir = skillsDir();
  const files = await readdir(dir).catch(() => []);
  const words = String(query).toLowerCase().split(/\s+/u).filter(Boolean);
  const skills = [];
  for (const file of files.filter(f => f.endsWith('.json'))) {
    try {
      const skill = JSON.parse(await readFile(join(dir, file), 'utf8'));
      const text = `${skill.name} ${skill.description}`.toLowerCase();
      if (words.every(w => text.includes(w))) skills.push(skill);
    } catch { /* a broken file is skipped */ }
  }
  return skills.sort((a, b) => (b.successes ?? 0) - (a.successes ?? 0) || a.name.localeCompare(b.name));
}

export async function loadSkill(name) {
  if (!NAME.test(String(name))) throw new Error(`Invalid skill name: ${name}`);
  try { return JSON.parse(await readFile(join(skillsDir(), `${name}.json`), 'utf8')); }
  catch { throw new Error(`No skill named ${name}; call skills to list them`); }
}

/** Saves (or replaces) a skill after checking that the code compiles. */
export async function saveSkill({ name, description, code, params = {} }) {
  if (!NAME.test(String(name))) throw new Error('name: 3-48 chars, lowercase letters, digits, underscores, starting with a letter');
  if (typeof description !== 'string' || !description.trim() || description.length > 400) throw new Error('description: one sentence, <=400 chars');
  if (typeof code !== 'string' || !code.trim() || code.length > 20_000) throw new Error('code: the script body, <=20000 chars');
  if (typeof params !== 'object' || Array.isArray(params) || Object.values(params).some(v => typeof v !== 'string'))
    throw new Error('params: {name: "what it means"}');
  new vm.Script(`(async (bot, args) => {\n${code}\n})`); // throws on a syntax error
  const dir = skillsDir();
  await mkdir(dir, { recursive: true });
  let old = {};
  try { old = await loadSkill(name); } catch { /* new skill */ }
  const skill = { name, description: description.trim(), params, code, created: old.created ?? new Date().toISOString(),
    updated: new Date().toISOString(), runs: old.runs ?? 0, successes: old.successes ?? 0, lastError: old.lastError ?? null };
  await writeFile(join(dir, `${name}.json`), `${JSON.stringify(skill, null, 2)}\n`);
  return skill;
}

export async function recordRun(name, run) {
  try {
    const skill = await loadSkill(name);
    skill.runs = (skill.runs ?? 0) + 1;
    if (run.ok) skill.successes = (skill.successes ?? 0) + 1; else skill.lastError = run.error;
    await writeFile(join(skillsDir(), `${name}.json`), `${JSON.stringify(skill, null, 2)}\n`);
  } catch { /* statistics are best effort */ }
}

/** Brain-side MCP tools for the actor; the schemas follow the body tools' style. */
export const skillTools = [
  { name: 'run_script', description: `Run a skill script now (several tool calls in one go, with loops and fallbacks). ${SCRIPT_API}`,
    inputSchema: { type: 'object', properties: { code: { type: 'string' }, args: { type: 'object' } }, required: ['code'], additionalProperties: false } },
  { name: 'save_skill', description: 'Save a script that just worked as a named reusable skill (name: lowercase_with_underscores; params: {arg: meaning}). Saving the same name replaces it.',
    inputSchema: { type: 'object', properties: { name: { type: 'string' }, description: { type: 'string' }, code: { type: 'string' },
      params: { type: 'object' } }, required: ['name', 'description', 'code'], additionalProperties: false } },
  { name: 'skill', description: 'Run a saved skill by name with its args. Use skills first to find one.',
    inputSchema: { type: 'object', properties: { name: { type: 'string' }, args: { type: 'object' } }, required: ['name'], additionalProperties: false } },
  { name: 'skills', description: 'List saved skills (name, what it does, params, success count); optional query words filter them.',
    inputSchema: { type: 'object', properties: { query: { type: 'string' } }, additionalProperties: false } },
];

/** Handles one skill tool call; returns the text for the model. */
export async function handleSkillTool(name, args, { call, tools }) {
  if (name === 'skills') {
    const found = await listSkills(args.query ?? '');
    if (!found.length) return args.query ? `no saved skill matches "${args.query}"` : 'no saved skills yet';
    return found.slice(0, 20).map(s => `${s.name}(${Object.entries(s.params ?? {}).map(([k, v]) => `${k}: ${v}`).join(', ')}): ${s.description}`
      + ` [${s.successes ?? 0}/${s.runs ?? 0} ok]`).join('\n');
  }
  if (name === 'save_skill') {
    const skill = await saveSkill(args);
    return `saved skill ${skill.name}`;
  }
  if (name === 'run_script') {
    if (typeof args.code !== 'string' || !args.code.trim()) throw new Error('code required');
    return describeRun(await runScript({ code: args.code, args: args.args ?? {}, call, tools }));
  }
  if (name === 'skill') {
    const skill = await loadSkill(args.name);
    const run = await runScript({ code: skill.code, args: args.args ?? {}, call, tools });
    await recordRun(skill.name, run);
    return `${skill.name}: ${describeRun(run)}`;
  }
  throw new Error(`Unknown skill tool ${name}`);
}
