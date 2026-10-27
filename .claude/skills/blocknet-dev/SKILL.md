---
name: blocknet-dev
description: How to work in the Blocknet repository (C:\Projects\blocknet, github.com/aremn/blocknet), a NeoForge 1.21.1 mod where an AI companion plays modded Minecraft as a real server-side ServerPlayer. Use this skill for any change, build, test or git task in this repo, even small ones; adding or changing bot actions, skills, /blocknet commands or radial menu entries; GameTests; running the dev client or server; pathfinding and the Automatone extraction; the brain/protocol; docs and ADRs; commits, branches and pushes. Also use it when the user just says "next step", "add a command to the menu", "make the bot do X" or reports the bot misbehaving in game, since those all mean editing this repo.
---

# Working in Blocknet

Blocknet is an AI companion for modded Minecraft (target: All the Mods 10, Minecraft 1.21.1,
NeoForge 21.1). The **body** is a server-side mod: the bot is a real `ServerPlayer`, so it acts
through the same server code a human player triggers. The **brain** (an LLM, for now Claude Haiku
through `claude -p`) will be a separate process talking to the body over a versioned protocol.

Before designing anything non-trivial, read `docs/ARCHITECTURE.md` and the decisions in
`docs/adr/`. They record what was chosen and why; `docs/DEVELOPMENT.md` has the day-to-day rules.
If a change contradicts an ADR, write a new ADR that supersedes it instead of silently diverging.

## Environment

- Windows, Git Bash. JDK 21 is not the system Java (that is Java 8), so every Gradle call needs
  `export JAVA_HOME="/c/Users/ncpaa/.jdks/jdk-21.0.12.1+1"` first.
- The repo has two Gradle layers: the root (settings, wrapper, `gradle.properties`) and the `mod/`
  subproject. Future modules (`pathing/`, `brain/`, `protocol/`) sit next to `mod/`.
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
| `control/` | `BotController`: one task at a time (goto, mine, use), head turning, aiming, tool choice |
| `command/` | `/blocknet` debug commands; the bot name is optional (nearest bot) |
| `client/` | Optional client side: radial menu on the mouse wheel; only sends `/blocknet` commands |
| `mixin/` | `PlayerListMixin` installs `BotPacketListener` during `placeNewPlayer` |
| `gametest/` | GameTests on the empty `arena` template; `TestArena` helpers |

The LGPL `pathing/` module (Automatone) lives next to `mod/`: `BotController` drives it through
`IBaritone.KEY.get(bot).getCustomGoalProcess()`, `BotPlayer.tick()` ticks it, and it acts through
`BotHands`/`PlayerInteractionController`. It is compile-only for the mod (dev runs get its classes
from the `mods` block, the jar nests it via jarJar) — adding it as `implementation` duplicates its
packages and the game refuses to start.

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
Navigation retries normal, tagged inventory-block building, healthy (>60%) falls up to 5, then nearby intermediate goals; attempt settings are restored.

Done in the basics (ADR-0004): walking around walls, ledges, digging through. `references/pathing.md`
has the history, what was kept and dropped, and the remaining ideas (gaps, pillars with blocks,
lava, doors, protecting player builds).

## Talking to the user

The user writes Russian, casually, and wants results more than explanations. Answer in Russian,
lead with what changed and how to try it in game, mention what you could not verify, and keep
English to code, commands and commit messages.
