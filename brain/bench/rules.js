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
