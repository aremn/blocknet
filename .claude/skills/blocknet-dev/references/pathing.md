# Pathfinding: extracting Automatone

Decision: ADR-0004. Section 6 of `docs/ARCHITECTURE.md` has the keep/drop list. This file is the
practical how-to.

## Source

- Repo: `https://github.com/shakey2/PlayerEngine`, branch `1.21.1-arch-persistentdata`
  (release 1.4.0, July 2026). License LGPL-3.0-or-later (Baritone headers).
- Clone for reading into `_src/` (git-ignored):
  `git -c core.longpaths=true clone --depth 1 -b 1.21.1-arch-persistentdata https://github.com/shakey2/PlayerEngine.git _src/pe`
  then `git -C _src/pe config core.longpaths true`.
- Code lives in `common/src/main/java/com/player2/playerengine/automaton/` (~279 files, ~28k lines),
  already on Mojang names, built with Architectury.

## Target layout

```
pathing/                      Gradle subproject, LGPL-3.0-or-later
  build.gradle                net.neoforged.moddev, neoForge version only (no runs, no mods block)
  LICENSE (LGPL-3.0 text) + COPYING (GPL-3.0 text)
  src/main/java/dev/blocknet/pathing/...   moved packages, Baritone headers kept
mod/build.gradle              implementation project(':pathing') + jarJar, so the LGPL code ships
                              as a separate, replaceable nested jar
settings.gradle               include 'pathing'
gradle.properties             mod_license=MIT AND LGPL-3.0-or-later once it ships
```

Copy, never mix: nothing from `pathing/` gets pasted into MIT files.

## Keep

- `pathing/calc` (A*), `pathing/movement` + 8 movements, `pathing/path` (executor),
  `api/pathing/goals`, `behavior/PathingBehavior`, `behavior/LookBehavior`,
  `utils/InputOverrideHandler`, `BlockStateInterface`, `BlockBreakHelper`, `BlockPlaceHelper`,
  `ToolSet`, settings classes, `CustomGoalProcess`, `FollowProcess`. Later maybe `BuilderProcess`.

## Drop

- `MineProcess`, `GetToBlockProcess`, `ExploreProcess`, `cache/WorldScanner`, `ContainerMemory`:
  they find blocks by scanning chunks (x-ray, against ADR-0002).
- `FishingProcess`, `CustomFishingBobberEntity`, client `GuiClick`, `PathRenderer`, `command/**`
  (the only `player2api` import is in `command/defaults/DefaultCommands`).

## Replace

| In PlayerEngine | In Blocknet |
|---|---|
| `PlayerEngine.LOGGER/getExecutor/MOD_ID/bucket sets` (25 files) | small `PathingHost` interface implemented by the mod |
| Architectury `CommandRegistrationEvent`, `Platform` | NeoForge `RegisterCommandsEvent`, `ModList`/`FMLPaths` |
| `IBaritone.KEY` (`EntityComponentKey`) | a field on `BotPlayer` or a NeoForge `AttachmentType` |
| `LivingEntityInventory`, `LivingEntityInteractionManager`, `I*Provider` | `BotHands` interface over the real `Inventory` and `ServerPlayerGameMode` (users: `IEntityContext`, `EntityContext`, `EntityInteractionController`, `InventoryBehavior`, `CalculationContext`, `MovementHelper`, `MovementFall`, `BlockPlaceHelper`, `ToolSet`) |
| `PlayerPlacedBlockStore` | `BlockProtection` interface |
| mixins `baritone/*` | drop mob/bucket/itemstack ones; `ServerChunkCache` accessor → public `getChunkNow` if it exists in 1.21.1 (check sources) |
| Fabric `@Environment` annotations | remove |

`InputOverrideHandler` writes `xxa`/`zza`/jump/sneak and rotations: route it through
`BotController` so there is one owner of the head and legs. `BotPlayer.tick()` already runs physics
after the controller sets inputs.

## Status (2026-10-27)

Navigation now compares walking/mining/building in one search when blocks exist (NORMAL -> FALL -> CLOSEST); placement costs 8 ticks, breaking adds 25 to player mining time (inventory tools, attributes, effects; water and airborne slowdowns stay per movement). `RouteChoiceGameTests` covers the tree (parent 196 vs 48-49 ticks, budget 120), stone-wall detour and mining estimates; player-build protection remains future work.

Steps 1-4 are done: extraction, `BotHands` + `PlayerInteractionController`, GameTests (around a
wall, onto a ledge, digging through), `goto` and approach-to-reach use the pathfinder. Two surprises:
vanilla `getChunkNow` is null off the server thread (fixed with an access transformer, see
`LoadedChunks`), and inputs must be re-applied every tick. Still open: general gap,
lava and door tests; a real `BlockProtection` for player builds.

## Order of work (original plan)

1. `spike/automatone-extract`: copy the keep-list, make it compile in `pathing/` without
   PlayerEngine. Count what breaks before fixing, report to the user.
2. `BotHands` adapter; drive one `GoalBlock` path from a debug command.
3. GameTests (bigger arena template if needed): walk around a wall, cross a 1-block gap, pillar up
   2 blocks, dig down 3, avoid lava, open a door. Assert arrival and tick budgets.
4. Replace straight-line walking in `BotController.goTo` and in approach-to-reach for mine/use.
5. Then: ADR update if anything diverged, docs, `mod_license`, jarJar.

## Honesty note

The pathfinder reads real terrain near the bot; that is an accepted compromise (ADR-0002). Targets
and search directions must still come from what the bot has seen, never from the pathfinder's view.
