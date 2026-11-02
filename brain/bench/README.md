# Exam report

`ExamGameTests` (optional GameTests, one job each) log `EXAM <id> PASS|FAIL`. Summarize a run:

```sh
node brain/bench/exam-report.js build/gametest.log [--record]
```

The model bench of planner/actor roles and its fake body's tool registry (`tools.json`,
`sync-tools.js`) were removed with ADR-0016 (tag `before-worker`).
