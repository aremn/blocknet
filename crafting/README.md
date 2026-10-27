# crafting

Read-only crafting calculation for Blocknet, adapted from [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2),
tag `neoforge/v19.2.18`, `src/main/java/appeng/crafting/`.

**License:** GNU LGPL v3.0 or later (`LICENSE`, supplementing GPL v3.0 in `COPYING`).
This differs from the MIT body code. Keep attribution headers; modifications stay LGPL.
The module ships as a replaceable nested `GAMELIBRARY` jar, exactly like `pathing/`.

`CraftingCalculation` adapts AE2's inventory-first tree nodes, recipe process traversal,
transactional child simulation/commit, fuzzy substitutions, ancestor recursion prevention,
container outputs and missing-item fallback. `CraftingSimulationState` adapts AE2's inventory
accounting to eagerly copied player snapshots (original and generated stock stay separate).
The small snapshot makes copying preferable to AE2's lazy network diff/cache.

New code: item/component keys, Minecraft recipe adapters and catalog, station prerequisites,
NeoForge fuel accounting with residual burn ticks, bounds, ordered immutable plan/text output,
validated step aggregation and the recipe index cache. No AE2 runtime dependency or network,
grid, CPU, storage or scheduling types. The MIT module only calls the public library API.

`new CraftingCalculation(level, PlannerCatalogs.get(level)).plan(target, count, snapshot)` runs
on the server thread. The index is built once per recipe set, outside the search deadline;
all search branches and the missing pass share a 50ms cooperative deadline, depth/variant/work
caps. Recipe callbacks are trusted synchronous code, so a callback itself cannot be preempted.
A limit result discards incomplete branches. Missing plans describe hypothetical operations;
only `ok` proves the requested material balance. Stations are reserved, listed separately,
and excluded from `leftovers`; untouched starting inventory is also excluded from leftovers.

See [ADR-0007](../docs/adr/0007-crafting-planner-from-ae2.md) and [development commands](../docs/DEVELOPMENT.md).
