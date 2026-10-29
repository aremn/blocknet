// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.knowledge.Knowledge;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Cave walking uses eye evidence; the map (ADR-0011) only finds the entrance. Breadcrumbs reserve time for the return. */
final class ExploreCavesTask extends SkillTask {
    private final Knowledge.Ore ore;
    private final Item drop;
    private final int count, initial;
    private final Map<Item, Integer> before = new LinkedHashMap<>();
    private final List<BlockPos> trail = new ArrayList<>();
    private final Set<BlockPos> visited = new HashSet<>(), exhausted = new HashSet<>(), lit = new HashSet<>();
    private BlockPos mouth, waypoint;
    private boolean looked, returning, walkedToMap;
    private int walked, torches, idleTicks, surveyedAt = -1;
    private boolean entered;
    private String stopReason = "";
    private List<Vec3> lava = List.of(), hostiles = List.of();

    private final String caveRef;

    ExploreCavesTask(BotPlayer bot, Item item, int count, Consumer<String> report) {
        this(bot, item, count, null, report);
    }

    /** {@code caveRef} names a map cave ("k3") to walk into first; null picks what is seen or nearest. */
    ExploreCavesTask(BotPlayer bot, Item item, int count, String caveRef, Consumer<String> report) {
        super(bot, report, 20*60*5);
        this.caveRef = caveRef;
        ore = Knowledge.get(bot.serverLevel()).ore(item);
        drop = ore == null ? item : ore.drop();
        this.count = count; initial = held();
        for (ItemStack stack : bot.getInventory().items) if (!stack.isEmpty()) before.merge(stack.getItem(), stack.getCount(), Integer::sum);
        trail.add(bot.blockPosition()); visited.add(bot.blockPosition());
    }
    private int held() { return bot.getInventory().countItem(drop); }
    @Override String describe() {
        List<String> gains = new ArrayList<>();
        Map<Item, Integer> now = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().items) if (!stack.isEmpty()) now.merge(stack.getItem(), stack.getCount(), Integer::sum);
        now.forEach((item, n) -> { int gain = n-before.getOrDefault(item, 0); if (gain > 0) gains.add(gain + " " + Knowledge.shortId(BuiltInRegistries.ITEM.getKey(item))); });
        return "explored cave " + walked + " blocks; got " + (gains.isEmpty() ? "nothing" : String.join(", ", gains))
            + "; placed " + torches + " torches" + (returning ? "; returning" : "");
    }
    private String dim() { return bot.level().dimension().location().toString(); }
    private boolean safe(BlockPos pos) {
        var map = bot.worldMemory().observed();
        var floor = map.cell(dim(), pos.below());
        if (!map.empty(dim(), pos) || !map.empty(dim(), pos.above()) || floor == null || floor.stateId() < 0) return false;
        var state = Block.stateById(floor.stateId());
        if (!state.getFluidState().isEmpty() || !state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) return false;
        var point = Vec3.atCenterOf(pos);
        return lava.stream().noneMatch(p -> p.distanceToSqr(point) < 9)
            && hostiles.stream().noneMatch(p -> p.distanceToSqr(point) < 25);
    }
    private void survey() {
        var feet = bot.blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) for (int dy = -2; dy <= 3; dy++) {
            if (!bot.vision().canObserve()) return;
            // Each cell must be reached by a ray; unknown/occluded cells never become walkable.
            bot.vision().observe(Vec3.atCenterOf(feet.relative(side).above(dy)).add(0, dy < 0 ? .499 : 0, 0));
        }
    }
    private boolean covered(BlockPos pos) {
        for (int y = 2; y <= 6; y++) {
            var cell = bot.worldMemory().observed().cell(dim(), pos.above(y));
            if (cell != null && Set.of("stone", "dirt").contains(cell.semantic().category())) return true;
        }
        return false;
    }
    private List<BlockPos> route(BlockPos goal, boolean exploring) {
        var start = bot.blockPosition();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); parents.put(start, start); queue.add(start);
        BlockPos best = start; double bestScore = -Double.MAX_VALUE;
        while (!queue.isEmpty() && parents.size() < 2048) {
            var pos = queue.remove();
            double score = exploring ? (visited.contains(pos) ? -1000 : 0) - pos.distSqr(goal)*.15 - start.distSqr(pos)*.1 : -pos.distSqr(goal);
            if (score > bestScore) { bestScore = score; best = pos; }
            for (Direction side : Direction.Plane.HORIZONTAL) for (int dy : new int[]{0, -1, 1}) {
                var next = pos.relative(side).above(dy);
                if (parents.containsKey(next) || next.distSqr(start) > 144 || !safe(next) || exploring && !covered(next)) continue;
                if (dy > 0 && !bot.worldMemory().observed().empty(dim(), pos.above(2))) continue;
                if (dy < 0 && !bot.worldMemory().observed().empty(dim(), next.above(2))) continue;
                parents.put(next, pos); queue.add(next);
            }
        }
        List<BlockPos> reverse = new ArrayList<>();
        for (var pos = best; !pos.equals(start); pos = parents.get(pos)) reverse.add(pos);
        Collections.reverse(reverse); return reverse;
    }
    private void walkToMap(dev.blocknet.map.WorldMap.Target target) {
        walkedToMap = true;
        controller.trace("explore_caves", "walking to " + target.label() + " at " + target.stand().toShortString());
        action(cb -> controller.goTo(target.stand(), cb), m -> {
            // The return trail starts at the entrance, not where the walk began.
            trail.clear(); trail.add(bot.blockPosition()); visited.add(bot.blockPosition());
            looked = false;
        });
    }
    private void turnBack(String reason) { returning = true; stopReason = reason; waypoint = null; idleTicks = 0; }
    @Override void work() {
        // Two minutes (plus distance allowance) remain to walk back, before SkillTask's hard cap.
        if (!returning && (held()-initial >= count || ticks >= 20*60*3 || ticks + trail.size()*80 >= budget-200)) {
            if (busy) { controller.skillAction(controller::stop); busy = false; }
            turnBack(held()-initial >= count ? "" : "budget ended");
        }
        if (busy) return;
        if (mouth == null && caveRef != null && !walkedToMap) {
            var target = dev.blocknet.map.WorldMap.target(bot, caveRef);
            if (target == null || target.stand() == null) {
                complete("failed: " + (target == null ? "no cave " + caveRef + " on the map" : target.label()) + "; " + describe());
                return;
            }
            walkToMap(target);
            return;
        }
        if (mouth == null) {
            mouth = bot.vision().frontiers().stream().filter(f -> f.caveMouth()).map(f -> f.unknownStart())
                .min(Comparator.comparingDouble(p -> p.distSqr(bot.blockPosition()))).orElse(null);
            if (mouth == null) mouth = bot.vision().objects().stream().filter(o -> o.category().equals("dark"))
                .min(Comparator.comparingDouble(o -> o.center().distanceToSqr(bot.position()))).map(o -> o.nearest()).orElse(null);
            if (mouth == null && walkedToMap && looked) {
                // Standing at the entrance the map gave; caves round a corner may still look dark-free.
                mouth = bot.blockPosition();
            }
            if (mouth == null) {
                var entrance = looked && !walkedToMap ? dev.blocknet.map.WorldMap.nearestCaveEntrance(bot, 64) : null;
                if (!looked) { looked = true; action(cb -> controller.look("", false, cb), m -> {}); }
                else if (entrance != null) {
                    controller.trace("explore_caves", "no cave in sight; the map knows " + entrance.label() + ", walking there");
                    walkToMap(entrance);
                }
                else complete("failed: no cave seen; " + describe());
                return;
            }
        }
        lava = bot.vision().objects().stream().filter(o -> o.category().equals("lava")).map(o -> o.center()).toList();
        hostiles = bot.vision().entities().stream().filter(e -> e.category().equals("hostile")).map(e -> e.position()).toList();
        survey();
        if (returning) {
            if (trail.isEmpty()) {
                complete((held()-initial >= count ? "" : "stopped: " + stopReason + "; ") + describe()); return;
            }
            var next = trail.getLast();
            if (bot.position().subtract(Vec3.atBottomCenterOf(next)).horizontalDistanceSqr() <= .09
                    && Math.abs(bot.getY()-next.getY()) < .6) { trail.removeLast(); idleTicks = 0; return; }
            // Use the same evidenced one-cell route on return, including interrupted waypoints.
            var path = route(next, false);
            if (path.isEmpty() || !path.getLast().equals(next)) {
                if (++idleTicks > 80) complete("stopped: return route blocked; " + describe()); return;
            }
            if (controller.exploreWalk(path.getFirst()) && Math.abs(bot.getY()-path.getFirst().getY()) < .6) idleTicks = 0;
            else if (++idleTicks > 200) complete("stopped: return route unreachable; " + describe());
            return;
        }
        if (waypoint != null) {
            if (!safe(waypoint)) { turnBack("unsafe passage"); return; }
            if (controller.exploreWalk(waypoint) && Math.abs(bot.getY()-waypoint.getY()) < .6) {
                trail.add(waypoint); visited.add(waypoint); walked++; waypoint = null; idleTicks = 0;
            } else if (++idleTicks > 200) turnBack("passage unreachable");
            return;
        }
        var feet = bot.blockPosition();
        if (walked % 4 == 0 && surveyedAt != walked) {
            surveyedAt = walked;
            action(cb -> controller.look("", false, cb), m -> {}); return;
        }
        if (bot.level().getMaxLocalRawBrightness(feet) < 7 && bot.getInventory().countItem(Items.TORCH) > 0 && lit.add(feet)) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                var spot = feet.relative(side);
                if (!safe(spot) || BlockPlacement.face(bot, spot, Direction.UP) == null) continue;
                action(cb -> controller.place(new ItemStack(Items.TORCH), spot, Direction.UP, cb), m -> { if (!failed(m)) torches++; }); return;
            }
        }
        if (ore != null) {
            var seen = bot.worldMemory().nearby(bot, 12).stream().filter(e -> !exhausted.contains(e.pos())).filter(e -> {
                var cell = bot.worldMemory().observed().cell(dim(), e.pos());
                return e.pos().getY() >= bot.getBlockY() && bot.canInteractWithBlock(e.pos(), -.5) && cell != null && cell.stateId() >= 0 && ore.blocks().contains(Block.stateById(cell.stateId()).getBlock());
            }).findFirst();
            if (seen.isPresent()) {
                var pos = seen.get().pos(); int n = held();
                action(cb -> controller.gatherCave(BuiltInRegistries.ITEM.getKey(drop).toString(), count-(held()-initial), cb), m -> {
                    if (held() == n) exhausted.add(pos);
                    // Defense may displace the bot even while gathering from a fixed position.
                    if (bot.blockPosition().distSqr(trail.getLast()) > 4) turnBack("gather left the walking route");
                    if (m.contains("almost broken")) turnBack("tool almost broken");
                }); return;
            }
        }
        // Once at the mouth, follow evidence frontiers locally, never a hidden ore coordinate.
        if (covered(feet) && feet.distSqr(mouth) < 36) entered = true;
        boolean inside = entered;
        BlockPos goal = inside ? bot.vision().frontiers().stream().filter(f -> !f.blocked() && f.knownDistance() > 1)
            .map(f -> f.unknownStart()).filter(p -> !visited.contains(p))
            .min(Comparator.comparingDouble(p -> p.distSqr(feet))).orElse(mouth) : mouth;
        var path = route(goal, inside);
        if (path.isEmpty() || inside && visited.contains(path.getLast())) {
            if (++idleTicks < 40) return;
            turnBack("no safe cave frontier"); return;
        }
        waypoint = path.getFirst();
    }
}
