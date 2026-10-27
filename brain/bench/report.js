export const p50 = values => {
  const sorted = values.filter(v => v != null && Number.isFinite(v)).sort((a, b) => a - b);
  if (!sorted.length) return null;
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};
const mean = values => {
  const known = values.filter(v => v != null && Number.isFinite(v));
  return known.length ? known.reduce((a, b) => a + b, 0) / known.length : null;
};
const fmt = value => value == null ? 'n/a' : String(Math.round(value * 10) / 10);
const rate = rows => rows.filter(r => r.pass).length / rows.length;
const key = row => `${row.backend}:${row.model} / ${row.tier}${row.backend === 'claude' ? ' (tier n/a)' : ''}`;
const tokenMean = rows => mean(rows.map(r => r.tokens.input == null || r.tokens.output == null ? null : r.tokens.input + r.tokens.output));

export function renderReport(records, rawName) {
  const groups = [...Map.groupBy(records, key)].map(([name, rows]) => ({ name, rows,
    pass: rate(rows), warm: p50(rows.filter(r => r.temperature === 'warm' && !r.error).map(r => r.ms)), tokens: tokenMean(rows) }));
  const lines = ['# Blocknet model benchmark', '',
    records.some(r => r.simulated) ? '**FAKE BACKENDS: test data only; no model quality or latency was measured.**' : 'Backend: real model processes against a scripted body.', '',
    `Raw data: [${rawName}](${rawName})`, '',
    'Each scenario has its own session. Repeat 1 is cold; subsequent repeats reuse its conversation but reset the body. Cold total/TTFT include startup. TTFT means first text token, not first tool call. Latency percentiles exclude errors; pass rates include them. Tokens are mean per turn; input includes cached tokens. Missing metrics are n/a.', '',
    'Claude has no tier control: requested tier groups are independent repeated samples with the same Claude configuration. Warm results include conversation/history effects. Scripted body actions finish immediately, so these timings exclude Minecraft action time. Rules are deterministic heuristics, not a human assessment of answer quality.', '',
    '## Ranking', '', '| Rank | Model / requested tier | Pass | Warm p50 ms | Mean input + output tokens |', '| --- | --- | --- | --- | --- |'];
  groups.sort((a, b) => b.pass - a.pass || (a.warm ?? Infinity) - (b.warm ?? Infinity) || (a.tokens ?? Infinity) - (b.tokens ?? Infinity));
  for (const [i, group] of groups.entries()) lines.push(`| ${i + 1} | ${group.name} | ${fmt(group.pass * 100)}% | ${fmt(group.warm)} | ${fmt(group.tokens)} |`);
  for (const group of groups) {
    lines.push('', `## ${group.name}`, '', '| Session | N | p50 total ms | p50 first text ms | Mean input | Mean cached | Mean output | Pass | Mean words |', '| --- | --- | --- | --- | --- | --- | --- | --- | --- |');
    for (const temperature of ['cold', 'warm']) {
      const rows = group.rows.filter(r => r.temperature === temperature);
      const ok = rows.filter(r => !r.error);
      lines.push(`| ${temperature} | ${rows.length} | ${fmt(p50(ok.map(r => r.ms)))} | ${fmt(p50(ok.map(r => r.ttftMs)))} | ${fmt(mean(rows.map(r => r.tokens.input)))} | ${fmt(mean(rows.map(r => r.tokens.cached)))} | ${fmt(mean(rows.map(r => r.tokens.output)))} | ${rows.length ? `${fmt(rate(rows) * 100)}%` : 'n/a'} | ${fmt(mean(rows.map(r => r.words)))} |`);
    }
    const failures = group.rows.filter(r => !r.pass);
    if (failures.length) {
      lines.push('', 'Failed rules:');
      for (const row of failures) lines.push(`- ${row.scenario}, repeat ${row.repeat}: ${row.failedRules.join(', ')}${row.error ? ` (${row.error.replace(/[\r\n|`]/g, ' ')})` : ''}`);
    }
  }
  return `${lines.join('\n')}\n`;
}
