# Blocknet brain

Node 22, plain ES modules, no npm dependencies. The mod is the body; this process uses rules for
short orders, a planner for goals and a small actor for individual steps. Defaults are
`--planner claude:sonnet` and `--actor codex:gpt-6-luna:priority` (low reasoning). Both use
your existing subscription login, with no API key or npm dependencies.

1. Start Minecraft with the mod and spawn a bot: `/blocknet spawn Kirill`.
2. From the repository root, run:

   ```sh
   node brain/brain.js --bot Kirill
   node brain/brain.js --bot Kirill --planner codex:gpt-6.1-sol:medium --actor claude:haiku
   ```

3. In player chat: `Kirill, ко мне`, `Kirill stop`, `Kirill, осмотрись`, or a longer request such as
   `Kirill, сделай деревянную кирку`. `Kirill, накопай 10 булыжника` routes straight to `gather`
   without a model call. Add `--alias Кирилл,Кир` for Russian aliases.

The default config path is `mod/run/config/blocknet_ai-bridge.json`, created by the mod on the first
server start. For Prism or another server directory, use:

```sh
node brain/brain.js --bot Kirill --config "/path/to/minecraft/config/blocknet_ai-bridge.json"
```

`BLOCKNET_CONFIG`, `BLOCKNET_BOT`, and `BLOCKNET_ALIASES` supply the same defaults as `--config`,
`--bot`, and `--alias`. Legacy `--backend` / `BLOCKNET_BACKEND` selects the actor family
when `--actor` is omitted (`claude` uses Haiku). `--max-turns N` caps autoplay model calls
(planner plus actor, default 200); actor sessions restart for each plan step. Config contains
`port` (25599 by default) and a generated `token`; restart
the Minecraft server after editing it. TCP binds only to `127.0.0.1`. The client reconnects every
second; interrupted transport actions are never replayed automatically. After death autoplay waits
for the body's respawn event before pursuing the goal again.

The brain generates `brain/mcp.json` at startup (`brain/mcp.<name>.json` for other bots), pointing
Claude to `mcp-server.js` with the absolute config path and bot name. The token stays in the mod's
config. The MCP wrapper exposes the body's registry and waits up to ten minutes for a task outcome.
It uses JSON lines on stdio, supports MCP `2026-07-28` metadata/discovery and initialization-based
clients including `2025-11-25` (see the [MCP spec](https://modelcontextprotocol.io/specification/latest/basic/versioning)).

Each backend module has one spawning function and accepts an injectable `spawnProcess` for tests.
Both implement `send(text) -> {text, usage, toolCalls, ms, ttftMs}` and `close()`.
The Claude command for Kirill is built by `buildClaudeArgs` in `claude-backend.js`:

```sh
claude -p --model haiku --mcp-config brain/mcp.json --strict-mcp-config --tools "" --allowedTools mcp__blocknet --permission-mode dontAsk --input-format stream-json --output-format stream-json --verbose --include-partial-messages --system-prompt-file brain/prompt.md
```

Each player request is a `type: "user"` JSON line on the same stdin. The process stays alive;
each `result` event finishes a call and supplies usage. There is no per-message spawn or resume.
Built-in tools are disabled. Subscription authentication is retained (no `--bare`).

`codex-backend.js` starts `codex app-server --listen stdio://` with these `-c` overrides:

```text
model="gpt-6-luna"
service_tier="priority"
model_reasoning_effort="low"
approval_policy="never"
sandbox_mode="read-only"
project_doc_max_bytes=0
features.skip_host_skill_discovery=true
features.skill_search=false
features.plugins=false
features.apps=false
features.memories=false
features.shell_tool=false
features.multi_agent=false
web_search="disabled"
mcp_servers={blocknet={command="<node executable>",args=["<absolute brain/mcp-server.js>"],env={BLOCKNET_CONFIG="<absolute bridge config>",BLOCKNET_BOT="Kirill",BLOCKNET_AUDIT="<absolute brain/logs/tool-calls.Kirill.jsonl>"},default_tools_approval_mode="approve",tool_timeout_sec=600}}
```

These are separate argv entries, not shell commands. `thread/start` replaces the large coding
instructions with `prompt.md` via `baseInstructions`, and sets `approvalPolicy: "never"`,
`sandbox: "read-only"`, `serviceTier: "priority"`, and an ephemeral thread. Each `turn/start`
uses the same thread and `effort: "low"`. Host skill discovery, skill search, plugins, apps,
memories, project docs and the shell tool are disabled. Blocknet MCP consent elicitations are
accepted automatically; other server requests are rejected. JSON-RPC shapes follow the generated
bindings in `_src/codex-app-server/`. Codex usage is the change in cumulative thread tokens over
the whole turn, including its tool round trips.

`claude` must already be installed and authenticated. `CLAUDE_BIN` overrides its executable.
On Windows use native `claude.exe`; for an npm installation whose `claude` entry is a `.cmd` shim,
set `CLAUDE_BIN` to `node` and `CLAUDE_SCRIPT` to the absolute installed Claude `cli.js` path.
The brain spawns the executable directly with `shell: false`. Codex must also be installed and
authenticated; `CODEX_BIN` and optional `CODEX_SCRIPT` provide the equivalent overrides.

Short stop/come/follow/look orders in English and Russian and clean Russian gather orders bypass
the model. Messages queue during a call. `стоп`/`stop` acts immediately, closes the model process
and clears queued requests; death also resets the session. A dead process restarts on the next
request; an interrupted request is never replayed. Reactive conversations rotate every 10 turns.
Model replies are sent after completion, allowing empty answers and `<silent>` to stay silent.
Each user message includes a compact inventory, held item and low-health line only when changed;
a fresh session gets the current line again. The English prompt asks for one casual sentence in
the player's language; delivery caps replies at 12 words / 200 characters.

`/blocknet gather [name] <item> <count>` and bridge `gather {item, count}` mine and collect that
many additional items. Candidates come from eye-observed WorldMemory and a quick glance. With
matching ground underfoot, the bot opens a simple forward/down staircase, checking each exposed
block before mining through the existing player code path. No ore prospecting or hidden-block
scan. With no resource or matching ground it reports `failed: no <item> in sight; look around
or go elsewhere`. Success reports once, e.g. `gathered 10 cobblestone`, plus tool wear when relevant.

`brain/logs/usage.jsonl` records each model call's backend, model, milliseconds, time to first
text token (when available), raw token usage, tool call count and error. `tool-calls.<bot>.jsonl`
is the per-call MCP counter (reset before each call). Failed calls have null usage when no result
was available. Generated MCP configs and logs are ignored by git.

```sh
node --test brain/test/ brain/bench/test/
```

Tests cover rules, MCP framing/task outcomes, persistent Claude stream-json and Codex JSON-RPC
with fake children, usage, restarts, and slow/fast acknowledgements. No tests call a model.
Real authentication, end-to-end latency and model behavior require a connected live test.

## Planner, executor, critic and plan library (ADR-0010 steps 2–3)

`--planner backend:model[:tier|effort]` and `--actor backend:model[:tier|effort]` create separate
backend sessions, even for the same model. Codex accepts `default`/`priority` service tier or a
reasoning effort such as `medium`; Claude accepts effort such as `high`. The planner has its own
MCP config and can call only `knowledge`; its system prompt is `planner-prompt.md`.

Goal words (e.g. `сделай`, `построй`, `кирка`, `дом`, `build`, `make`) select the planner;
single registered tool commands and short rules bypass it. Ordinary chat uses the actor, which
can return `needs_plan` to escalate. Autoplay plans its goal once and ends on completion or final
failure. Planner input contains current state/scene, one line per tool with argument order,
previous failures, and the two nearest library examples. Invalid JSON/schema/conditions get one
correction attempt; a second invalid response is reported to the player.

```json
{"goal":"железная кирка с нуля","steps":[{"id":1,"do":"obtain stone_pickaxe 1","done":"inv stone_pickaxe >= 1"},{"id":2,"do":"obtain iron_ingot 3","done":"inv iron_ingot >= 3"},{"id":3,"do":"craft iron_pickaxe 1","done":"inv iron_pickaxe >= 1"}],"budget_min":20}
```

Steps execute in order. Registered commands accept positional arguments (`obtain iron_ingot 3`)
or JSON arguments (`goto {"ref":"t1"}`), and run directly through the body without the actor.
Free text gets a fresh actor session with only the language rule, goal, step, done condition,
state line, scene line and plan status. The critic reads full `inv` and `status` snapshots,
ignores held-item echoes/summaries, and checks inventory totals or position. Supported conditions:
`inv <item-id> >= <count>` and `pos <x> <y> <z> [within <radius>]`. Bare Minecraft item ids and
`minecraft:` ids are equivalent; other namespaces remain distinct. Unsupported predicates are
rejected, including claims that a house exists: position proves arrival only.

The plan has 1–32 steps and `budget_min` in (0,120]. Each step gets an equal share unless it has
its own positive `budget_min`; the original plan deadline also applies across replans. A body
failure, unverified completion or budget expiry replans with the reason. After three failed steps
in total (initial attempt plus at most two failure replans), the bot briefly tells the player.
Budget expiry stops the body and actor. Stop/death cancel the plan; continue/respawn can pursue
the autoplay goal again. Every actor message carries a one-line status; bare chat `план?` reads
it immediately, even while a step is running, e.g. `план: шаг 2/3 obtain iron_ingot 3 — 1/3`.

Successful plans append to ignored `brain/logs/plans.jsonl`: goal, normalized `goal_key`, steps,
budget and `duration_ms`. Matching lowercases, removes punctuation/politeness and sorts words.
An exact match or word-set similarity >= 0.8 reuses the plan without a planner call; differing
numbers never match. Only successful, valid records are reused; malformed JSONL lines are ignored.
After a failure the planner is always called, even if a matching cached plan exists.

Role benchmarks: `node brain/bench/run.js --role planner --models claude:sonnet --repeat 1`
and `--role actor --models codex:gpt-6-luna --repeat 1`. Add `--fake` for simulated backends.
Planner cases check JSON, acquisition skills, preparation/crafting order and checkable conditions
for `железная кирка с нуля` / `дом до ночи`; actor cases check one obtain/craft step.

## Autoplay, chat and learning logs

```sh
node brain/brain.js --bot Kirill --autoplay "survive and progress: get wood, tools, shelter, iron" --max-turns 200 --max-minutes 60
node brain/brain.js --bot Kirill --listen name --lessons 3
node brain/report.js
node brain/report.js brain/logs/session-<date>-Kirill.jsonl 10
```

Autoplay executes the goal plan, checking body state after each step. Ordinary queued chat is
handled after the current plan; status and stop act immediately. Bare `стоп`/`stop`
pauses and cancels the current action; `продолжай`/`continue` resumes. The defaults are hard limits
of 200 model turns (including chat while autoplay is active) and 60 wall-clock minutes, including
paused/disconnected time. Reaching either limit ends autoplay; one final reflection asks for
3-5 lessons and then the brain remains idle, available for player chat. A time limit cancels the
model and sends stop to the body. No interrupted action is replayed after reconnecting.

`--listen all` is the default: with one bot, ordinary player chat needs no name. With several
bots, use a name at the start, or speak to the bot that last replied to that player. `--listen name`
requires a name/alias. Kirill also recognizes Кирилл/Кир by default; `--alias` overrides aliases.
Rules such as `ко мне`, `за мной`, `накопай 10 булыжника` and `стоп` work on bare messages.
The model may answer `<silent>` or an empty string when a message is for another player; nothing
is sent to game chat. The brain survives a closed game and reconnects when its bridge returns.

Body tools now include `eat {item?}`, `use {x,y,z,sneak?}` and `place {item,x,y,z,face?}`.
Commands: `/blocknet eat [name] [item]`, `/blocknet use [name] <pos> [sneak]`,
`/blocknet place [name] <item> <pos> [face]`. The optional face is the clicked support face
(`up`, `down`, `north`, `south`, `east`, `west`); place sneaks automatically. Eating holds normal
player use until consumption, chooses safe food by default, and refuses ordinary food at full hunger.

- `brain/logs/session-<date>-<bot>.jsonl`: chronological model messages (up to 300 characters),
  tool arguments/results/timings, changed state as line diffs, chat, and key body moments with
  snapshots. Each segment is at most 5 MB; continuation files use `.jsonl.1`, `.jsonl.2`, etc.
  The last 20 sessions are retained, including their segments.
- `brain/logs/tool-feedback.jsonl`: the brain-only `tool_feedback {kind,tool?,note}` MCP tool
  records missing/confusing/redundant/bug feedback in one concrete sentence. Never sent to the body.
- `brain/logs/report-<session>.md`: `report.js` prints and writes a compact Markdown report with
  goal, duration, turns, tokens, calls/failures per tool, key moments, deaths and their preceding
  30 seconds, grouped feedback, last failures (optional final number, default 10), and longest
  gaps between progress. Without a path it uses the latest session and follows rotated segments.
- `brain/logs/lessons.md`: one reflection at autoplay end, or on chat `Кирилл, итоги` (bare `итоги`
  works in all mode). `--lessons N` adds the last N reflection entries to the next system prompt,
  capped at 2400 characters; the default is 0.

Moments include damage bursts merged over 2 seconds, death, low health/hunger, broken equipment,
failed tasks, falls over 4 blocks, entering water/lava/fire, dimension/night transitions, first
hostile sightings and full inventory. Snapshots include task/report, last three bridge calls,
nearby entities (cap 8), light, biome, held item, ground/front blocks and nearby hazards.
Human-only facts that were outside the bot's sight are labeled `unseen`; these fields are removed
before an event reaches the model. Routine state/tool results stay compact; no raw backend dumps.
