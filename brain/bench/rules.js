import { validatePlan, parseTool } from '../plans.js';

export const wordCount = text => {
  const value = String(text ?? '').trim();
  return !value || value === '<silent>' ? 0 : value.split(/\s+/u).length;
};
const success = call => typeof call.result === 'string' && !/^(failed:|error:)/i.test(call.result);
const itemIs = (call, item) => call.args?.item?.replace(/^minecraft:/, '') === item;
const gather = (call, pattern) => call.tool === 'gather' && new RegExp(pattern, 'i').test(call.args?.item ?? '') && success(call);

export function evaluate(scenario, calls, answer) {
  const words = wordCount(answer);
  const failures = [];
  for (const rule of scenario.rules) {
    let passed;
    switch (rule.type) {
      case 'planQuality': {
        try {
          const plan = validatePlan(answer);
            if (rule.target === 'shelter') {
              passed = plan.status === 'blocked' && /blueprint|shelter|building|постро|укрыт/iu.test(plan.reason ?? '') && calls.length === 0;
              break;
            }
          const steps = plan.steps.map(s => ({ ...s, call: parseTool(s.do) }));
          const indexOf = (tool, item) => steps.findIndex(s => s.call?.tool === tool && itemIs(s.call, item));
          const acquisition = steps.some(s => ['obtain', 'mine_ore'].includes(s.call?.tool));
          const onlyKnowledge = calls.every(c => c.tool === 'knowledge' && success(c));
          if (rule.target === 'iron_pickaxe') {
            const stone = indexOf('obtain', 'stone_pickaxe'), iron = indexOf('obtain', 'iron_ingot');
            const craft = indexOf('craft', 'iron_pickaxe');
            const mined = indexOf('mine_ore', 'raw_iron');
            passed = acquisition && onlyKnowledge && stone >= 0 && iron > stone && craft > iron
              && (mined < 0 || mined > stone && mined < iron)
              && steps[craft].done.replace('minecraft:', '') === 'inv iron_pickaxe >= 1';
          } else {
            const supplies = steps.findIndex(s => ['obtain', 'mine_ore'].includes(s.call?.tool));
            const build = steps.findIndex(s => !s.call && /shelter|walls|roof|дом|стен|крыш/iu.test(s.do));
            passed = acquisition && onlyKnowledge && supplies >= 0 && build > supplies
              && steps.slice(0, build).some(s => s.done.startsWith('inv '))
              && steps[build].done.startsWith('pos ');
          }
        } catch { passed = false; }
        break;
      }
      case 'singleStep': passed = calls.length === 1 && calls[0].tool === rule.tool && itemIs(calls[0], rule.item)
        && (calls[0].args.count ?? 1) === rule.count && success(calls[0]); break;
      case 'gatherWithin': passed = calls.slice(0, rule.limit).some(c => gather(c, rule.pattern)); break;
      case 'craft': passed = calls.some(c => c.tool === 'craft' && itemIs(c, rule.item) && success(c)); break;
      case 'short': passed = words > 0 && words <= rule.maxWords; break;
      case 'calls': passed = calls.length <= rule.max && (!rule.allowed || calls.every(c => rule.allowed.includes(c.tool))); break;
      case 'mentions': passed = rule.patterns.every(pattern => new RegExp(pattern, 'iu').test(answer)); break;
      case 'silent': passed = words === 0; break;
      case 'observeExplore': {
        const seen = calls.findIndex(c => ['look', 'scene'].includes(c.tool) && success(c));
        passed = seen >= 0 && calls.some((c, i) => i > seen && c.tool === 'explore' && success(c))
          && !calls.slice(0, seen + 1).some(c => c.tool === 'goto');
        break;
      }
      case 'gatherBeforeCraft': {
        const mined = calls.findIndex(c => gather(c, rule.pattern));
        const crafted = calls.findIndex(c => c.tool === 'craft' && itemIs(c, rule.item));
        passed = mined >= 0 && crafted > mined && success(calls[crafted]);
        break;
      }
      case 'dangerFirst': {
        const action = calls.find(c => !['look', 'scene', 'inv', 'status', 'changes', 'tool_feedback'].includes(c.tool));
        passed = !!action && success(action) && (action.tool === 'goto' && scenario.safeRefs.includes(action.args.ref)
          || action.tool === 'place');
        break;
      }
      case 'observedTargets': {
        let evidence = scenario.scene;
        passed = calls.every(call => {
          const args = call.args ?? {};
          const coordinates = ['x', 'y', 'z'].some(key => key in args);
          const ref = args.ref ?? (call.tool === 'gather' && /^[a-z]\d+$/.test(args.item ?? '') ? args.item : null);
          const valid = (!coordinates || (scenario.coordinates ?? []).some(pos => pos.every((v, i) => v === args[['x', 'y', 'z'][i]])))
            && (!ref || new RegExp(`\\b${ref.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(evidence));
          if (success(call)) evidence += `\n${call.result}`;
          return valid;
        });
        break;
      }
      default: throw new Error(`Unknown rule: ${rule.type}`);
    }
    if (!passed) failures.push(rule.type);
  }
  return { pass: failures.length === 0, failedRules: failures, words };
}
