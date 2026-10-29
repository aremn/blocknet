---
name: blocknet-dev
description: How to work in the Blocknet repository (C:\Projects\blocknet, github.com/aremn/blocknet), a NeoForge 1.21.1 mod where an AI companion plays modded Minecraft as a real server-side ServerPlayer. Use this skill for any change, build, test or git task in this repo, even small ones; adding or changing bot actions, skills, /blocknet commands or radial menu entries; GameTests; running the dev client or server; pathfinding and the Automatone extraction; the brain/protocol; docs and ADRs; commits, branches and pushes. Also use it when the user just says "next step", "add a command to the menu", "make the bot do X" or reports the bot misbehaving in game, since those all mean editing this repo.
---

# Working in Blocknet

Blocknet is an AI companion for modded Minecraft (target: All the Mods 10, Minecraft 1.21.1,
NeoForge 21.1). The **body** is a server-side mod: the bot is a real `ServerPlayer`, so it acts
through the same server code a human player triggers. The **brain** (an LLM, for now Claude Haiku
through `claude -p`) is a separate Node 22 process talking to the body over TCP JSON-RPC lines.

Before designing anything non-trivial, read `docs/ARCHITECTURE.md` and the decisions in
`docs/adr/`. They record what was chosen and why; `docs/DEVELOPMENT.md` has the day-to-day rules.
If a change contradicts an ADR, write a new ADR that supersedes it instead of silently diverging.

## Environment

- Windows, Git Bash. JDK 21 is not the system Java (that is Java 8), so every Gradle call needs
  `export JAVA_HOME="/c/Users/ncpaa/.jdks/jdk-21.0.12.1+1"` first.
- The repo has two Gradle layers: the root (settings, wrapper, `gradle.properties`) and the `mod/`
  subproject. The LGPL `pathing/` and `crafting/` modules and Node 22 `brain/` sit next to `mod/`; `protocol/` schemas are planned.
- Minecraft and NeoForge sources (Mojang names + Parchment) are in
  `mod/build/moddev/artifacts/neoforge-21.1.219-sources.jar`. Read real signatures there
  (`unzip -p <jar> net/minecraft/...java`) instead of guessing; extract files you need to `_src/`
  (git-ignored). Clones of other repos also go to `_src/`, with `git -c core.longpaths=true` because
  Windows path limits bite deep Java trees.

## Commands

```bash
export JAVA_HOME="/c/Users/ncpaa/.jdks/jdk-21.0.12.1+1"
./gradlew build                                            # compile + jar (mod/build/libs/)
./gradlew :mod:runGameTestServer --console=plain > build/gametest.log 2>&1
grep -a -E "GAME TESTS COMPLETE|required tests (passed|failed)|failed at" build/gametest.log
./gradlew :mod:runClient --console=plain > build/client.log 2>&1   # run in background
```

When the game may be running, build with `./gradlew build --offline -x deployJar -x removeOldDeployedJars`.
If sandboxed Windows Java reports ZipFS `AccessDeniedException`/`toRealPath` for the user Gradle
cache, copy the offline `caches/modules-2`, `caches/neoformruntime`, `wrapper` and user
`gradle.properties` into ignored `build/gradle-home`, set `GRADLE_USER_HOME` there, and rebuild
with `--no-configuration-cache` once. Do not alter cache ACLs or stop the running game.

- GameTests are the main proof that something works: they run a real server and finish in seconds.
  Don't trust Gradle's exit code alone; grep the log for the summary and `failed at`, and for
  `Exception|NullPointer`. "Unable to delete file ... latest.log" is harmless (a running client
  holds `mod/run/logs`).
- The dev client opens a game window on the user's screen. Start it in the background, wait for
  `Sound engine started` in `build/client.log`, then hand over to the user: you cannot see the screen,
  so anything visual (the radial menu, rendering) is the user's manual test. Say so plainly.
- A running client uses the code it was started with. After a fix, ask the user to close the game
  before relaunching; killing it can lose their test world.
- Test fixture mods (AE2 + GuideME today) are `localRuntime` only, pinned in `gradle.properties`.

## Code map (`mod/src/main/java/dev/blocknet/`)

| Package | What |
|---|---|
| `body/` | `BotPlayer` (ServerPlayer, runs its own physics in `tick()`), `BotConnection`, `BotPacketListener`, `BotManager` (spawn/despawn/nearest) |
| `control/` | `BotController`: one task at a time, survival mining/pickup, tools and aiming; `MiningTask`: safe straight descend/tunnel, observed ore reports with refs; `GatherTask`: observed targets/refs and newly exposed connected veins; `ContainerTask`/`ContainerFilter`: delayed menu transfers; `CraftExecutor`/`CraftStations`: planning execution and remembered stations; recent `look` uses a frontal frame, `look fresh` forces panorama; `ExploreTask`: observed safe steps then panorama |
| `memory/` | `WorldMemory`: compatibility facade and per-UUID SavedData for the section-sparse observed map (stations/containers queried from it); `ContainerMemory`: component-aware menu snapshots per dimension/position, double-chest normalization, per-UUID SavedData persistence, compact memory/whereIs queries and nearest planner sources |
| `map/` | ADR-0011: `MapScanner` (one loaded chunk per tick, 0.3 ms cap: structure starts + cave air samples, never ores), `MapMemory` (level SavedData, caves clustered by chunk, refs v#/k#), `Radar` (live creatures in 48 blocks, refs e#), `WorldMap` (map text, ground spots for goto) |
| `vision/` | MIT `VoxelRay` (loaded-section DDA, air jumps, exact partial VISUAL shapes), reloadable `OpacityTable`, budgeted jittered `VisionSensor` (160x90 background/frontal/panorama/entities, budgeted exposed-neighbour rays after breaks), `ObservedMap` (512-section LRU, palette persistence), tag/property `Semantics`, incremental `ObjectGraph`, evidence-only `Frontiers`, compact ranked `Scene` (`look [focus]`, instant `scene`, deltas), rolling `VisionTiming`; `/blocknet see [name]` prints debug evidence and timings |
| `state/` | `ItemDescriber`/`ToolFacts`: generic item facts and vanilla tool probes; `BodyState`/`BodyStateTracker`: on-demand compact sections, independent revisions, bounded inventory diff history (ADR-0006); no transport |
| `command/` | `/blocknet` debug commands; the bot name is optional (nearest bot) |
| `debug/` | `BotTrace`: per-bot reasoning trace (one per name, survives respawn), merged repeats, tick cost; `TraceFiles`: async `logs/blocknet/<bot>-trace.log`; `PathTrace`: pathfinder lines and route events; `DebugWatch`/`DebugFrames`/`DebugPayload`: `/blocknet debug` frames (optional payload, particle fallback); client `DebugOverlay` draws them at `AFTER_PARTICLES` (as NeoForge's own debug renderers) with an explicit model-view matrix |
| `bridge/` | `BridgeConfig` (localhost port/token), `BridgeServer` (JSON-RPC lines, lifecycle/chat/death, server-thread dispatch), `BridgeTools` (one tool registry reusing commands and their reports; independent changes baselines) |
| `client/` | Optional client side: radial menu on the mouse wheel; only sends `/blocknet` commands |
| `mixin/` | `PlayerListMixin` installs `BotPacketListener` during `placeNewPlayer` |
| `gametest/` | GameTests on `arena`/`arena_tall`; sixteen `VisionGameTests` on the 48x24x48 `vision_field` (helper floor at y=0); `skyAccess = true` keeps daylight deterministic, the dark-room test builds its own roof; `TestArena` sets the bare GameTest PlayerList view distance to 4 chunks |

`brain/` is dependency-free Node 22 ES modules: `body.js` is the reconnecting TCP client,
`mcp-server.js` wraps tools with task-outcome waiting, `rules.js` routes short ru/en orders,
`brain.js` queues chat and builds Claude Haiku arguments/sessions/logs, and `prompt.md` is stable.
Run `node brain/brain.js --bot Kirill --config <minecraft>/config/blocknet_ai-bridge.json`;
dev config defaults to `mod/run/config/blocknet_ai-bridge.json`. Tests: `node --test brain/test/`.
With `"brain": "<repo>/brain"` in that config, `/blocknet spawn` starts the brain itself
(`bridge/BrainLauncher`, log `logs/blocknet/brain-<bot>.log`) and despawn/world close kills its
process tree; the Prism "Blocknet Dev" instance is set up this way, so don't start a second one by
hand for the same bot. `BrainLauncherGameTests` checks start, one-per-bot and tree stop with a fake brain.js.
`BridgeGameTests` proves authentication, discovery, status and physical goto through a TCP socket.
See `brain/README.md`, architecture section 5 and ADR-0008. No `claude --bare`; preserve subscription
auth, disable built-in tools and use strict MCP. Generated configs, sessions and logs stay ignored.

The LGPL `pathing/` module (Automatone) lives next to `mod/`: `BotController` drives it through
`IBaritone.KEY.get(bot).getCustomGoalProcess()`, `BotPlayer.tick()` ticks it, and it acts through
`BotHands`/`PlayerInteractionController`. It is compile-only for the mod (dev runs get its classes
from the `mods` block, the jar nests it via jarJar) — adding it as `implementation` duplicates its
packages and the game refuses to start.

The LGPL `crafting/` module adapts AE2's crafting calculation (ADR-0007): `RecipeCatalog` /
`Process` read server recipes, `ItemKey` includes components, `CraftingSimulationState` copies
inventory and commits successful branches, `CraftingCalculation` searches with bounds,
`CraftPlan` returns immutable steps/material balances and text. Optional caller-sorted
`RememberedSource` snapshots supply inventory-first withdrawals; `CraftPlan.withdrawals()` executes
through `ContainerTask` before recipes, sharing the one-replan rule. `PlannerCatalogs` caches the
recipe index across requests and detects reloads. `/blocknet plan [name] <item> [count]` is
planning only, server-thread synchronous, no brain or item movement. `/blocknet craft [name] <item> [count]`
executes an OK plan with `CraftExecutor`: vanilla recipe-book placement, menu clicks (3 ticks apart),
station placement/opening through useItemOn, real furnace ticks, and station breaking/drop collection
through the controller. Missing plans report once without execution; inputs are checked before each
step, with one replan allowed. Cancellation/death/logout close menus and report once. Like pathing, compileOnly
plus dev sourceSet and a nested GAMELIBRARY jar; preserve LGPL attribution. Index construction
is outside the 50ms search budget; search also caps depth/variants/operations. Stations count
from inventory and the caller's remembered stations (`CraftStations`, within 24 blocks; explicit `look` sees up to 16).
`CraftPlanGameTests` covers planning and budgets; `CraftExecuteGameTests` covers real menus, station
recovery, cooking time, modded recipe fixtures, cancellation/replanning and command reports.
`ContainerGameTests` covers menu-only observation, reach, junk/count/tag transfers, double chests,
full storage, stale snapshots/replanning, persistence/component serialization, cancellation,
locked/removed containers, source preference, command parsing, furnace memory and runtime-discovered
modded storage. `/blocknet look-in [name] <pos>`, `/blocknet deposit|withdraw [name] <pos> <filter> [count]`
and `/blocknet memory [name] [filter]` expose it.
Vanilla wooden pickaxe + table needs 9 planks, so 3 logs; with an owned table, 2 logs suffice.

## Rules that came from real bugs

These are why the code looks the way it does; keep them unless an ADR changes them.

- **The bot has no client.** `BotConnection` sits on an inert `EmbeddedChannel` (vanilla GameTest
  mock players do the same) because NeoForge and mods read `connection.channel().attr(...)`.
  `BotPacketListener` drops every send *before* NeoForge validates payloads: in a big modpack mods
  broadcast payloads to every player, and NeoForge throws for channels the bot never negotiated.
  `hasChannel` must keep its default answer (false); answering true made NeoForge dig into channel
  attributes and crash the login.
- **Act through player code paths.** Break with `gameMode.handleBlockBreakAction` and real per-tick
  progress, right-click with `gameMode.useItemOn`, use menus via `containerMenu`. Then modded blocks
  behave correctly and break speed, drops and menus are honest. Reach and line of sight are checked
  by us, because we bypass the packet handler.
- **Honest perception (ADR-0002).** The bot knows what it saw from its own eyes. No scanning loaded
  chunks to find blocks; that is x-ray, even if Baritone-style code does it.
- **No mod is special (ADR-0005).** Body code never names a mod: use registries, tags, capabilities,
  menus, `RecipeManager`. A mod-specific adapter only where the generic layer cannot reach, behind a
  generic interface, loaded only with its mod. Mods in tests are fixtures, named by behavior.
- **Server messages use `Component.literal`.** Clients usually lack this mod, so translation keys
  would show raw. Translations are fine on the client side (`client/`, `assets/blocknet_ai/lang`).
- **Bots fail like players, not like scripts.** When something is in the way the bot tries other
  faces, clears leaves, steps closer; it reports `failed: ...` only when out of options. Every task
  ends with exactly one report through the command's reporter.

- **Explain decisions.** A new choice in the body (which block, which route, why it gave up) gets a
  `controller.trace(source, text)` line saying why, so `/blocknet debug` and the trace file show
  it. The pathfinder talks through `Pathing.trace`; its chat lines never reach the bot's client.

## Writing GameTests

- Use `@GameTestHolder(Blocknet.MOD_ID)`, `@PrefixGameTestTemplate(false)`, `template = "arena"`;
  `TestArena.floor(helper)` lays stone at y=0, bots stand at y=1.
- Give each test a unique bot name (≤16 chars, tests run in parallel) and despawn it inside
  `succeedWhen` after the assertions.
- For honesty, assert timing too (`helper.getTick() >= N`): the test server runs faster than real
  time, so only tick counts prove a block took survival time to break.
- Name tests by behavior (`minesModdedBlock`), keep mod ids in constants.

## Git and GitHub

- Remote `origin` = github.com/aremn/blocknet (public). Author email is the GitHub noreply address,
  already set in the repo config.
- Never commit straight to `main`. Branch per task: `feat/…`, `fix/…`, `docs/…`, `build/…`,
  `test/…`, `spike/…` (spikes are not merged as is). Branch names describe the change, not a mod.
- Conventional Commits, one concern per commit, a body explaining why, and the trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Run `build` and the GameTests before every commit; push only when the user asks (they review on
  GitHub; CI builds every push). PRs are merged by squash, or rebase when the commits are
  worth keeping.
- Deleting branches, force-pushing and rewriting history need the user's explicit OK.
- Unmerged work may be stacked (a branch based on another feature branch); check
  `git log --oneline --graph --all` and say which branch to merge first.

## Pathfinding

`come` targets ground up to 16 blocks below the source, within 2 horizontal blocks; `goto` stays exact.
Navigation compares walking, mining and tagged inventory-block building in the first search, then retries healthy (>60%) falls up to 5 and nearby intermediate goals; attempt settings are restored.

Done in the basics (ADR-0004): walking around walls, ledges, digging through. `references/pathing.md`
has the history, what was kept and dropped, and the remaining ideas (gaps, pillars with blocks,
lava, doors, protecting player builds).

## Talking to the user

The user writes Russian, casually, and wants results more than explanations. Answer in Russian,
lead with what changed and how to try it in game, mention what you could not verify, and keep
English to code, commands and commit messages.


## Live autoplay recovery (second session)

- Build with a client running: `./gradlew build --offline -x deployJar -x removeOldDeployedJars`.
  Set JDK 21 first; set `GRADLE_USER_HOME=C:/Users/ncpaa/.gradle` if the wrapper misses its cached distribution.
- Death uses vanilla drops/keepInventory, then `PlayerList.respawn` after 20 ticks. The respawn mixin
  preserves BotPlayer and its inert listener; BotManager tracks the replacement. `bot.died` carries
  cause, position and items_dropped, followed by `bot.respawned`. Brain waits and continues autoplay.
- Danger pauses task ticking/timeouts and navigation (including idle pathing hotbar rearrangement). It fights visible approaching hostiles within
  6 blocks or mob attackers, through player attacks and cooldown: sword > axe > pickaxe > hand.
  It retreats below 30% health or when outnumbered, and resumes after threats die or leave 16 blocks.
  Moments: danger_reflex, danger_clear; only danger_failed wakes the brain (low health or continuing
  damage after 10 seconds). `attack <ref|type>` uses observed entity UUIDs or type names.
- Craft station recovery is best effort: pickup failure reports `crafted ... (left a crafting_table at x y z)`.
  Material forecasts reject deficits, allowing incidental pickups. Opening a remembered station allows
  navigation time rather than only the short menu timeout.
- `look food` includes observed animals, food drops and crops. Failed look-in hints remembered containers.
  Bridge goto/gather accept quoted comma-separated refs (first ref) and nested `{ref: "t1"}` values.
- Sessions rotate every 10 turns with goal, inventory summary, last 3 task results and current scene line.
  Scene output caps at 12 lines and two representatives per class, including structures.
- After BridgeTools changes run `node brain/bench/sync-tools.js`; tools.json is generated.
  Socket GameTests use port zero; an occupied live bridge port warns without failing the server.
  Run the full GameTest suite at the end (use the requested stability count); inspect required tests, failed at and exceptions in
  build/gametest.log. Node: `node --test brain/test/`. Leave changes uncommitted when requested.


## Knowledge and acquisition skills (ADR-0010 step 1)

- `knowledge/Knowledge` rebuilds at server start and global datapack sync. Walk the dimension's
  biome generation settings (not the flat generator's decoration overrides), placed features and
  OreConfiguration. Decode placement codecs for anchored uniform/trapezoid heights and count/rarity;
  other height providers report `unknown range`. Loot tables and shared ToolFacts probes provide
  drop/tool facts. Group variants by non-silk drop; never search world blocks to build this index.
- `/blocknet knowledge|mine_ore|obtain [name] <item> [count]` share the bridge registry. `mine_ore`
  gathers additional drops; `obtain` counts inventory. `get` retains its existing planner scope.
- `SkillTask` owns the final report while launching ordinary controller actions. Preserve ownership
  during internal starts/finishes; external stop/replacement cancels the owner exactly once. Progress
  is `task.progress` every 200 ticks, with depth/count and bridge task_id; budget 12000 ticks.
- `MineOreTask`: most frequent known peak at/below feet in this dimension, descend, main 1x2 tunnel,
  side branches every 3 blocks, length 12. Only observed ore interrupts this fixed schedule. Observed
  fluids stop a leg; the main leg can try a parallel lane. Stop before a tool has ≤5 uses left.
- `ObtainTask`: inventory → remembered withdrawals → planner deficits → observed logs/ground/ore.
  Bound depth (16), operations (256), and cycles. A forecast may add observed source items to a COPY
  for recipe selection (e.g. birch wood); it must never grant real items. Unsupported mob drops are
  explained from entity loot tables. Prepare wooden then stone pickaxes through the same goals.
- Observed side ore may expose only a face: gather visibility checks eye-facing faces as aiming does.
  For staircase support, aim at its top face; a center ray can hit the current stair's lip. Headroom
  pickup does not walk and must not mistake an empty head cell for missing floor support.
- `KnowledgeSkillGameTests` contains the six step-1 checks (including paired hidden-ore scenes).
  Wait for light for the tree. Run only requested targeted tests while iterating; the full suite once
  at the end unless a stability count is requested. Regenerate tools.json with `node brain/bench/sync-tools.js` after registry edits.

## Natural terrain regression checks (third live run)

- Flat stone arenas do not prove access through soil or recovery around water/caves. Use
  `NaturalTerrain` (32x48x32) and the `natural_column` / paired `natural_pair` templates. Local
  y=26 scales iron's low peak; pass the target absolute y through the controller overload in
  fixtures rather than changing worldgen knowledge. Keep coal, four dirt layers and deepslate.
- `NaturalMiningGameTests` checks soil-to-stone acquisition, water/cave descent to peak, identical
  first 45 real break events with/without hidden iron, a remembered cliff revisit, depth hints,
  observer-received swing/crack packets, both full 12-block branches and a real water-source plug.
  Test event listeners observe actions only; never feed hidden test world states into decisions.
- Gather refs are destinations for re-observation, including recent refs retired by object merges.
  Cliff refs yield cobblestone. Cover blocks are not failed cobblestone harvests. For tagged stone
  recipe deficits, prefer the supported cobblestone source over unseen alternative stones.
- `mine_ore` confirms the peak was reached, uses bounded deterministic lane changes around observed
  fluids/unsafe support, and can plug visible water sources using inventory/menu/useItemOn paths.
  No lava plugging. Keep direct descend/tunnel stop-before-fluid behavior. Return to saved branch
  junctions; check observed ore between tunnel steps. Count actual drops and report partial progress.
- Pickup must stay on the verified stair, even when a drop bounces over a cave. Wait for onGround
  before selecting the next stair: a jumping eye can falsely see a ceiling obstruction. Check
  support at the top face. Never step down into unknown air or liquids.
- Keep vanilla swing broadcasts and gameMode destroy progress at survival speed. Packet tests
  must observe them on another player's connection, including crack reset; do not invent progress.
- Propagate y, source surface y, active ore peak and distance into body/scene/CompactState. For
  status replacements, use the newest body revision. MCP must classify stopped/unsupported/none
  seen and partial `; stopped` reports as failures, so incomplete mining cannot pass a plan step.
- Build: `./gradlew build --offline -x deployJar -x removeOldDeployedJars`. If GameTest launch
  throws BootstrapLauncher NoSuchElementException, run `./gradlew --stop` once, then retry.
  After registry edits run `node brain/bench/sync-tools.js`; Node checks include brain/test/ and
  brain/bench/test/. Iterate on selected tests; finish this regression workflow with THREE full
  GameTest runs and inspect each required-tests summary, failed-at lines and exceptions.
- Camera rays may defer under load. For repeated fixture observations use `helper.onEachTick`
  and an explicit stage; never interpret a budget-deferred ray as an absent object.

## In-game wiki and planner speech

- `knowledge/Wiki` lazily caches loaded mod roots and server resource locations; startup/global
  datapack sync invalidates it. Generic framework formats: GuideME Markdown under an arbitrary
  `assets/<ns>/<guide folder>/` (default English pages directly in that folder, YAML `item_ids`),
  Patchouli JSON entries in assets/data, FTB Quests SNBT in `config/ftbquests/quests`, and English
  item/block names plus `.desc`/`.tooltip`/`.info` language lines. No content-mod names in body code.
- Bridge `wiki {query}` and `/blocknet wiki <query>` share search, up to three source-labelled
  snippets under 120 words. `/blocknet wiki-status` lists indexed/skipped formats. Missing optional
  formats stay quiet. `WikiGameTests` checks the real loaded guide fixture and nonsense queries.
- `brain/persona.md` is the stable character prompt shared by actor and planner; persona.json
  keeps only defaultLanguage. No phrase pools or timer fillers. First model preamble line streams
  immediately from Codex agent-message deltas/items or Claude text deltas, before tools/JSON;
  strip that line before normal processing. Stop/death suppress stale speech. Trivial rules stay
  silent/model-free. Player hint acknowledgement is the classifier's model-written reply.
- One non-moving scene is captured alongside planner preparation. Planner playbook prepares coal,
  food, torches and smelting fuel after stone tools, uses seen ore/cliffs/caves before bounded
  explore_caves, and mine_ore at a knowledge peak as fallback. Use knowledge/wiki for unfamiliar items.
- Steps accept optional budget_min and else. On failure/timeout run else once with the same done
  condition and a fresh step budget within the original plan deadline; replan only if fallback fails.
  Validate planner tool_gaps [{want,why}] (32 entries, nonempty <=200/500 characters), append with
  source: planner to tool-feedback.jsonl and session log; report.js displays that source.
- ExploreCavesTask uses observed cave/dark evidence, fresh look if absent, and reports no cave seen.
  Walk routes use observed air + sturdy floors, single-block elevation changes and natural cave
  ceilings, avoiding lava/hostiles. Gather wall ore stays within reach and does not walk for drops;
  reuse GatherTask's vein following. Place owned torches at light <7 through controller.place.
  Five-minute body budget reserves return time; replay safe breadcrumbs, report a blocked return.
  Progress every 200 ticks, one terminal report including actual gains, distance and torches.
- This live-play change adds no tests or bench rules. Adapt existing speech tests and gathered-count
  assertions (at least requested, with actual count in report). Verify build with deployment excluded,
  one passing full GameTest run and existing Node suites. This overrides the earlier three-run workflow.
- Planner MCP permits only `wiki`/`knowledge`. Codex planner uses `web_search="live"`; actor uses
  `"disabled"`. Claude planner uses only built-in `WebSearch` plus Blocknet MCP (installed CLI
  supports this with `--tools`/`--allowedTools`); actor has no built-in tools. Planner prefers local
  sources before Minecraft wiki/mod docs. No live model calls are needed for Node tests.

## Map, radar and hunting (ADR-0011)

- `map {focus?}` / `/blocknet map [name] [focus]`: 9x9 chunk grid, structures `v#`, caves `k#`
  (entrance = sky-lit cave air), creatures `e#` at live positions. Ores never go on the map; the
  hidden-ore paired tests must stay identical.
- `chunk.getHeight(type, x, z)` is the TOP block, not the first free one: feet are `+1`.
- `goto` takes `v#`/`k#`/`e#`; a built vision object goes to `WorldMap.groundBeside` (lowest ring
  around the footprint) so the bot never ends on a roof. `attack` hunts via FOLLOW with the live
  position, beginDanger in reach, and reports `killed <ref> <type>` or `failed: lost ...`.
- `obtain` for ore tries the nearest mapped cave entrance (64 blocks) with `ExploreCavesTask`
  before `MineOreTask`; `explore_caves` accepts `cave k#`.
- The GameTest world shares one MapMemory with stale/neighbouring "caves" (barrier ceilings,
  tunnels): map tests rescan their own chunks via `MapScanner.scan` and pick their cave by bounds.
- Keep the scanner off the vision budget (`lookAndBackgroundRespectVisionBudget`, p95 < 1.5 ms):
  three chunks per tick already broke it; the radar ticks after the vision timestamp.

## Full perception and the slim actor (ADR-0012)

- `perception` in `blocknet_ai-bridge.json`: `full` (default) or `assisted`; read at spawn into
  `BotPlayer.fullPerception`. `TestArena.spawnBot` sets assisted, so honest fixtures and hidden-ore
  pairs stay valid; full-perception tests call `bot.fullPerception(true)`.
- `map/BlockFinder.nearest` walks loaded chunks only, skipping sections via `maybeHas`. `ObtainTask`
  in full mode mines the nearest known ore not touching lava, then falls back to caves/mine_ore.
- Items: radar refs `i#` (24 blocks), `pickup [ref|item|all]` (`PickupTask`), `attack` loots its kill
  ("killed e3 sheep; picked up 1 white_wool"). `goto` accepts a player name.
- Brain: `brain/tool-policy.js` ACTOR_TOOLS filters MCP tools/list and the planner tool listing; calls
  are not rejected (tests and typed commands still use low-level tools). `killed <type> >= N` done
  conditions count `killed e# <type>` task results via `PlanRunner.recordKill`.

## Give and live dialogue recovery

- `give <player> <item|all> [count]` / `drop <item|all> [count]` use `GiveTask`: walk/aim then
  select inventory slots and `ServerPlayer.drop(boolean)` (Q), splitting partial stacks first.
  `gave` confirms tossing, not receipt. Flying player goals fall back to `WorldMap.groundNear`
  within 4 horizontal blocks. `GiveGameTests` uses vision_field and clears both inventories.
- Keep reactive chat independent of actor/MCP waits. New orders cancel body + backend with a
  generation change; questions leave the task running. Explicit ru/en stops bypass the model.
  Actor/planner/classifier receive six speaker-labelled dialogue lines, including preambles.
  Planner State lists online players; reject pos at the bot's own start. Direct arrival reports
  may satisfy player-position conditions; inv/killed remain strict. Idle actors time out after
  45 s without a tool/final reply (preamble is not progress), logging bug feedback with request.
- After registry edits sync tools; run `node --test brain/test/ brain/bench/test/`, the offline
  build with deployment excluded, and one full passing GameTest suite with log inspection.
- Transfer = probe destination room per source kind (no walking between probes), withdraw each kind up
  to min(room, inventory room), deposit exactly the cargo; expected source = plan minus this trip's
  take (a memory snapshot can already show someone else's change). Deposit filter `loot` keeps the kit.
- Background vision sweeps 360 (frame yaw + 120 * sweep); ObservedMap.MAX_SECTIONS = 2048. Keep the
  vision budget test green after any sensor change.
- Memory is unlimited: `vision/MemoryArchive` (region files under data/blocknet_memory/<uuid>), RAM
  working set ObservedMap.MAX_SECTIONS = 3072, `warm()` every 40 ticks (radius 4 sections) from
  VisionSensor, reads never thaw (CME safety), notable index → `remembered()` and the map line.
  `forEach` covers RAM only. `MemoryArchiveGameTests` covers evict → disk → reload → warm.

## Skill scripts (ADR-0014)

- `brain/skills.js`: runScript (vm sandbox, Proxy `bot` over body tools, camelCase for hyphenated tools,
  JSON args across the realm boundary, stop/budget end the run and freeze the script), library in
  brain/logs/skills (BLOCKNET_SKILLS for tests), MCP tools run_script/save_skill/skill/skills in
  mcp-server.js (actor only), planner gets "Saved skills". Movement stays in the Java body skills.
- `locate` (map/WorldFind: BlockFinder + ItemHandler peek, full perception only), `obtain ... world`
  (controller.worldOnly → ContainerMemory.sources empty), ObtainTask picks up drops after a mined block
  with no gain, plan `done: "ok"`, completion line "✔ <goal>". Never name a tool like a chat verb (find).

## Storage-network terminals (docs/AE2_NETWORK_CONTENTS_PLAN.md, stage 1)

- `menu {filter?, page?}` appends a `network` section via `control/NetworkMenus` when an adapter
  handles the open menu: ready (exact long amounts, craftable-only apart), empty, disconnected
  ("contents unknown, not empty") or pending (no server broadcast yet). Pages `<menu>.<rev>.<off>`.
- Mod adapters live in `compat/<modid>/`, registered by name in `compat/Compat` only when the mod
  is loaded; their mixins go in `compat/mixin/<modid>/` (the whole `compat.mixin` package belongs
  to Mixin, never reference classes there) and `blocknet_ai.compat.mixins.json`, gated by
  `CompatMixinPlugin` on the package's mod id. The fixture mod is `compileOnly` + `localRuntime`.
- Read only what the menu already prepared for its player; never reach grids, drives or cells.
  `NetworkMenuGameTests` builds the network in the scene and lets the bot place cable/terminal.
- Stage 2: adapters also `pickup`/`putCarried` through the terminal's own click handler (serials it
  already sent). `ContainerTask` switches to network mode for withdraw/deposit/look-in and planner
  withdrawals (waits 40 ticks for a booting grid). `ContainerMemory` entries with `network` hold
  stored network items; `sources()` keeps only the nearest network terminal. No autocrafting yet.

## Reflexes, multi-part blocks, network autocraft (2026-10-29 night)

- Hunger reflex (`BotController.tickHunger`, after danger): food < 14, or hurt with food < 18, eats
  the best non-harmful food, pauses the task and pathing (`ownsHands()`), then swaps the tool back.
  `task == EAT`, open menus and container tasks are left alone. `ReflexGameTests` covers it.
- `partAims(pos)`: a visible point per box of a multi-box outline (cable + terminal). `use` retries
  another part when a click did nothing (`!consumesAction`); `ContainerTask.open` retries when no menu
  opened and the held item was not used up (never repeat a placing click). Aim rays go 0.05 past the
  face: a ray ending exactly on a surface misses it.
- `autocraft {ref|x,y,z, item, count}` -> `ContainerTask` AUTOCRAFT -> `NetworkMenus.Adapter.requestCraft`
  (AE2: terminal `AUTO_CRAFT` click -> `CraftAmountMenu.confirm(amount, false, autoStart=true)`), then
  `craftOutcome` each tick: submitted / short of X / refused / no CPU. Reports "autocraft submitted";
  the result is withdrawn later. GameTest fixtures that need AE2 classes live in
  `compat/ae2/Ae2TestFixtures` and are called by name.
- Brain: `--planner-strong backend:model` replans with a stronger planner after `ESCALATE_AFTER` (2)
  failed plans for one goal; without it nothing changes.


## Parts, pattern encoding (2026-10-29)

- `control/BlockParts` (generic SPI, AE2 adapter lists `IPartHost` parts per side): scene lines show
  `[parts: north ae2:pattern_encoding_terminal, core ...]` for single-block objects; only loaded chunks.
- `encode_pattern {item}` on an open pattern encoding terminal: body picks a server crafting recipe
  (preferring inputs the bot/network has); the AE2 adapter does what JEI's transfer + Encode send
  (`receiveClientAction("setMode"/"clear"/"encode")`, `setFilter` on grid fake slots), moves a blank
  pattern in by cursor clicks, then quick-moves the encoded pattern to the inventory.
- `use` closes a leftover menu first (a stale CPU screen hid the terminal); `place` of a part next to
  a block that did not take it reports the NEW block position.
- Known: `rememberedCliffRefWalksAndReobserves` started failing on 2026-10-29 morning runs (gather
  picks remembered stone 46 m away at a fixed offset); not caused by these changes per bisection.

## Voice (2026-10-29)

- Body: `voice/VoiceInput` collects 48 kHz frames per speaker, ends a phrase after 600 ms silence,
  emits `voice` events {from, bots in 24 blocks, ms, wav 16 kHz base64, pointed_*}; `voice/VoiceOutput`
  plays brain WAVs. `compat/voicechat/BlocknetVoicePlugin` (@ForgeVoicechatPlugin, loaded only by
  Simple Voice Chat) decodes Opus and gives each bot an entity audio channel. API is compileOnly from
  maven.maxhenkel.de (`voicechat_api_version`). Bridge tools `speak`/`heard` are internal (not actor tools).
- Brain: `brain/voice.js` (`Hearing` = whisper-server child process, `Speech` = Piper per reply,
  hallucination filter, `speakable`). `setup-voice.ps1` installs them. `VoiceGameTests`, `voice.test.js`.
- Agents may not run downloaded binaries here; live voice checks are the user's.

## Survival reflex, one tree, faster talk (2026-10-29)

- `BotController.tickSurvival` runs before danger: under water with air < 120/300, or in lava, the
  task pauses (`ownsHands`), the bot holds jump and walks to the nearest open-air water surface
  (`surface()`, column scan, no ceiling) or dry sturdy footing (`dryFooting()`), emits moment
  `emergency` once; the brain `shout()`s one short cry through the chat model (15 s cooldown).
  `ReflexGameTests` drowning/lava tests assert `controller().escaping()` actually fired.
- `gather {ref}` without count (count 0 through the command) takes that whole observed object and
  stops ("the whole t1 oak tree"); a pointed `p#` maps to the observed object around that block.
  `gather` is an actor tool again; prompt says "this tree" = gather ref, obtain = totals.
- Brain: chat role effort `minimal`; CompactState always carries vitals (health/food/air/in water);
  persona forbids service-speak and echoing the order. Voice phrase pause 450 ms.

## Live log fixes: craft cursor, beds, voice switch (2026-10-29 late morning)

- Idle Automatone `InventoryBehavior` swaps a building block (logs count) into hotbar slot 8 every
  tick. It now skips while the player has a stack on the cursor or a non-inventory menu open; a
  2x2 craft lost its log slot to dirt otherwise ("inventory changed while returning cursor").
  `CraftExecutor.returnCarried` also falls back to any main-inventory slot that takes the stack.
  `craftsWhileIdlePathingWantsBuildingBlocksInHotbar` reproduces the live hotbar.
- `ObtainTask` hunts a live passive radar creature whose own loot table (`Knowledge.drops(entity)`;
  a sheep's depends on its colour) drops the goal item, then picks up drops. Creature drops join
  the forecast sources, so a bed needs white wool from sheep instead of a re-dyed wool cycle.
  `lootKill` keeps an owning skill (stop() would cancel it) and reports just "killed e# type".
  `obtainBedHuntsSheepForWool` covers it.
- Brain: planner/chat retry provider-load errors (`sendRetrying`: at capacity, overloaded, 429/5xx)
  twice; the final plan failure is said by the chat model in character (`giveUp`), never raw
  English tool reports. Piper costs ~330 ms per reply; model TTFT (1.5-9 s on 10-100k input
  tokens) dominates latency.
- `/blocknet voice [bot] [name|next|list]` and the radial "Сменить голос" send bridge event
  `voice.choose`; the brain lists `<home>/piper/voices/*.onnx`, switches `Speech.voice` at once,
  answers in the new voice and stores the file name in `voices/chosen.txt` (read by `voiceConfig`).
  `setup-voice.ps1 -Voices all` installs dmitri, denis, irina, ruslan (published SHA-256).
- Quick mouth (`BrainSession.quickReply`, `--quick`, default actor model at `low`): idle-path player
  orders (not rules, typed tool syntax or reflection) get one line from a persona-only tool-free
  session; `opened(by)` lets the first of quick line / model preamble speak per player message and
  drops the other. Classifier paths during plans/busy actors still reply themselves. Session logs:
  `quick_reply`, `voice_warm`. Across 20 sessions (to 10-29): actor turns avg 22 s / 44.6k input
  tokens; of 14 plan failures ~10 were body skills failing, ~4 vague free-text actor steps.

## The exam (2026-10-29)

- `ExamGameTests`: 16 optional GameTests (`required = false`), one planner step each through
  `BridgeTools.run`, `vision_field` "meadow" (river z 30-34 three deep, cliffs with coal/iron at
  x=0, oaks), the live inventory (`liveBag`), full perception, a real `ServerPlayer` (`human`) for
  come/follow (they reject bots). Graded by the brain's failure regex plus a world check; log line
  `EXAM <id> PASS|FAIL`. `node brain/bench/exam-report.js build/gametest.log [--record]`.
  Baseline 11/16 -> 16/16 the same day. A new live failure becomes an exam order first.
- Exam worlds put ore near other tests: assert a test's own blocks are found first, not counts.
- Fixed through the exam: loot tables include referenced tables (`minecraft:loot_table` entries;
  a coloured sheep's table refers to `entities/sheep` for mutton); obtain's recipe forecast adds
  what obtain can always fetch (a log, cobblestone, dirt, every `Knowledge.oreItems()`), so torches
  use plank sticks and coal, not bamboo and charcoal; `CraftingCalculation.fuelRank`: coal, then
  logs/planks, then other stackables; never tools/armor (unless a remainder: lava bucket), chests,
  barrels, crafting tables; `CraftExecutor.queueSpareFuel` adds bag fuel when the furnace went out
  between batches (planned carry-over heat burns away); Automatone `InventoryBehavior` runs only
  while a path is active (it swapped a furnace out of the hand every tick).
