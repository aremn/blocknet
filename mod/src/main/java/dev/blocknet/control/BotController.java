package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.Settings;
import dev.blocknet.pathing.behavior.InventoryBehavior;
import dev.blocknet.pathing.api.pathing.goals.Goal;
import dev.blocknet.pathing.api.pathing.goals.GoalBlock;
import dev.blocknet.pathing.api.pathing.goals.GoalGetToBlock;
import dev.blocknet.pathing.api.pathing.goals.GoalComposite;
import dev.blocknet.pathing.api.pathing.goals.GoalNear;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the bot's head, legs and hands each tick for one simple task at a time.
 *
 * <p>Getting somewhere goes through the pathing module (Automatone): it walks around walls, jumps,
 * pillars up, digs through and swims as needed. Mining and
 * right-clicking go through {@code ServerPlayerGameMode} exactly like a client's packets, so break
 * speed, reach, drops and modded block behaviour (including opening their menus) follow the normal
 * survival rules.
 */
public final class BotController {
    private static final float MAX_TURN_PER_TICK = 30.0F;
    private static final float FACING_TOLERANCE = 5.0F;
    private static final double ARRIVE_DISTANCE = 0.6;
    private static final int STUCK_TICKS = 60;
    private static final int COLLECT_TICKS = 40;
    private static final int MAX_BREAK_TICKS = 20 * 60;
    /** How many leaves or plants the bot may clear on the way to one target. */
    private static final int MAX_CLEARED_OBSTACLES = 8;
    /** Give up on a path that has not reached its goal after this long (5 minutes). */
    private static final int MAX_PATH_TICKS = 20 * 60 * 5;

    private enum Task { IDLE, GOTO, MINE_APPROACH, MINE_BREAK, COLLECT, USE }

    private final BotPlayer bot;
    private Task task = Task.IDLE;
    @Nullable
    private BlockPos target;
    private Consumer<String> reporter = message -> {};
    /** The real target and task while the bot clears an obstacle (leaves, grass) in the way. */
    @Nullable
    private BlockPos resumeTarget;
    @Nullable
    private Task resumeTask;
    private int clearedObstacles;
    /** The goal the pathfinder is currently working on for this task, or null if it is idle. */
    @Nullable
    private Goal pathGoal;
    private int pathTicks;
    @Nullable
    private Goal navigationGoal;
    private enum Strategy { NORMAL, BUILD, FALL, CLOSEST }
    private Strategy strategy = Strategy.NORMAL;
    private int closestRange;
    private int navigationTicks;
    @Nullable
    private AttemptSettings attemptSettings;

    /** Only settings changed by an attempt; other per-bot preferences stay intact. */
    private record AttemptSettings(boolean allowPlace, int fallHeight) {
        static AttemptSettings capture(Settings settings) {
            return new AttemptSettings(settings.allowPlace.get(), settings.maxFallHeightNoWater.get());
        }

        void restore(Settings settings) {
            settings.allowPlace.set(allowPlace);
            settings.maxFallHeightNoWater.set(fallHeight);
        }
    }

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

    /** Paths to the exact feet block, trying more costly routes before settling for the closest spot. */
    public void goTo(BlockPos pos, Consumer<String> reporter) {
        start(Task.GOTO, pos, reporter);
        navigationGoal = new GoalBlock(pos);
    }

    /**
     * Comes within two horizontal blocks of where the command source stands. If the source stands
     * on something (ground, leaves on a tree, a roof), that height is the goal; only a source in the
     * air (flying) is projected down to the first block below that can be stood on.
     */
    public void come(Vec3 source, boolean sourceOnGround, Consumer<String> reporter) {
        // Same feet rule as the pathfinder: a hair above the position, so slabs and paths count.
        BlockPos feet = BlockPos.containing(source.x, source.y + 0.1251, source.z);
        start(Task.GOTO, feet, reporter);
        if (sourceOnGround) {
            setComeGoal(feet);
            return;
        }
        ServerLevel level = bot.serverLevel();
        for (int down = 1; down <= 16; down++) {
            BlockPos ground = feet.below(down);
            if (ground.getY() < level.getMinBuildHeight()) {
                break;
            }
            if (canStandOn(level, ground) && isRoom(level, ground.above()) && isRoom(level, ground.above(2))) {
                setComeGoal(ground.above());
                return;
            }
        }
        fail("no ground within 16 blocks below " + feet.toShortString());
    }

    private void setComeGoal(BlockPos spot) {
        target = spot;
        List<Goal> spots = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= 4) {
                    spots.add(new GoalBlock(spot.offset(dx, 0, dz)));
                }
            }
        }
        navigationGoal = new GoalComposite(spots.toArray(Goal[]::new));
    }

    /**
     * A block with a top a player can stand on. Collision, not "sturdy": leaves have no support face
     * (nothing can hang on them) but are solid to walk on, which made the bot ignore treetops.
     */
    private static boolean canStandOn(ServerLevel level, BlockPos pos) {
        var shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5;
    }

    /** Grass, flowers and snow layers still count as room to stand in. */
    private static boolean isRoom(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Walks into reach of the block, breaks it and picks up the drops. */
    public void mine(BlockPos pos, Consumer<String> reporter) {
        start(Task.MINE_APPROACH, pos, reporter);
    }

    /** Walks into reach of the block and right-clicks it with the held item. */
    public void use(BlockPos pos, Consumer<String> reporter) {
        start(Task.USE, pos, reporter);
    }

    public void stop() {
        stopPathing();
        abortBreaking();
        task = Task.IDLE;
        target = null;
        resumeTarget = null;
        resumeTask = null;
        navigationGoal = null;
    }

    public String describe() {
        if (task == Task.IDLE || target == null) {
            return "idle, open menu: " + openMenuDescription();
        }
        String where = target.getX() + " " + target.getY() + " " + target.getZ();
        String clearing = resumeTarget == null ? "" : "clearing the way to " + resumeTarget.toShortString() + ": ";
        return clearing + switch (task) {
            case GOTO -> "walking to " + where;
            case MINE_APPROACH -> "approaching block at " + where;
            case MINE_BREAK -> String.format(Locale.ROOT, "mining block at %s (%.0f%%)", where, breakProgress * 100.0F);
            case COLLECT -> "collecting drops at " + where;
            case USE -> "going to use block at " + where;
            case IDLE -> "idle";
        };
    }

    /** Registry id and size of the menu the bot has open, or "none". */
    public String openMenuDescription() {
        AbstractContainerMenu menu = bot.containerMenu;
        if (menu == bot.inventoryMenu) {
            return "none";
        }
        return BuiltInRegistries.MENU.getKey(menu.getType()) + " (" + menu.slots.size() + " slots)";
    }

    /** Called from {@link BotPlayer#tick()} before physics: sets this tick's inputs. */
    public void tick() {
        // While the pathfinder drives, it owns the legs (it sets the inputs after this).
        if (pathGoal == null) {
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            bot.setJumping(false);
        }
        if (target == null) {
            return;
        }
        switch (task) {
            case GOTO -> tickGoTo(target);
            case MINE_APPROACH -> tickMineApproach(target);
            case MINE_BREAK -> tickMineBreak(target);
            case COLLECT -> tickCollect(target);
            case USE -> tickUse(target);
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
        clearedObstacles = 0;
        strategy = Strategy.NORMAL;
        closestRange = 1;
        navigationTicks = 0;
    }

    private IBaritone pathing() {
        return IBaritone.KEY.get(bot);
    }

    /**
     * Steers toward {@code goal} with the pathfinder. Returns {@link PathState#ARRIVED} once the bot
     * stands in the goal, {@link PathState#FAILED} if no path exists or it takes too long.
     */
    private PathState pathTo(Goal goal) {
        if (goal.isInGoal(bot.blockPosition())) {
            stopPathing();
            return PathState.ARRIVED;
        }
        IBaritone pathing = pathing();
        if (pathGoal == null) {
            // A cancelled background calculation must finish before starting the next attempt.
            if (pathing.getPathingBehavior().getInProgress().isPresent()) {
                return PathState.MOVING;
            }
            if (task == Task.GOTO) {
                configureAttempt();
            }
            pathGoal = goal;
            pathTicks = 0;
            pathing.getCustomGoalProcess().setGoalAndPath(goal);
            return PathState.MOVING;
        }
        if (++pathTicks > MAX_PATH_TICKS) {
            stopPathing();
            return PathState.FAILED;
        }
        // The process goes idle when it reaches the goal or when no path can be found.
        if (!pathing.getCustomGoalProcess().isActive()) {
            stopPathing();
            return goal.isInGoal(bot.blockPosition()) ? PathState.ARRIVED : PathState.FAILED;
        }
        return PathState.MOVING;
    }

    private void stopPathing() {
        if (pathGoal != null) {
            IBaritone pathing = IBaritone.KEY.getNullable(bot);
            if (pathing != null) {
                pathing.getPathingBehavior().cancelEverything();
                pathing.getCustomGoalProcess().onLostControl();
            }
            pathGoal = null;
        }
        if (attemptSettings != null) {
            attemptSettings.restore(pathing().settings());
            attemptSettings = null;
        }
    }

    private enum PathState { MOVING, ARRIVED, FAILED }

    private void tickGoTo(BlockPos pos) {
        if (++navigationTicks > MAX_PATH_TICKS) {
            finishNavigation(pos);
            return;
        }
        Goal goal = strategy == Strategy.CLOSEST ? new GoalNear(pos, closestRange) : navigationGoal;
        PathState state = pathTo(goal);
        if (state == PathState.MOVING) {
            return;
        }
        if (state == PathState.ARRIVED) {
            finishNavigation(pos);
        } else if (strategy == Strategy.NORMAL && hasBuildingBlocks()) {
            strategy = Strategy.BUILD;
        } else if (strategy != Strategy.FALL && strategy != Strategy.CLOSEST && healthyForFall()) {
            strategy = Strategy.FALL;
        } else {
            // Automatone executes its long partial segments already. For short rejected segments,
            // expand a nearby intermediate goal a block at a time until a reachable one is found.
            if (strategy != Strategy.CLOSEST) {
                strategy = Strategy.CLOSEST;
            } else if (closestRange >= Math.ceil(Math.sqrt(bot.blockPosition().distSqr(pos)))) {
                finishNavigation(pos);
            } else {
                closestRange++;
            }
        }
    }

    private boolean hasBuildingBlocks() {
        Settings settings = pathing().settings();
        return bot.getInventory().items.stream().anyMatch(stack -> InventoryBehavior.isAcceptableThrowaway(stack, settings))
            || InventoryBehavior.isAcceptableThrowaway(bot.getOffhandItem(), settings);
    }

    private boolean healthyForFall() {
        return bot.getHealth() > bot.getMaxHealth() * 0.6F;
    }

    private void configureAttempt() {
        Settings settings = pathing().settings();
        attemptSettings = AttemptSettings.capture(settings);
        if (strategy != Strategy.NORMAL && hasBuildingBlocks()) {
            settings.allowPlace.set(true);
        }
        if ((strategy == Strategy.FALL || strategy == Strategy.CLOSEST) && healthyForFall()) {
            settings.maxFallHeightNoWater.set(5);
        }
    }

    private void finishNavigation(BlockPos pos) {
        if (navigationGoal.isInGoal(bot.blockPosition())
            || navigationGoal instanceof GoalBlock && bot.blockPosition().distSqr(pos) <= 1) {
            finish("arrived at " + pos.toShortString());
        } else {
            fail(String.format(Locale.ROOT, "got as close as I could, %.1f blocks from %s",
                Math.sqrt(bot.blockPosition().distSqr(pos)), pos.toShortString()));
        }
    }

    private void tickMineApproach(BlockPos pos) {
        BlockHitResult hit = approachAndAim(pos);
        if (hit == null) {
            return;
        }
        ServerLevel level = bot.serverLevel();
        BlockState state = level.getBlockState(pos);
        breakFace = hit.getDirection();
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
            if (resumeTarget != null) {
                resume();
                return;
            }
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

    private void tickUse(BlockPos pos) {
        BlockHitResult hit = approachAndAim(pos);
        if (hit == null) {
            return;
        }
        ServerLevel level = bot.serverLevel();
        String block = level.getBlockState(pos).getBlock().getName().getString();
        // Same call the server makes for a client's right-click packet: the block (or mod) decides what happens.
        InteractionResult result = bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        if (result.shouldSwing()) {
            bot.swing(InteractionHand.MAIN_HAND);
        }
        finish("used " + block + " at " + pos.toShortString() + ": " + result + ", open menu: " + openMenuDescription());
    }

    /**
     * Walks into reach of the block and turns to face it. Returns the hit on a face the bot can see
     * once it is ready to act; returns null while still approaching, turning or clearing the view,
     * or after failing.
     *
     * <p>Like a player, the bot does not give up on the first blocked look: it tries every face
     * turned toward it, breaks leaves or plants in the way, and steps closer past anything else.
     */
    @Nullable
    private BlockHitResult approachAndAim(BlockPos pos) {
        ServerLevel level = bot.serverLevel();
        if (level.getBlockState(pos).isAir()) {
            if (resumeTarget != null) {
                resume();
            } else {
                finish("nothing at " + pos.toShortString());
            }
            return null;
        }
        Vec3 goal = Vec3.atBottomCenterOf(pos);
        // Small margin so the server's own reach checks always pass.
        if (!bot.canInteractWithBlock(pos, -0.5)) {
            // Next to the block is always within reach; the pathfinder finds the way there.
            if (pathTo(new GoalGetToBlock(pos)) == PathState.FAILED) {
                fail("cannot get within reach of " + pos.toShortString());
            }
            return null;
        }
        stopPathing();

        Vec3 center = Vec3.atCenterOf(pos);
        lookAt(center);
        if (!isLookingAt(center)) {
            return null;
        }
        BlockHitResult visible = findVisibleFace(level, pos);
        if (visible != null) {
            return visible;
        }

        Vec3 eye = bot.getEyePosition();
        BlockPos blocker = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot)).getBlockPos();
        BlockState blockerState = level.getBlockState(blocker);
        if (resumeTarget == null && isClearable(blockerState) && clearedObstacles < MAX_CLEARED_OBSTACLES
            && bot.canInteractWithBlock(blocker, -0.5)) {
            resumeTarget = pos;
            resumeTask = task;
            clearedObstacles++;
            target = blocker;
            task = Task.MINE_APPROACH;
            return null;
        }
        // Something solid in the way: step closer and look again.
        walkTowards(goal);
        if (isStuck(goal)) {
            fail("view of " + pos.toShortString() + " is blocked by " + blockerState.getBlock().getName().getString());
        }
        return null;
    }

    /** A ray from the eyes to the block's center or to a face turned toward the bot that is not blocked. */
    @Nullable
    private BlockHitResult findVisibleFace(ServerLevel level, BlockPos pos) {
        Vec3 eye = bot.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        List<Vec3> points = new ArrayList<>();
        points.add(center);
        for (Direction face : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
            if (normal.dot(eye.subtract(center)) > 0.0) {
                points.add(center.add(normal.scale(0.45)));
            }
        }
        for (Vec3 point : points) {
            BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return hit;
            }
            if (hit.getType() == HitResult.Type.MISS) {
                // The ray reached the point without touching any outline (a thin block): aim at the nearest face.
                Direction face = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
                return new BlockHitResult(point, face, pos, false);
            }
        }
        return null;
    }

    /** Leaves and plants a player would just break through to reach a block behind them. */
    private static boolean isClearable(BlockState state) {
        return !state.isAir() && (state.is(BlockTags.LEAVES) || state.canBeReplaced());
    }

    /** Back to the real target after clearing an obstacle. */
    private void resume() {
        target = resumeTarget;
        task = resumeTask;
        resumeTarget = null;
        resumeTask = null;
        bestDistanceSq = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        breakTicks = 0;
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
        stopPathing();
        task = Task.IDLE;
        target = null;
        resumeTarget = null;
        resumeTask = null;
        reporter.accept(message);
    }

    private void fail(String message) {
        stopPathing();
        task = Task.IDLE;
        target = null;
        resumeTarget = null;
        resumeTask = null;
        reporter.accept("failed: " + message);
    }
}
