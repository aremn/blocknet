# Exam report and tool registry

`ExamGameTests` (optional GameTests, one job each) log `EXAM <id> PASS|FAIL`. Summarize a run:

```sh
node brain/bench/exam-report.js build/gametest.log [--record]
```

`tools.json` is the body's bridge tool registry, regenerated with `node brain/bench/sync-tools.js`
after `BridgeTools` changes. The model bench of planner/actor roles was removed with ADR-0016
(tag `before-worker`).
