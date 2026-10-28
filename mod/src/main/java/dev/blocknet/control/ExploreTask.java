// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.vision.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Walk only through evidenced free cells with an observed safe floor. No world search/pathfinder. */
final class ExploreTask {
    private final BotPlayer bot;
    private final BotController controller;
    final Set<String> before;
    private List<BlockPos> route = List.of();
    private final BlockPos origin;
    private final Vec3 toward;
    private final int distance;
    private int index, ticks;
    private final List<Vec3> hostiles;
    private final List<Vec3> lava;
    ExploreTask(BotPlayer bot, BotController controller, String direction, int distance) {
        this.bot = bot; this.controller = controller;
        before = controller.scene().refs();
        hostiles = bot.vision().entities().stream().filter(e -> e.category().equals("hostile")).map(VisionSensor.EntitySeen::position).toList();
        lava = bot.vision().objects().stream().filter(o -> o.category().equals("lava")).map(ObjectGraph.ObjectView::center).toList();
        origin = bot.blockPosition();
        this.distance = distance;
        // A wall at 22m still leaves 21m of useful open space. Do not discard that direction.
        var frontier = bot.vision().frontiers().stream().filter(f -> !f.caveMouth() && (!f.blocked() || f.knownDistance() > 1))
            .filter(f -> direction.equalsIgnoreCase("auto") || f.direction().equalsIgnoreCase(direction))
            .max(Comparator.comparingDouble(f -> f.knownDistance() * .5 - risk(Vec3.atCenterOf(f.unknownStart())))).orElse(null);
        toward = frontier == null ? Vec3.ZERO : Vec3.atCenterOf(frontier.unknownStart())
            .subtract(bot.position()).multiply(1, 0, 1).normalize();
        controller.trace("explore", frontier == null ? "no open frontier " + (direction.equalsIgnoreCase("auto") ? "anywhere" : "to the " + direction)
            + " in what I have seen; nothing to walk toward"
            : "heading " + frontier.direction() + " toward the edge of what I know at " + frontier.unknownStart().toShortString()
                + " (" + Math.round(frontier.knownDistance()) + "m of known ground" + (frontier.blocked() ? ", partly blocked" : "") + ")");
    }

    private double score(BlockPos pos) {
        Vec3 delta = Vec3.atCenterOf(pos).subtract(Vec3.atCenterOf(origin));
        return delta.dot(toward) * 2 - delta.length() * .25 - risk(Vec3.atCenterOf(pos));
    }

    private void plan() {
        BlockPos start = bot.blockPosition();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        parents.put(start, start); queue.add(start);
        BlockPos best = start;
        double bestScore = score(start) + .1;
        while (!queue.isEmpty() && parents.size() < 4096) {
            BlockPos pos = queue.remove();
            double score = score(pos);
            if (score > bestScore) { best = pos; bestScore = score; }
            for (Direction side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                // One-block steps up and down, as in cave walking: natural ground is rarely flat.
                for (int dy : new int[] {0, -1, 1}) {
                    BlockPos next = pos.relative(side).above(dy);
                    if (parents.containsKey(next) || next.distSqr(origin) > distance * distance || !safe(next)) continue;
                    if (dy > 0 && !empty(pos.above(2))) continue;
                    if (dy < 0 && !empty(next.above(2))) continue;
                    parents.put(next, pos); queue.add(next);
                }
            }
        }
        List<BlockPos> reverse = new ArrayList<>();
        for (BlockPos pos = best; !pos.equals(start); pos = parents.get(pos)) reverse.add(pos);
        Collections.reverse(reverse); route = List.copyOf(reverse); index = 0;
        controller.trace("explore", route.isEmpty() ? "no safe observed step from " + start.toShortString()
            + " (every neighbour is unknown, unsafe or a drop)" : "route of " + route.size() + " observed safe cells to " + best.toShortString());
    }

    private boolean empty(BlockPos pos) {
        return bot.worldMemory().observed().empty(bot.level().dimension().location().toString(), pos);
    }
    private double risk(Vec3 point) {
        double risk = 0;
        for (var position : hostiles) risk += Math.max(0, 16 - point.distanceTo(position)) * 4;
        for (var position : lava) risk += Math.max(0, 8 - point.distanceTo(position)) * 8;
        return risk;
    }
    private boolean safe(BlockPos pos) {
        var map = bot.worldMemory().observed();
        String dim = bot.level().dimension().location().toString();
        var floor = map.cell(dim, pos.below());
        if (!map.empty(dim, pos) || !map.empty(dim, pos.above()) || floor == null || floor.stateId() < 0) return false;
        var state = Block.stateById(floor.stateId());
        return state.getFluidState().isEmpty() && !state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
            && !floor.semantic().category().equals("dark");
    }
    /** Fill camera sampling gaps with nearby eye rays before selecting a walking cell. */
    private void surveyStep() {
        BlockPos feet = bot.blockPosition();
        if (!bot.vision().canObserve()) return;
        bot.vision().observe(Vec3.atCenterOf(feet.above(2)));
        for (Direction side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            BlockPos next = feet.relative(side);
            // Two below for a step down's floor, two above for a step up's headroom.
            for (int dy = -2; dy <= 2; dy++) {
                if (!bot.vision().canObserve()) return;
                bot.vision().observe(Vec3.atCenterOf(next.above(dy)).add(0, dy < 0 ? .499 : 0, 0));
            }
        }
    }

    void tick() {
        if (++ticks > 600 || toward.lengthSqr() == 0) { controller.explored(before); return; }
        if (index >= route.size()) {
            if (!bot.vision().canObserve()) return;
            surveyStep();
            plan();
            if (route.isEmpty()) { controller.explored(before); return; }
        }
        BlockPos next = route.get(index);
        if (!safe(next)) {
            controller.trace("explore", "next cell " + next.toShortString() + " no longer looks safe; stopping here");
            controller.explored(before);
            return;
        }
        if (controller.exploreWalk(next) && Math.abs(bot.getY() - next.getY()) < .6) index++;
    }
}
