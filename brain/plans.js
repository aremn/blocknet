import { appendFile, mkdir, readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { failed, oneLine } from './session-log.js';

export const toolCatalog = JSON.parse(await readFile(new URL('./bench/tools.json', import.meta.url), 'utf8'));
export const plannerPrompt = await readFile(new URL('./planner-prompt.md', import.meta.url), 'utf8');
const itemId = id => id.replace(/^minecraft:/u, '');

export function parseRole(spec) {
  const match = /^(claude|codex):([^:\s]+)(?::([^:\s]+))?$/u.exec(spec ?? '');
  if (!match) throw new Error('Use backend:model[:tier|effort] for --planner/--actor');
  const [, backend, model, modifier] = match;
  if (modifier && !['default', 'priority', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'].includes(modifier))
    throw new Error(`Unknown tier/effort: ${modifier}`);
  if (backend === 'claude' && ['default', 'priority'].includes(modifier)) throw new Error('Claude accepts effort, not service tier');
  return { backend, model, ...(modifier && (['default', 'priority'].includes(modifier)
    ? { serviceTier: modifier } : { effort: modifier })) };
}

/** Parse only registered tool commands. Natural-language tasks return null. */
export function parseTool(text, tools = toolCatalog) {
  const match = /^(\S+)(?:\s+([\s\S]*))?$/u.exec(text.trim());
  const tool = tools.find(t => t.name === match?.[1]);
  if (!tool) return null;
  const schema = tool.params ?? tool.inputSchema;
  const rest = match[2]?.trim() ?? '';
  let args = {};
  if (tool.name === 'wiki' && !rest.startsWith('{')) args = { query: rest };
  else if (rest.startsWith('{')) args = JSON.parse(rest);
  else {
    const values = rest ? rest.split(/\s+/u) : [];
    let keys = Object.keys(schema.properties);
    // Ref alternatives precede coordinates in the registry; positional coordinates use x/y/z.
    if (schema.oneOf || tool.name === 'goto') keys = /^-?\d+$/u.test(values[0] ?? '') ? keys.filter(k => k !== 'ref') : ['ref'];
    if (tool.name === 'gather') keys = ['item', 'count'];
    if (values.length > keys.length) throw new Error(`Too many arguments: ${text}`);
    args = Object.fromEntries(values.map((v, i) => {
      const type = schema.properties[keys[i]].type;
      return [keys[i], type === 'integer' ? Number(v) : type === 'boolean' && ['true', 'false'].includes(v) ? v === 'true' : v];
    }));
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error(`Invalid arguments: ${text}`);
  for (const key of schema.required ?? []) if (!(key in args)) throw new Error(`Missing ${key}: ${text}`);
  for (const [key, value] of Object.entries(args)) {
    const p = schema.properties[key];
    if (!p || (p.type === 'integer' ? !Number.isSafeInteger(value) : typeof value !== p.type)
      || p.minimum != null && value < p.minimum || p.maximum != null && value > p.maximum
      || p.enum && !p.enum.includes(value)) throw new Error(`Invalid ${key}: ${text}`);
  }
  if (schema.oneOf && schema.oneOf.filter(c => c.required.every(k => k in args)).length !== 1)
    throw new Error(`Expected ref OR coordinates: ${text}`);
  if (tool.name === 'goto' && !(typeof args.ref === 'string' && Object.keys(args).length === 1
      || ['x', 'y', 'z'].every(k => Number.isSafeInteger(args[k])) && !('ref' in args)))
    throw new Error(`Expected ref OR coordinates: ${text}`);
  if ('count' in args && (args.count <= 0 || args.count > 1_000_000)) throw new Error(`Invalid count: ${text}`);
  return { tool: tool.name, args };
}

export function parseCondition(text) {
  let m = /^inv ([\w:./-]+) >= ([1-9]\d*)$/u.exec(text);
  if (m && Number.isSafeInteger(+m[2])) return { type: 'inv', item: itemId(m[1]), count: +m[2] };
  m = /^pos (-?\d+) (-?\d+) (-?\d+)(?: within (\d+(?:\.\d+)?))?$/u.exec(text);
  if (m && m.slice(1, 4).every(n => Number.isSafeInteger(+n)))
    return { type: 'pos', position: m.slice(1, 4).map(Number), radius: +(m[4] ?? 0) };
  throw new Error(`Uncheckable done: ${text}`);
}

export function validatePlan(value, tools = toolCatalog) {
  const plan = typeof value === 'string' ? JSON.parse(value.trim()) : value;
  const budget = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 120;
  if (!plan || typeof plan.goal !== 'string' || !plan.goal.trim() || !budget(plan.budget_min)
      || !Array.isArray(plan.steps) || !plan.steps.length || plan.steps.length > 32) throw new Error('Invalid plan goal/steps/budget_min');
  plan.steps.forEach((step, index) => {
    if (!step || step.id !== index + 1 || typeof step.do !== 'string' || !step.do.trim() || step.do.length > 1000
      || typeof step.done !== 'string' || step.budget_min != null && !budget(step.budget_min)) throw new Error(`Invalid step ${index + 1}`);
    parseCondition(step.done); parseTool(step.do, tools);
  });
  return structuredClone(plan);
}

/** Read full inv snapshots, never double-count the main-hand echo or summary. */
export function inventoryCounts(text) {
  const counts = {};
  for (const line of String(text).replace(/^inventory r\d+:\s*/u, '').split(/\n|;\s*|,\s*(?=\d+ [\w:./-]+)/u)) {
    if (/^(?:main:|selected |summary:|free slots)/u.test(line.trim())) continue;
    const m = /^(?:(?:off|head|chest|legs|feet):\s*)?(?:(\d+) )?([\w:./-]+)(?:\s|$)/u.exec(line.trim());
    if (!m || !m[1] && !/ slots \[|^(?:off|head|chest|legs|feet):/u.test(line.trim())) continue;
    const key = itemId(m[2]); counts[key] = (counts[key] ?? 0) + +(m[1] ?? 1);
  }
  return counts;
}

export function checkDone(condition, state) {
  const c = typeof condition === 'string' ? parseCondition(condition) : condition;
  if (c.type === 'inv') {
    const count = state.inventory[c.item] ?? 0;
    return { pass: count >= c.count, progress: `${count}/${c.count}` };
  }
  const distance = state.position ? Math.hypot(...state.position.map((v, i) => v - c.position[i])) : Infinity;
  return { pass: distance <= c.radius, progress: Number.isFinite(distance) ? `${Math.round(distance)}m` : 'position unknown' };
}

export function goalKey(goal) {
  return oneLine(goal.toLowerCase().replace(/[^\p{L}\p{N}_:]+/gu, ' '))
    .split(' ').filter(w => !['пожалуйста', 'сделай', 'мне', 'please', 'make', 'me', 'a', 'the'].includes(w)).sort().join(' ');
}
function similarity(a, b) {
  if (a === b) return 1;
  // Counts are significant: never reuse a plan for a different requested quantity.
  if ((a.match(/\b\d+\b/gu) ?? []).join() !== (b.match(/\b\d+\b/gu) ?? []).join()) return 0;
  const aa = new Set(a.split(' ')), bb = new Set(b.split(' '));
  return [...aa].filter(w => bb.has(w)).length / new Set([...aa, ...bb]).size;
}

export class PlanLibrary {
  constructor(file) { this.file = file; this.entries = []; }
  async matches(goal) {
    if (this.file) {
      const text = await readFile(this.file, 'utf8').catch(e => { if (e.code === 'ENOENT') return ''; throw e; });
      this.entries = text.split('\n').filter(Boolean).flatMap(line => {
        try { const p = JSON.parse(line); validatePlan(p); return [p]; } catch { return []; }
      });
    }
    return this.entries.map(plan => ({ plan, score: similarity(goalKey(goal), goalKey(plan.goal)) }))
      .sort((a, b) => b.score - a.score).slice(0, 2);
  }
  async save(goal, plan, duration_ms) {
    const entry = { ...plan, goal, goal_key: goalKey(goal), duration_ms };
    if (this.file) {
      await mkdir(dirname(this.file), { recursive: true });
      await appendFile(this.file, `${JSON.stringify(entry)}\n`);
    }
    this.entries.push(entry);
  }
}

export function plannerInput({ goal, state, scene, failures = [], hints = [], examples = [], tools = toolCatalog }) {
  return `Goal: ${goal}\nState: ${oneLine(state)}\nScene: ${oneLine(scene)}\nPrevious failures: ${JSON.stringify(failures)}\n`
    + `Player hints for this plan: ${JSON.stringify(hints)}\nMatching successful plans (examples): ${JSON.stringify(examples)}\nAvailable skills/tools (positional argument order; ? = optional):\n`
    + tools.map(t => `${t.name} ${Object.keys((t.params ?? t.inputSchema).properties).map(k => k + ((t.params ?? t.inputSchema).required?.includes(k) ? '' : '?')).join(' ')}: ${oneLine(t.description)}`).join('\n');
}

/** Small routing heuristic; ordinary conversation and single registered calls stay cheap. */
export function needsPlan(text, tools = toolCatalog) {
  try { if (parseTool(text, tools)) return false; }
  catch { return true; } // e.g. "craft an iron pickaxe from scratch" is a goal, not CLI syntax.
  return /(?:сдела[йт]|постро[йи]|добуд|найди|получи|хочу|нуж[ен]|кирк|дом|с нуля|до ночи|\b(?:build|make|obtain|find|get|need|from scratch)\b)/iu.test(text);
}

export class PlanRunner {
  constructor({ call, planner, actor, resetActor, tools = toolCatalog, library = new PlanLibrary(), say,
    log = () => {}, actorPrefix = () => '', isActive = () => true }) {
    Object.assign(this, { call, planner, actor, resetActor, tools, library, say, log, actorPrefix, isActive });
    this.state = { inventory: {} }; this.label = 'нет';
  }
  cancel() { this.controller?.abort(); this.label = 'прерван'; }
  interact(work) {
    const controller = this.controller;
    const pending = (this.interactions ?? Promise.resolve()).then(async () => {
      await this.ready;
      if (controller !== this.controller || controller.signal.aborted || !this.running) return;
      return work();
    });
    this.interactions = pending.catch(error => this.log('plan_chat_error', { error: error.message }));
    return pending;
  }
  async waitInteractions() {
    let pending;
    do { pending = this.interactions; await pending; } while (pending !== this.interactions);
  }
  async context() {
    await this.snapshot(); this.assertActive();
    const scene = this.tools.some(t => t.name === 'scene')
      ? (await this.call('scene', { delta: false })).result : 'unknown';
    this.assertActive();
    return `Goal: ${this.plan.goal}\nCurrent plan: ${JSON.stringify(this.plan)}\nCurrent step: ${JSON.stringify(this.step)}\nState: ${this.stateLine}\nScene: ${scene}\nPlayer hints: ${JSON.stringify(this.hints)}\n${this.status()}`;
  }
  async hint(text, player) {
    this.hints.push(text);
    const context = await this.context();
    const result = await this.bounded(() => this.planner(plannerInput({ goal: this.plan.goal,
      state: this.stateLine, scene: 'see current context below', hints: this.hints, tools: this.tools })
      + `\n${context}\nPlayer hint: ${JSON.stringify(text)}\nHint review: return {"action":"keep","reply":"optional short reply"} or a revised plan starting at the current step (ids start at 1).`), 120_000);
    this.assertActive();
    const value = JSON.parse(result.text);
    if (value.action === 'keep') {
      if (value.reply) await this.say(oneLine(value.reply).split(' ').slice(0, 12).join(' ').slice(0, 200), player);
      return;
    }
    const revised = validatePlan(value, this.tools);
    const next = revised.steps[0], previous = this.step;
    const command = step => {
      const tool = parseTool(step.do, this.tools);
      return tool ? JSON.stringify([tool.tool, Object.entries(tool.args).sort(([a], [b]) => a.localeCompare(b))]) : step.do.trim();
    };
    const changed = command(next) !== command(previous)
      || JSON.stringify(parseCondition(next.done)) !== JSON.stringify(parseCondition(previous.done));
    this.plan.steps.splice(this.index, Infinity, ...revised.steps.map((s, i) => ({ ...s, id: this.index + i + 1 })));
    this.log('plan_revised', { plan: this.plan, hint: text, changed });
    if (changed) {
      this.step = this.plan.steps[this.index];
      this.resetActor?.();
      await this.call('stop'); this.assertActive();
      this.stepController?.abort(new Error('Plan revised'));
    }
  }
  status() {
    if (!this.plan || !this.step) return `план: ${this.label}`;
    return `план: шаг ${this.step.id}/${this.plan.steps.length} ${oneLine(this.step.do)} — ${checkDone(this.step.done, this.state).progress}`;
  }
  async snapshot() {
    const inv = (await this.call('inv')).result;
    const status = (await this.call('status')).result;
    const position = /\bpos (-?\d+),?\s+(-?\d+),?\s+(-?\d+)/u.exec(status ?? '');
    this.state = { inventory: inventoryCounts(inv), position: position?.slice(1).map(Number) };
    this.stateLine = `${oneLine(status).slice(0, 300)}; inventory: ${Object.entries(this.state.inventory).map(([id, count]) => `${count} ${id}`).join(', ') || 'empty'}`;
  }
  assertActive() {
    if (this.controller.signal.aborted || !this.isActive()) throw new Error('Plan cancelled');
  }
  async bounded(work, ms, stepSignal) {
    this.assertActive();
    let timer, abort;
    try {
      return await Promise.race([Promise.resolve().then(() => { this.assertActive(); return work(); }), new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('step budget exceeded')), ms);
        abort = () => reject(new Error('Plan cancelled'));
        this.controller.signal.addEventListener('abort', abort, { once: true });
        stepSignal?.addEventListener('abort', abort, { once: true });
      })]);
    } finally { clearTimeout(timer); this.controller.signal.removeEventListener('abort', abort); stepSignal?.removeEventListener('abort', abort); }
  }
  async makePlan(goal, failures, scene) {
    const matches = await this.library.matches(goal);
    this.assertActive();
    if (!failures.length && !this.hints.length && matches[0]?.score >= 0.8) {
      this.log('plan_reused', { goal });
      return validatePlan({ ...matches[0].plan, goal }, this.tools);
    }
    const input = plannerInput({ goal, state: this.stateLine, scene, failures,
      examples: matches.map(m => m.plan), hints: this.hints, tools: this.tools });
    let invalid = '';
    for (let attempt = 0; attempt < 2; attempt++) {
      const result = await this.bounded(() => this.planner(input + invalid), 120_000);
      this.assertActive();
      try { return validatePlan(result.text, this.tools); }
      catch (error) { invalid = `\nInvalid output: ${error.message}. Return corrected JSON only.`; }
    }
    throw new Error(`planner returned invalid JSON twice:${invalid}`);
  }
  async run(goal, player) {
    this.controller = new AbortController(); this.plan = null; this.step = null; this.label = 'составляю';
    this.hints = []; this.interactions = Promise.resolve(); this.running = true;
    let ready;
    this.ready = new Promise(resolve => { ready = resolve; });
    const started = Date.now(), failures = [], completed = [];
    try {
      await this.snapshot();
      let scene = 'unknown'; // BrainSession reads one cheap scene alongside planner request preparation.
      this.plan = await this.makePlan(goal, failures, scene);
      this.step = this.plan.steps[0]; ready();
      const deadline = started + this.plan.budget_min * 60_000;
      while (true) {
        this.log('plan', { plan: this.plan, failures });
        let reason;
        for (this.index = 0; this.index < this.plan.steps.length; this.index++) {
          this.step = this.plan.steps[this.index];
          await this.waitInteractions();
          const step = this.plan.steps[this.index];
          this.assertActive(); this.step = step;
          const stepController = this.stepController = new AbortController();
          this.resetActor?.();
          const budgetMs = Math.min(deadline - Date.now(), (step.budget_min ?? this.plan.budget_min / this.plan.steps.length) * 60_000);
          const stepDeadline = Date.now() + budgetMs;
          try {
            if (budgetMs <= 0) throw new Error('plan budget exceeded');
            if (!checkDone(step.done, this.state).pass) {
              const direct = parseTool(step.do, this.tools);
              const result = await this.bounded(async () => {
                if (direct) {
                  const response = await this.call(direct.tool, direct.args);
                  return response.outcome ? await response.outcome : response.result;
                }
                scene = this.tools.some(t => t.name === 'scene') ? (await this.call('scene', { delta: true, goal })).result : scene;
                this.assertActive();
                if (stepController.signal.aborted) throw new Error('Plan revised');
                if (Date.now() > stepDeadline) throw new Error('step budget exceeded');
                return (await this.actor(`${this.actorPrefix()}Goal: ${goal}\nStep: ${step.do}\nDone: ${step.done}\nState: ${oneLine(this.stateLine)}\nScene: ${oneLine(scene).slice(0, 600)}\n${this.status()}`)).text;
              }, budgetMs, stepController.signal);
              await this.waitInteractions();
              this.assertActive();
              if (stepController.signal.aborted) throw new Error('Plan revised');
              if (failed(result) || /^(?:error|unsupported|needs_plan)\b/iu.test(String(result))) throw new Error(String(result));
              await this.snapshot(); await this.waitInteractions(); this.assertActive();
              if (stepController.signal.aborted) throw new Error('Plan revised');
              if (Date.now() > stepDeadline) throw new Error('step budget exceeded');
              const verdict = checkDone(step.done, this.state);
              if (!verdict.pass) throw new Error(`${step.done}: ${verdict.progress}; no verified completion`);
            }
            completed.push(step);
            this.log('plan_step', { step, status: this.status(), pass: true });
          } catch (error) {
            await this.waitInteractions();
            this.assertActive();
            if (stepController.signal.aborted) { this.index--; continue; }
            reason = `step ${step.id} (${step.do}): ${error.message}`;
            if (/budget exceeded/u.test(error.message)) { this.resetActor?.(); await this.call('stop'); }
            break;
          }
        }
        if (!reason) {
          this.running = false;
          const steps = completed.map((s, i) => ({ ...s, id: i + 1 }));
          // Library entries obey the same plan bounds as fresh planner output.
          if (steps.length <= 32) await this.library.save(goal, { ...this.plan, steps }, Date.now() - started);
          this.step = null; this.label = 'выполнен';
          await this.say('Готово.', player);
          return true;
        }
        failures.push(reason); this.log('plan_failure', { reason, failures: failures.length });
        if (failures.length >= 3) throw new Error(reason);
        await this.waitInteractions(); this.assertActive();
        this.ready = new Promise(resolve => { ready = resolve; });
        await this.snapshot(); this.assertActive();
        this.plan = await this.makePlan(goal, failures, scene);
        this.step = this.plan.steps[0]; ready();
      }
    } catch (error) {
      this.step = null;
      if (this.controller.signal.aborted || !this.isActive()) return null;
      this.label = 'не вышло';
      await this.say(`Не получилось: ${oneLine(error.message).slice(0, 150)}`, player);
      return false;
    } finally { this.running = false; ready(); }
  }
}
