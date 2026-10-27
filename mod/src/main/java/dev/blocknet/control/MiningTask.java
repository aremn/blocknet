// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.vision.VoxelRay;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** One straight excavation, using the controller's survival mining and pickup path. */
final class MiningTask {
    private final BotPlayer bot;
    private final BotController controller;
    private final Direction direction;
    private final int limit;
    private final boolean descending;
    private final Set<Long> knownOres = new HashSet<>();
    private BlockPos feet, step;
    private int completed, ticks;

    MiningTask(BotPlayer bot, BotController controller, Direction direction, int limit, boolean descending) {
        this.bot = bot; this.controller = controller; this.direction = direction;
        this.limit = limit; this.descending = descending; feet = bot.blockPosition();
        bot.worldMemory().observed().forEach(dimension(), (pos, cell) -> {
            if (cell.semantic().category().equals("ore")) knownOres.add(pos.asLong());
        });
    }

    private String dimension() { return bot.level().dimension().location().toString(); }

    boolean checkTimeout() {
        if (++ticks < 20 * 60 * 10) return true;
        done("stopped: timed out"); return false;
    }

    private void done(String reason) {
        String uncovered = bot.vision().objects().stream().filter(o -> o.category().equals("ore"))
            .map(o -> {
                int[] count = {0};
                bot.worldMemory().observed().forEach(dimension(), (pos, cell) -> {
                    if (!knownOres.contains(pos.asLong()) && cell.semantic().category().equals("ore")
                        && cell.semantic().kind().equals(o.kind()) && o.bounds().contains(Vec3.atCenterOf(pos))) count[0]++;
                });
                return count[0] == 0 ? "" : o.kind() + " x" + count[0] + " " + o.ref();
            }).filter(s -> !s.isEmpty()).collect(Collectors.joining(", "));
        controller.miningFinished((descending ? "descended to " + feet.getY() : "tunneled " + completed + " " + direction.getName().substring(0, 1).toUpperCase(java.util.Locale.ROOT))
            + (reason.isEmpty() ? "" : "; " + reason) + "; uncovered: " + (uncovered.isEmpty() ? "none" : uncovered));
    }

    private String fluid(BlockPos pos) {
        var cell = bot.worldMemory().observed().cell(dimension(), pos);
        if (cell == null || cell.stateId() < 0) return "";
        var state = Block.stateById(cell.stateId());
        if (state.getFluidState().isEmpty()) return "";
        return state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water";
    }

    /** Pickup must not walk into a hole before its newly visible support has been checked. */
    boolean collectionWalk(BlockPos broken) { return broken.getY() <= step.getY(); }

    boolean collectionSafe(BlockPos broken) {
        if (!collectionWalk(broken)) return true;
        if (!bot.vision().canObserve()) return false;
        BlockPos support = broken.below();
        var hit = bot.vision().observe(Vec3.atCenterOf(support).add(0, .499, 0));
        String danger = fluid(support);
        if (!danger.isEmpty()) { done("stopped before " + danger); return false; }
        if (hit.end() == VoxelRay.End.UNKNOWN) return false;
        if (hit.end() == VoxelRay.End.HIT && !support.equals(hit.pos())) {
            controller.exploreWalk(feet); return false;
        }
        if (hit.end() != VoxelRay.End.HIT || !support.equals(hit.pos()) || hit.state() == null
            || !hit.state().isFaceSturdy(bot.level(), support, Direction.UP)) {
            done("stopped before large drop or void"); return false;
        }
        return true;
    }

    void tick() {
        if (descending && limit >= feet.getY()) { done(""); return; }
        if (!descending && completed >= limit) { done(""); return; }
        if (step == null) step = descending ? feet.relative(direction).below() : feet.relative(direction);
        if (step.getY() <= bot.level().getMinBuildHeight()) { done("stopped before void"); return; }
        // Descending needs the extra cell above the new two-high opening to enter from above.
        for (int height = descending ? 2 : 1; height >= 0; height--) {
            BlockPos pos = step.above(height);
            if (!bot.vision().canObserve()) return;
            var hit = bot.vision().observe(Vec3.atCenterOf(pos));
            String danger = fluid(pos);
            if (!danger.isEmpty()) { done("stopped before " + danger); return; }
            for (Direction side : Direction.values()) {
                if (!bot.vision().canObserve()) return;
                bot.vision().observe(Vec3.atCenterOf(pos.relative(side)));
                danger = fluid(pos.relative(side));
                if (!danger.isEmpty()) { done("stopped before " + danger); return; }
            }
            if (hit.end() == VoxelRay.End.UNKNOWN) return;
            if (hit.end() == VoxelRay.End.HIT) {
                BlockPos seen = hit.pos();
                if (!seen.equals(pos)) { done("stopped: opening is obstructed"); return; }
                if (hit.state() == null) { done("stopped: too dark"); return; }
                String hint = controller.toolHint(hit.state());
                if (!hint.isEmpty()) { done(hint); return; }
                if (hit.state().getDestroySpeed(bot.level(), seen) < 0) { done("stopped: unbreakable block"); return; }
                controller.gatherMine(seen); return;
            }
        }
        // Support must actually be seen before walking; never step into an unknown drop.
        if (!bot.vision().canObserve()) return;
        BlockPos support = step.below();
        var hit = bot.vision().observe(Vec3.atCenterOf(support).add(0, .499, 0));
        String danger = fluid(support);
        if (!danger.isEmpty()) { done("stopped before " + danger); return; }
        if (hit.end() == VoxelRay.End.UNKNOWN) return;
        if (hit.end() == VoxelRay.End.HIT && !support.equals(hit.pos())) {
            controller.exploreWalk(feet); return;
        }
        if (hit.end() != VoxelRay.End.HIT || !support.equals(hit.pos()) || hit.state() == null
            || !hit.state().isFaceSturdy(bot.level(), support, Direction.UP)) {
            done("stopped before large drop or void"); return;
        }
        if (!controller.exploreWalk(step) || Math.abs(bot.getY() - step.getY()) > .25) return;
        feet = step; step = null; completed++;
    }
}
