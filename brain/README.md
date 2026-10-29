# Blocknet brain

Object interaction and verified cargo jobs (ADR-0013): `inspect ref`, `look-in ref`,
`withdraw ref filter [count]`, `deposit ref filter [count]`, and `transfer source destination`.
`transfer` moves the whole initial cargo in batches and keeps owned quantities/components.
Repeat it for the same endpoints after stop to resume a saved job; `job` shows unfinished cargo.
`job {"action":"forget"}` abandons the saved obligation without moving any real items.
Pointing at a block while speaking supplies a separate `p#` hint, supported by these actions.

`menu` inspects a menu opened with `use ref`. `menu_click` accepts its exact `menu_id`, `slot`,
optional `button` and `click` (`pickup`, `quick_move`, `button`). It uses the ordinary server
menu path; custom network terminals may require an adapter. `plan` exposes supported recipes
and their deficits to the model. No universal JEI or network-autocrafting adapter is claimed.

Memory viewer (Node 22, no packages): from the repo root run `./brain/start-viewer.ps1`, then
open `http://127.0.0.1:25600`. The script prefers the Prism Blocknet Dev bridge config, or falls
back to the dev config; `-Config <path>` overrides it. Minecraft must run an updated mod jar.
Top projection, height slices, pan/zoom, object selection, ages and container snapshots read
only eye/menu memory. Empty background means unknown. A saved test snapshot can be previewed
with `node brain/viewer.js --snapshot build/memory-view-sample.json --port 25601`; this is explicitly
labelled as a saved snapshot, not the user's live world. GameTests produce that sample file.
`http://127.0.0.1:25600/3d` is the 3D view (plain WebGL2, no libraries): `debug.memory` mode
`voxels` returns remembered surface blocks (faces toward air or the unknown, nearest first, at
most 250k, radius 32/64/96) and the page flies over them: click for mouse look, WASD, Space/C,
Shift faster, wheel speed, F back to the bot; the crosshair names the block and its age. Blocks
seen in the last 10 s are bright, older ones dim, blocks seen in darkness are violet.

Node 22, plain ES modules, no npm dependencies. The mod is the body; this process uses rules for
short orders, a planner for goals and a small actor for individual steps. Defaults are
`--planner codex:gpt-6.1-sol:high` (high reasoning) and `--actor codex:gpt-6.1-sol:medium` (medium reasoning, priority tier; 2026-10-28 live play: terra picked wrong tools too often. Earlier 2026-10-28 bench: actor steps ~4.6 s vs ~5.2 s on luna, same pass rate; planning: terra and sonnet both passed, terra wrote leaner plans that lean on obtain and answered in ~4 s vs ~7.7 s, luna failed a case; use --planner claude:sonnet to move planning off Codex limits). Both use
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

To skip step 2, name the brain directory in the bridge config; `/blocknet spawn <name>` then starts
`node <brain>/brain.js --bot <name> --config <that config>` itself and stops it, with the model CLIs
it spawned, on despawn or when the world closes. `node` is optional (default `node` from PATH).
Output goes to `logs/blocknet/brain-<name>.log` in the game directory; an unexpected exit is
announced in chat. Don't also run a brain by hand for the same bot, or both will answer.

```json
{"port": 25599, "token": "...", "brain": "C:/Projects/blocknet/brain"}
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
Actor built-in tools are disabled. The planner uses `--tools WebSearch --allowedTools mcp__blocknet,WebSearch`
(supported by the installed Claude CLI), keeping shell/file tools disabled. Subscription authentication is retained (no `--bare`).

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
the model. During reactive calls and plans, a separate tool-free chat session handles new messages
while the body keeps working. stop/new_order cancels the body and actor immediately after classification;
questions/chat answer without stopping. Reactive hints restart the turn when the backend cannot inject them.
Chat classification also runs before the first plan is ready and during invalid-output retries or
failure replans. Only hint reviews wait for an available planner; a waiting hint never blocks a
later question or replacement order.
Obvious `стоп`, `хватит`, `не надо`, `отмена`, `остановись`, `stop`, `cancel` phrases bypass models,
including punctuation/profanity; a following order such as `хватит копать, принеси уголь` runs next.
Cancellation changes generation and suppresses stale speech. Death also resets the session. A dead process restarts on the next
request; an interrupted request is never replayed. Reactive conversations rotate every 10 turns.
The first preamble line streams immediately; remaining replies arrive after completion. Empty
answers and `<silent>` stay silent.
Each actor/planner/classifier input includes the last six speaker-labelled player and bot lines,
including streamed preambles and planner questions. Short answers retain their dialogue context.
An actor that starts no tool and produces no final response within 45 seconds is aborted, says one
short fallback, and logs `tool_feedback` kind `bug` with the request. Preambles do not reset this
watchdog; real tool calls can take longer. Planner requests retain their 120-second limit.
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
with fake children, usage, restarts, and streaming/cancelled preambles. No tests call a model.
Real authentication, end-to-end latency and model behavior require a connected live test.

## Planner, executor, critic and plan library (ADR-0010 steps 2–3)

The brain logs `body_capabilities` from the live bridge registry on connection. If `obtain` exists
but `give` is missing, it warns in game and on stderr that the deployed mod jar is outdated.
Building with `-x deployJar -x removeOldDeployedJars` leaves the launcher copy unchanged: before
testing through Prism, install `mod/build/libs/blocknet_ai-0.1.0.jar` into that instance's `mods`
folder while Minecraft is closed, and verify its SHA-256 matches the built jar. Restarting only
the Node brain cannot add body commands or player-position output to an old running mod.

`--planner backend:model[:tier|effort]` and `--actor backend:model[:tier|effort]` create separate
backend sessions, even for the same model. Codex accepts `default`/`priority` service tier or a
reasoning effort such as `medium`; Claude accepts effort such as `high`. The planner has its own
MCP config and can call only `knowledge` and `wiki`; its system prompt is `planner-prompt.md`.
Codex planner arguments set `web_search="live"`; the actor keeps `web_search="disabled"`.
The planner prefers local `wiki`/`knowledge` and may search Minecraft wiki/mod docs for unfamiliar goals.

`persona.md` is the shared stable character prefix for actor and planner. It describes the
player's Minecraft buddy, casual language and brief speech without numbers unless asked.
`persona.json` keeps only the default language. There are no phrase pools or timer fillers.
Both roles emit one short intent line before thinking/tools; the planner follows it with JSON.
The brain sends that first line immediately from Codex agent-message deltas/completed items or
Claude stream-json text deltas, and strips it from the remaining result. Stop/death suppress stale
speech. Trivial rule orders stay model-free and have no preamble. One non-moving `scene` call runs
alongside planner preparation and is appended to its input. Hint acknowledgements come from the
tool-free classifier's `reply`, including what it understood from the player's hint.

`wiki {query: "ME controller"}` reads loaded GuideME Markdown, Patchouli JSON entries, FTB Quests
SNBT under `config/ftbquests/quests`, and English item/block names and tooltip/description lines.
The body lazily caches its index per server, invalidates it on startup/global datapack sync,
and ranks keywords/item IDs into at most three source-labelled snippets under 120 words.
Use `/blocknet wiki <query>` without a bot, and `/blocknet wiki-status` to list indexed or skipped
formats. Missing formats are skipped quietly; no external web request is made by the body.

Goal words (e.g. `сделай`, `построй`, `кирка`, `дом`, `build`, `make`) select the planner;
single registered tool commands and short rules bypass it. Ordinary chat uses the actor, which
can return `needs_plan` to escalate. Autoplay plans its goal once and ends on completion or final
failure. Planner input contains current state/scene, one line per tool with argument order,
previous failures, and the two nearest library examples. Invalid JSON/schema/conditions get one
correction attempt; a second invalid response is reported to the player.
State includes every other online player's name, position and dimension. A `pos` condition equal
to the bot's own position at plan start is rejected with a correction error. Successful direct
come/goto/give reports complete position conditions near a player (including flying players);
inventory and kill conditions still need exact evidence.

```json
{"goal":"find iron after preparing tools, coal, food and torches","steps":[{"id":1,"do":"explore_caves iron_ore 3","budget_min":5,"else":"mine_ore iron_ore 3","done":"inv raw_iron >= 3"}],"budget_min":15,"tool_gaps":[]}
```

Steps execute in order. Registered commands accept positional arguments (`obtain iron_ingot 3`)
or JSON arguments (`goto {"ref":"t1"}`), and run directly through the body without the actor.
Free text gets a fresh actor session with the language rule, recent dialogue, goal, step, done condition,
state line, scene line and plan status. The critic reads full `inv` and `status` snapshots,
ignores held-item echoes/summaries, and checks inventory totals or position. Supported conditions:
`inv <item-id> >= <count>` and `pos <x> <y> <z> [within <radius>]`. Bare Minecraft item ids and
`minecraft:` ids are equivalent; other namespaces remain distinct. Unsupported predicates are
rejected, including claims that a house exists: position proves arrival only.

The plan has 1–32 steps and `budget_min` in (0,120]. Each step gets an equal share unless it has
its own positive `budget_min`; the original plan deadline also applies across replans. A body
failure, unverified completion or budget expiry runs the optional step `else` first, with the same
`done` condition and a fresh step budget inside the original plan deadline. Only failure of that
fallback (or a step without one) calls the planner again. After three failed steps
in total (initial attempt plus at most two failure replans), the bot briefly tells the player.
Budget expiry stops the body and actor. Stop/death cancel the plan; continue/respawn can pursue
the autoplay goal again. Every actor message carries a one-line status; bare chat `план?` reads
it through a short tool-free model reply even while a step is running. Internal context looks like `план: шаг 2/3 obtain iron_ingot 3 — 1/3`.

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

Autoplay executes the goal plan, checking body state after each step. You can steer the bot while
it works: say `железо глубже, на y=16` or `сундук с углём слева`. It acknowledges
through the classifier's model-written reply, without a local phrase or cooldown.
One cheap actor call classifies each message as a hint, new goal, question or small talk.
Hints go to the planner with the current plan, step, state and scene, and remain in memory for
later replans. A `keep` response continues the body task; revised remaining steps replace the
plan, cancelling the body task only if the current step changes. Questions get a short answer
without replanning; small talk gets a short reply or silence. A different request, such as
`сначала добудь уголь`, gets a one-line confirmation and a new plan; the long-term autoplay goal
resumes afterwards. `план?` asks the model for a short progress reply without interrupting the body. Bare `стоп`/`stop`
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
`give {player,item,count?}` walks next to the player, faces their eyes and tosses selected stacks
through the Q-key `ServerPlayer.drop(boolean)` path. `item` can be `all`; omitted count means all
matching owned items. Partial stacks are split, and nothing is teleported. `drop {item,count?}`
tosses where the bot stands. Commands: `/blocknet give [name] <player> <item|all> [count]` and
`/blocknet drop [name] <item|all> [count]`. A successful report confirms tossing; normal pickup
rules determine receipt. Airborne destinations fall back to nearby ground within four horizontal
blocks when there is no ground directly below within sixteen. "Bring/give X" uses owned items;
only missing items need obtain, whose count is a TOTAL inventory target.
Commands: `/blocknet eat [name] [item]`, `/blocknet use [name] <pos> [sneak]`,
`/blocknet place [name] <item> <pos> [face]`. The optional face is the clicked support face
(`up`, `down`, `north`, `south`, `east`, `west`); place sneaks automatically. Eating holds normal
player use until consumption, chooses safe food by default, and refuses ordinary food at full hunger.

- `brain/logs/session-<date>-<bot>.jsonl`: chronological model messages (up to 300 characters),
  tool arguments/results/timings, changed state as line diffs, chat, and key body moments with
  snapshots. Each segment is at most 5 MB; continuation files use `.jsonl.1`, `.jsonl.2`, etc.
  The last 20 sessions are retained, including their segments.
- The session log also keeps `model_trace` entries: every model input and output in full (up to
  20 000 characters, unlike the 300-character `model_message`), Codex reasoning summaries and Claude
  thinking blocks, and the MCP tool calls a model made inside its turn with arguments and results.
  Plan, step start, critic verdicts and short model notes also go to the body as `debug.note`
  (JSON-RPC notification, no reply), so `/blocknet debug` shows them in game and they land in the
  body trace `logs/blocknet/<bot>-trace.log`. `node brain/timeline.js [session] [--config ...]`
  merges both into `brain/logs/timeline-<session>.txt`.
- `brain/logs/tool-feedback.jsonl`: the brain-only `tool_feedback {kind,tool?,note}` MCP tool
  records missing/confusing/redundant/bug feedback in one concrete sentence. Validated planner
  `tool_gaps: [{want, why}]` append to the same file with `source: planner` and to the session log;
  reports label their source under Tool feedback. Never sent to the body.
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

## Survival playbook and cave exploration

The stable planner playbook prepares wood, table, wooden pickaxe, stone tools, coal and food,
then torches, cave ores, furnace/smelting and iron tools. Fuel and food are explicit preparations.
Ore search uses visible ore from look/scene first, then seen cliffs/cave mouths, bounded
`explore_caves`, and only then `mine_ore` at a knowledge peak. Unfamiliar items use knowledge/wiki.

```json
{"id":1,"do":"explore_caves iron_ore 3","budget_min":5,"else":"mine_ore iron_ore 3","done":"inv raw_iron >= 3"}
```

`/blocknet explore_caves [name] <target> [count]` (bridge `explore_caves {item,count}`) chooses
an observed cave-mouth frontier or dark opening, looking fresh once if needed. It reports
`no cave seen` if neither exists. It walks only observed free cells with an observed solid floor,
including one-block steps, stays under observed natural ceilings once inside, and avoids observed
lava and hostiles. It periodically looks, follows local cave frontiers, gathers reachable wall
ore through gather/vein following without wandering off its walking route, and places owned
torches through normal player placement when local light is below 7. It never scans hidden blocks.
The five-minute body budget reserves up to two minutes for returning along checked breadcrumbs;
reaching the count also starts the return. External stop/death cancels immediately; a blocked
return reports the problem. Progress events arrive every 200 ticks and there is one terminal
report, e.g. `explored cave 40 blocks; got 3 raw_iron, 5 coal; placed 4 torches`.

Planner `tool_gaps` is optional: at most 32 nonempty `{want, why}` strings, bounded to 200/500
characters. Invalid gaps/fallbacks are rejected with the rest of the plan. No new bench rules
or tests were added for this live-play iteration; existing speech tests follow the new contract.

Skill scripts (ADR-0014): the actor has `run_script {code, args}`, `save_skill {name, description,
params, code}`, `skill {name, args}` and `skills {query}`. A script is the body of `async (bot, args)`;
`bot.<tool>({...})` runs any body tool and throws `SkillError` with the report on failure; helpers
`count`, `inventory`, `wait`, `log`. Sandbox: `node:vm` without require/eval, 2 s sync, 8 min per
run; a player's stop ends the run. Learned skills are JSON files in `brain/logs/skills/`
(`BLOCKNET_SKILLS` overrides the folder); the planner sees their list. Tests: `brain/test/skills.test.js`.

## Voice (optional)

Players talk to the bot through Simple Voice Chat: hold push-to-talk near the bot (24 blocks), and
the phrase goes to the brain, which transcribes it with a local whisper.cpp server, shows the player
`[bot heard] ...`, and treats it as chat. Replies are also spoken with Piper from the bot's position.
`powershell -ExecutionPolicy Bypass -File brain/setup-voice.ps1` installs both into
`%LOCALAPPDATA%\blocknet` (CUDA build + large-v3-turbo with an NVIDIA GPU, CPU build + small
otherwise; `-Model`, `-Voice`, `-Cpu`, `-Root` override). The brain looks in the config's `voice.home`,
`BLOCKNET_VOICE_HOME`, `~/blocknet`, then `%LOCALAPPDATA%\blocknet`. Override paths in the bridge config under
`"voice": {"stt": {"server", "model", "language", "gpu"}, "tts": {"piper", "voice", "enabled"}}`.
Without these pieces, or without Simple Voice Chat, everything stays text-only.

`-Voices all` installs every Russian Piper voice (dmitri, denis, irina, ruslan). In game,
`/blocknet voice` lists them, `/blocknet voice irina` or `/blocknet voice next` (also "Сменить голос"
in the radial menu) switches at once; the bot answers in the new voice, and the choice is kept in
`piper/voices/chosen.txt` for the next start. A configured `tts.voice` still wins over it.

A player message the bot will work on gets an instant spoken line from the quick mouth: a separate
tool-free session that sees only the persona, the last six dialogue lines and the compact state
(~2 KB prompt instead of the actor's 30-100k tokens). The actor or planner runs in parallel; of the
quick line and the model's first preamble line, whichever comes first is said and the other is
dropped (later preambles, e.g. after a replan, are still said). `--quick backend:model[:effort]`
picks its model (default: the actor's model at `low`), `--quick off` disables it. Each line is logged
as `quick_reply` with its latency. Whisper is warmed with half a second of silence at start.
