package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.Locale;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the bot's head, legs and hands each tick for one simple task at a time.
 *
 * <p>Prototype: walking goes in a straight line with jumps (no pathfinding yet), mining goes through
 * {@code ServerPlayerGameMode} exactly like a client's dig packets, so break speed, reach and drops
 * follow the normal survival rules.
 */
public final class BotController {
    private static final float MAX_TURN_PER_TICK = 30.0F;
    private static final float FACING_TOLERANCE = 5.0F;
    private static final double ARRIVE_DISTANCE = 0.6;
    private static final int STUCK_TICKS = 60;
    private static final int COLLECT_TICKS = 40;
    private static final int MAX_BREAK_TICKS = 20 * 60;

    private enum Task { IDLE, GOTO, MINE_APPROACH, MINE_BREAK, COLLECT }

    private final BotPlayer bot;
    private Task task = Task.IDLE;
    @Nullable
    private BlockPos target;
    private Consumer<String> reporter = message -> {};

    private double bestDistanceSq;
    private int ticksWithoutProgress;
    private float breakProgress;
    private Direction breakFace = Direction.UP;
    private int breakTicks;
    private int collectTicks;
    private int sequence;

    public BotController(BotPlayer bot) {
        this.bot = bot;
    }

    /** Walks to the block position in a straight line. */
    public void goTo(BlockPos pos, Consumer<String> reporter) {
        start(Task.GOTO, pos, reporter);
    }

    /** Walks into reach of the block, breaks it and picks up the drops. */
    public void mine(BlockPos pos, Consumer<String> reporter) {
        start(Task.MINE_APPROACH, pos, reporter);
    }

    public void stop() {
        abortBreaking();
        task = Task.IDLE;
        target = null;
    }

    public String describe() {
        if (task == Task.IDLE || target == null) {
            return "idle";
        }
        String where = target.getX() + " " + target.getY() + " " + target.getZ();
        return switch (task) {
            case GOTO -> "walking to " + where;
            case MINE_APPROACH -> "approaching block at " + where;
            case MINE_BREAK -> String.format(Locale.ROOT, "mining block at %s (%.0f%%)", where, breakProgress * 100.0F);
            case COLLECT -> "collecting drops at " + where;
            case IDLE -> "idle";
        };
    }

    /** Called from {@link BotPlayer#tick()} before physics: sets this tick's inputs. */
    public void tick() {
        bot.zza = 0.0F;
        bot.xxa = 0.0F;
        bot.setJumping(false);
        if (target == null) {
            return;
        }
        switch (task) {
            case GOTO -> tickGoTo(target);
            case MINE_APPROACH -> tickMineApproach(target);
            case MINE_BREAK -> tickMineBreak(target);
            case COLLECT -> tickCollect(target);
            case IDLE -> {}
        }
    }

    private void start(Task newTask, BlockPos pos, Consumer<String> newReporter) {
        stop();
        task = newTask;
        target = pos.immutable();
        reporter = newReporter;
        bestDistanceSq = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        breakTicks = 0;
    }

    private void tickGoTo(BlockPos pos) {
        Vec3 goal = Vec3.atBottomCenterOf(pos);
        if (hasArrived(goal)) {
            finish("arrived at " + pos.toShortString());
            return;
        }
        walkTowards(goal);
        if (isStuck(goal)) {
            fail("stuck on the way to " + pos.toShortString());
        }
    }

    private void tickMineApproach(BlockPos pos) {
        ServerLevel level = bot.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            finish("nothing to mine at " + pos.toShortString());
            return;
        }
        // Small margin so the server's own reach check in handleBlockBreakAction always passes.
        if (!bot.canInteractWithBlock(pos, -0.5)) {
            Vec3 goal = Vec3.atBottomCenterOf(pos);
            walkTowards(goal);
            if (isStuck(goal)) {
                fail("cannot get within reach of " + pos.toShortString());
            }
            return;
        }

        Vec3 center = Vec3.atCenterOf(pos);
        lookAt(center);
        if (!isLookingAt(center)) {
            return;
        }
        Vec3 eye = bot.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        if (hit.getType() == HitResult.Type.BLOCK && !hit.getBlockPos().equals(pos)) {
            fail("view of " + pos.toShortString() + " is blocked by " + level.getBlockState(hit.getBlockPos()).getBlock().getName().getString());
            return;
        }
        breakFace = hit.getType() == HitResult.Type.BLOCK
            ? hit.getDirection()
            : Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);

        selectBestTool(state);
        bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, breakFace, level.getMaxBuildHeight(), sequence++);
        bot.swing(InteractionHand.MAIN_HAND);
        breakProgress = state.getDestroyProgress(bot, level, pos);
        breakTicks = 0;
        task = Task.MINE_BREAK;
    }

    private void tickMineBreak(BlockPos pos) {
        ServerLevel level = bot.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            task = Task.COLLECT;
            collectTicks = COLLECT_TICKS;
            bestDistanceSq = Double.MAX_VALUE;
            ticksWithoutProgress = 0;
            return;
        }
        if (++breakTicks > MAX_BREAK_TICKS) {
            abortBreaking();
            fail("gave up mining " + pos.toShortString() + ": too slow without a proper tool");
            return;
        }
        lookAt(Vec3.atCenterOf(pos));
        breakProgress += state.getDestroyProgress(bot, level, pos);
        bot.swing(InteractionHand.MAIN_HAND);
        if (breakProgress >= 1.0F) {
            // Same packet a client sends when its local progress reaches 100%; the server validates the timing.
            bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, breakFace, level.getMaxBuildHeight(), sequence++);
            breakProgress = 0.0F;
        }
    }

    private void tickCollect(BlockPos pos) {
        Vec3 goal = Vec3.atBottomCenterOf(pos);
        if (!hasArrived(goal)) {
            walkTowards(goal);
        }
        if (--collectTicks <= 0 || (hasArrived(goal) && collectTicks < COLLECT_TICKS - 10)) {
            finish("mined block at " + pos.toShortString());
        }
    }

    private void walkTowards(Vec3 goal) {
        double dx = goal.x - bot.getX();
        double dz = goal.z - bot.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        turnTowards(yaw, 0.0F);
        if (Math.abs(Mth.wrapDegrees(yaw - bot.getYRot())) < 45.0F) {
            bot.zza = 1.0F;
        }
        if ((bot.horizontalCollision && bot.onGround()) || bot.isInWater()) {
            bot.setJumping(true);
        }
    }

    private boolean hasArrived(Vec3 goal) {
        double dx = goal.x - bot.getX();
        double dz = goal.z - bot.getZ();
        return dx * dx + dz * dz <= ARRIVE_DISTANCE * ARRIVE_DISTANCE && Math.abs(goal.y - bot.getY()) < 1.0;
    }

    private boolean isStuck(Vec3 goal) {
        double distanceSq = bot.position().distanceToSqr(goal);
        if (distanceSq < bestDistanceSq - 0.01) {
            bestDistanceSq = distanceSq;
            ticksWithoutProgress = 0;
            return false;
        }
        return ++ticksWithoutProgress > STUCK_TICKS;
    }

    private void lookAt(Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
        turnTowards(yaw, pitch);
    }

    private boolean isLookingAt(Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        Vec3 wanted = point.subtract(eye).normalize();
        Vec3 facing = bot.getViewVector(1.0F);
        double angle = Math.toDegrees(Math.acos(Mth.clamp(wanted.dot(facing), -1.0, 1.0)));
        return angle <= FACING_TOLERANCE;
    }

    /** Turns the head toward the wanted angles, no faster than a player could. */
    private void turnTowards(float yaw, float pitch) {
        float newYaw = bot.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - bot.getYRot()), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        float newPitch = Mth.clamp(bot.getXRot() + Mth.clamp(pitch - bot.getXRot(), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK), -90.0F, 90.0F);
        bot.setYRot(newYaw);
        bot.setXRot(newPitch);
        bot.setYHeadRot(newYaw);
    }

    /** Picks the hotbar slot that breaks the block fastest while still dropping it. */
    private void selectBestTool(BlockState state) {
        Inventory inventory = bot.getInventory();
        int best = inventory.selected;
        float bestScore = toolScore(inventory.getItem(best), state);
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            float score = toolScore(inventory.getItem(slot), state);
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        inventory.selected = best;
    }

    private static float toolScore(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        boolean drops = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return drops ? speed + 1000.0F : speed;
    }

    private void abortBreaking() {
        if (task == Task.MINE_BREAK && target != null) {
            ServerLevel level = bot.serverLevel();
            bot.gameMode.handleBlockBreakAction(target, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, breakFace, level.getMaxBuildHeight(), sequence++);
        }
    }

    private void finish(String message) {
        task = Task.IDLE;
        target = null;
        reporter.accept(message);
    }

    private void fail(String message) {
        task = Task.IDLE;
        target = null;
        reporter.accept("failed: " + message);
    }
}
