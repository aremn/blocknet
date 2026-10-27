package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One bounded action: visible resources, or a straight descending staircase through matching ground. */
final class GatherTask {
    private final BotPlayer bot;
    private final BotController controller;
    private final Item item;
    private final int count;
    private final int initial;
    private final Direction forward;
    private BlockPos step;
    private boolean tunneling;
    private int ticks;
    private int lastCount;
    private int emptyMines;

    GatherTask(BotPlayer bot, BotController controller, Item item, int count) {
        this.bot = bot;
        this.controller = controller;
        this.item = item;
        this.count = count;
        initial = held();
        lastCount = initial;
        forward = bot.getDirection();
    }

    private String name() { return BuiltInRegistries.ITEM.getKey(item).toString().replace("minecraft:", ""); }
    String describe() { return "gathering " + count + " " + name(); }

    boolean checkTimeout() {
        if (++ticks <= 20 * 60 * 10) return true;
        controller.gatherFinished("failed: gather timed out");
        return false;
    }

    private int held() {
        int total = 0;
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    void collected() {
        int now = held();
        emptyMines = now > lastCount ? 0 : emptyMines + 1;
        lastCount = now;
    }

    /** Inspect loot only after a ray reaches the block; no hidden block states or chunk search. */
    private boolean matches(BlockPos pos) {
        var state = bot.serverLevel().getBlockState(pos);
        ItemStack tool = bot.getMainHandItem();
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = bot.getInventory().getItem(slot);
            if (candidate.isCorrectToolForDrops(state) && (!tool.isCorrectToolForDrops(state)
                    || candidate.getDestroySpeed(state) > tool.getDestroySpeed(state))) tool = candidate;
        }
        return Block.getDrops(state, bot.serverLevel(), pos, bot.serverLevel().getBlockEntity(pos), bot, tool)
            .stream().anyMatch(stack -> stack.is(item));
    }

    private boolean visible(BlockPos pos) {
        var hit = bot.level().clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(pos),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    void tick() {
        if (held() - initial >= count) {
            controller.gatherFinished("gathered " + (held() - initial) + " " + name());
            return;
        }
        if (emptyMines >= 4) {
            controller.gatherFinished("failed: cannot collect " + name() + "; check tool and inventory space");
            return;
        }
        if (step != null) {
            // Clear headroom, feet, then the next stair, revealing each block before choosing it.
            for (BlockPos pos : new BlockPos[] { step.above(2), step.above(), step }) {
                var hit = bot.level().clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(pos),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos seen = hit.getBlockPos();
                    // Never dig outside the three cells of this stair (e.g. an occluding wall).
                    if (!seen.equals(step) && !seen.equals(step.above()) && !seen.equals(step.above(2))) {
                        noSight();
                        return;
                    }
                    var state = bot.level().getBlockState(seen);
                    if (!state.getFluidState().isEmpty() || state.getDestroySpeed(bot.level(), seen) < 0) {
                        noSight();
                        return;
                    }
                    bot.worldMemory().glance(bot, 4);
                    controller.gatherMine(seen);
                    return;
                }
                if (!bot.level().getFluidState(pos).isEmpty()) { noSight(); return; }
            }
            if (!visible(step.below()) || bot.level().getBlockState(step.below()).getCollisionShape(bot.level(), step.below()).isEmpty()) {
                noSight();
                return;
            }
            if (!controller.craftWalk(Vec3.atBottomCenterOf(step))) return;
            step = null;
        }
        bot.worldMemory().glance(bot, 4);
        BlockPos feet = bot.blockPosition();
        // Prefer exposed resources above the floor before opening a staircase.
        var candidate = bot.worldMemory().nearby(bot, 16).stream()
            .map(entry -> entry.pos()).filter(pos -> pos.getY() >= feet.getY())
            .filter(this::visible).filter(this::matches)
            .min(Comparator.comparingDouble(pos -> bot.position().distanceToSqr(Vec3.atCenterOf(pos))));
        if (candidate.isPresent()) {
            controller.gatherMine(candidate.get());
            return;
        }
        BlockPos ground = feet.below();
        if (tunneling || visible(ground) && matches(ground)) {
            tunneling = true;
            step = feet.relative(forward).below();
            if (step.getY() < bot.level().getMinBuildHeight()) noSight();
            return;
        }
        // A visible surface below the feet can still supply the resource without a tunnel.
        var surface = bot.worldMemory().nearby(bot, 16).stream().map(entry -> entry.pos())
            .filter(this::visible).filter(this::matches).findFirst();
        if (surface.isPresent()) controller.gatherMine(surface.get());
        else noSight();
    }

    private void noSight() {
        controller.gatherFinished("failed: no " + name() + " in sight; look around or go elsewhere");
    }
}
