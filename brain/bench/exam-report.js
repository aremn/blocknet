// The exam's score from a GameTest log (mod ExamGameTests): one line per order with the reason it
// failed, and the pass rate. --record appends the result to docs/exam-history.jsonl, so progress
// (or a regression) shows run over run.
//   node brain/bench/exam-report.js build/gametest.log [--record]
import { appendFileSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function examResults(log) {
  const results = new Map();
  for (const line of log.split(/\r?\n/u)) {
    const graded = /EXAM (\S+) (PASS|FAIL) in (\d+) ticks: (.*)$/u.exec(line);
    if (graded) { results.set(graded[1], { id: graded[1], pass: graded[2] === 'PASS', ticks: Number(graded[3]), note: graded[4] }); continue; }
    // A crash or a GameTest timeout fails a test without the examiner's line.
    const crashed = /(exam\d+\w*) failed at [^!]*! (.*)$/u.exec(line);
    if (crashed && ![...results.values()].some(r => crashed[2].startsWith(`${r.id}:`))) {
      results.set(crashed[1], { id: crashed[1], pass: false, ticks: null, note: crashed[2] });
    }
  }
  return [...results.values()].sort((a, b) => a.id.localeCompare(b.id));
}

export function examTable(results) {
  const passed = results.filter(r => r.pass).length;
  const rows = results.map(r => `| ${r.pass ? 'PASS' : 'FAIL'} | ${r.id} | ${r.ticks == null ? '-' : `${(r.ticks / 20).toFixed(0)} s`} | ${r.note.replace(/\|/gu, '/').slice(0, 160)} |`);
  return [`Exam: ${passed}/${results.length} passed (${results.length ? Math.round(100 * passed / results.length) : 0}%)`, '',
    '| result | order | game time | report |', '|---|---|---|---|', ...rows].join('\n');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const file = process.argv[2] ?? 'build/gametest.log';
  const results = examResults(readFileSync(file, 'utf8'));
  if (!results.length) { console.error(`no exam results in ${file}`); process.exit(1); }
  console.log(examTable(results));
  if (process.argv.includes('--record')) {
    const history = resolve(dirname(fileURLToPath(import.meta.url)), '../../docs/exam-history.jsonl');
    appendFileSync(history, `${JSON.stringify({ time: new Date().toISOString(), passed: results.filter(r => r.pass).length, total: results.length,
      failed: results.filter(r => !r.pass).map(r => r.id) })}\n`);
    console.log(`\nrecorded in ${history}`);
  }
}
