# Brain model benchmark

Run from the repository root with Node 22+, no npm dependencies:

```sh
node brain/bench/run.js --models codex:gpt-6-luna,codex:gpt-6.1-terra,codex:gpt-reserve,codex:gpt-5.6-luna,claude:haiku,claude:sonnet --tiers default,priority --repeat 3
```

This command runs real authenticated CLI backends when network is available. It does not start
Minecraft or connect to the live brain/body. Unsupported/unavailable models become failed rows;
there is no fallback model. Existing backend defaults remain unchanged.

Offline smoke run (same command with `--fake`), or a smaller pass/fail demonstration:

```sh
node brain/bench/run.js --models codex:passing,codex:failing --tiers default,priority --repeat 3 --fake
node --test brain/test/ brain/bench/test/
```

Select ADR-0010 role scenarios with `--role planner` (iron pickaxe from scratch, shelter before
night) or `--role actor` (single obtain/craft steps). Without `--role`, the original eight reactive
scenarios remain. Planner cases use `planner-prompt.md` and an MCP exposing only `knowledge`;
they validate JSON, checkable conditions, acquisition skills and preparation/crafting order.
The shelter position condition checks arrival only, not that a finished building exists.
Actor cases get the goal, step, state, scene and one-line plan status. Raw rows include `role`.
Both role sets support `--fake`; simulated planner answers also exercise MCP action rejection.

Reports go to `build/model-bench-<UTC-time>.md` and the adjacent `.jsonl`. Fake reports are clearly
marked as simulated; their scores/timings say nothing about real models. CLI defaults: all six
models, tier `default`, three repeats, 120 seconds per turn (`--timeout-ms` overrides it).

The full two-tier command runs 288 turns sequentially (96 cold, 192 warm). At an assumed 5–20
seconds per turn, allow roughly 24–96 minutes; actual model latency has not been measured here.
The same matrix with fakes takes seconds. Fake body task outcomes are immediate, so latency
measures model/MCP overhead rather than game action duration.

Each model/tier/scenario gets a fresh backend process and conversation. Repeats reuse that
conversation, resetting the scripted world/inventory and supplying the same message/state each
time. Thus warm measurements include repeated-task conversation history, not just cached prompts.
After an error the session is closed, and the next attempt is recorded as cold. Failed attempts
count against pass rate and are excluded from latency percentiles. TTFT is first **text** token;
it may occur after tool calls. Cold timing includes backend startup. Missing usage/TTFT is `n/a`.

Codex tier is passed in CLI config, thread creation and turn creation. Claude supports model
selection but has no service-tier option in this backend: both requested tier groups are run as
independent samples of the same configuration, explicitly labeled `tier n/a`.

`scenarios.json` holds messages, compact initial states, ADR-0009 scene text, matching tool
responses, state transitions and pass rules. Unknown actions/invalid arguments fail explicitly.
The danger scenario uses an observed safe path ref (`goto f1`) or a visible block placement;
the real registry currently has no attack/flee tool. Rule checks are deliberately small heuristics:
inventory/greeting word matching is not a human evaluation of language quality.

`tools.json` is generated from the `BridgeTools.java` registry by `node brain/bench/sync-tools.js` (rerun after changing tools), including descriptions,
task flags and parameter schemas. Update it when the body registry changes; a test checks names,
descriptions and task flags against the source. The TCP bridge implements authenticated hello,
immediate results, task acknowledgments and `task.finished` events. `mcp.js` imports the production
`serveMcp` unchanged, directing feedback/session/audit files into a private temporary directory.
Configs and logs never overwrite `brain/mcp.json` or the live brain's files.

Raw JSONL has one row per attempt: model/backend/requested and effective tier, scenario/repeat,
cold/warm, total and first-text milliseconds, original and normalized token usage, actual audited
tool arguments/results, answer, word count, pass and failed rule names, and any backend error.
Claude input totals include normal, cache-read and cache-creation input; cached is the read subset.
Ranking sorts pass rate descending, warm p50 total latency ascending, then mean input+output tokens
ascending. With one repeat, warm latency is unavailable.
