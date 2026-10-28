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
| `vision/` | MIT `VoxelRay` (loaded-section DDA, air jumps, exact partial VISUAL shapes), reloadable `OpacityTable`, budgeted jittered `VisionSensor` (160x90 background/frontal/panorama/entities, budgeted exposed-neighbour rays after breaks), `ObservedMap` (512-section LRU, palette persistence), tag/property `Semantics`, incremental `ObjectGraph`, evidence-only `Frontiers`, compact ranked `Scene` (`look [focus]`, instant `scene`, deltas), rolling `VisionTiming`; `/blocknet see [name]` prints debug evidence and timings |
| `state/` | `ItemDescriber`/`ToolFacts`: generic item facts and vanilla tool probes; `BodyState`/`BodyStateTracker`: on-demand compact sections, independent revisions, bounded inventory diff history (ADR-0006); no transport |
| `command/` | `/blocknet` debug commands; the bot name is optional (nearest bot) |
| `bridge/` | `BridgeConfig` (localhost port/token), `BridgeServer` (JSON-RPC lines, lifecycle/chat/death, server-thread dispatch), `BridgeTools` (one tool registry reusing commands and their reports; independent changes baselines) |
| `client/` | Optional client side: radial menu on the mouse wheel; only sends `/blocknet` commands |
| `mixin/` | `PlayerListMixin` installs `BotPacketListener` during `placeNewPlayer` |
| `gametest/` | GameTests on `arena`/`arena_tall`; sixteen `VisionGameTests` on the 48x24x48 `vision_field` (helper floor at y=0); `skyAccess = true` keeps daylight deterministic, the dark-room test builds its own roof; `TestArena` sets the bare GameTest PlayerList view distance to 4 chunks |

`brain/` is dependency-free Node 22 ES modules: `body.js` is the reconnecting TCP client,
`mcp-server.js` wraps tools with task-outcome waiting, `rules.js` routes short ru/en orders,
`brain.js` queues chat and builds Claude Haiku arguments/sessions/logs, and `prompt.md` is stable.
Run `node brain/brain.js --bot Kirill --config <minecraft>/config/blocknet_ai-bridge.json`;
dev config defaults to `mod/run/config/blocknet_ai-bridge.json`. Tests: `node --test brain/test/`.
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
- Run `build` and the GameTests before every commit; push the branch after committing so the user
  sees it on GitHub (CI builds every push). PRs are merged by squash, or rebase when the commits are
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
