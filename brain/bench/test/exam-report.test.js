import { test } from 'node:test';
import assert from 'node:assert/strict';
import { examResults, examTable } from '../exam-report.js';

test('exam report grades each order once, including crashes and timeouts without an examiner line', () => {
  const log = [
    '[11:00:01] [Server thread/INFO] [de.bl.Blocknet/]: EXAM bed_from_sheep PASS in 1040 ticks: obtained 1 white_bed: hunted sheep',
    '[11:00:02] [Server thread/INFO] [de.bl.Blocknet/]: EXAM follow_across_river FAIL in 300 ticks: died: drown',
    '[11:00:02] [Server thread/ERROR] [minecraft/LogTestReporter]: exam10followacrossriver failed at 1, 2, 3! follow_across_river: died: drown',
    '[11:00:03] [Server thread/ERROR] [minecraft/LogTestReporter]: exam16ironpickaxefromnothing failed at 1, 2, 3! Didn\'t succeed or fail within 24000 ticks',
  ].join('\n');
  const results = examResults(log);
  assert.deepEqual(results.map(r => [r.id, r.pass]), [['bed_from_sheep', true], ['exam16ironpickaxefromnothing', false], ['follow_across_river', false]]);
  const table = examTable(results);
  assert.match(table, /^Exam: 1\/3 passed \(33%\)/u);
  assert.match(table, /\| FAIL \| follow_across_river \| 15 s \| died: drown \|/u);
});
