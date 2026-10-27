package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.CraftPlan;
import dev.blocknet.memory.ContainerMemory;
import dev.blocknet.memory.WorldMemory;
import dev.blocknet.state.ItemDescriber;
import dev.blocknet.state.ToolFacts;
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
import java.util.function.Predicate;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
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

    private enum Task { IDLE, GOTO, FOLLOW, MINE_APPROACH, MINE_BREAK, COLLECT, USE, CRAFT, GATHER, CONTAINER, LOOK }

    private int lookTicks;
    private float lookStartYaw;
    private long lookStartTick;

    @Nullable
    private CraftExecutor crafting;
    private GatherTask gathering;
    private ContainerTask containerTask;
    private BlockPos usedContainerPos;
    private AbstractContainerMenu usedContainerMenu;

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
    @Nullable
    private Entity followTarget;
    private String followName;
    private Vec3 lastSeenFollowPosition;
    private boolean lastSeenFollowOnGround;
    @Nullable
    private Vec3 followLegPosition;
    private int followRetryTicks;
    private boolean followAllowSprint;
    private enum Strategy { NORMAL, FALL, CLOSEST }
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
    private boolean miningToolAlmostBroken;

    public BotController(BotPlayer bot) {
        this.bot = bot;
    }

    /** The pathfinder owns movement only while this controller has a navigation leg. */
    public boolean isNavigating() {
        return pathGoal != null;
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
        if (!setGroundGoal(source, sourceOnGround)) {
            fail("no ground within 16 blocks below " + feet.toShortString());
        }
    }

    /** Shares come's feet/ground projection and horizontal goal with each follow leg. */
    private boolean setGroundGoal(Vec3 source, boolean sourceOnGround) {
        BlockPos feet = BlockPos.containing(source.x, source.y + 0.1251, source.z);
        if (sourceOnGround) {
            setComeGoal(feet);
            return true;
        }
        ServerLevel level = bot.serverLevel();
        for (int down = 1; down <= 16; down++) {
            BlockPos ground = feet.below(down);
            if (ground.getY() < level.getMinBuildHeight()) {
                break;
            }
            if (canStandOn(level, ground) && isRoom(level, ground.above()) && isRoom(level, ground.above(2))) {
                setComeGoal(ground.above());
                return true;
            }
        }
        return false;
    }

    /** Follows until cancelled or the target leaves; individual route failures are silent. */
    public void follow(Entity leader, Consumer<String> reporter) {
        start(Task.FOLLOW, leader.blockPosition(), reporter);
        followTarget = leader;
        followName = leader.getName().getString();
        // The command supplies the initial location. Later locations require a clear sight line.
        lastSeenFollowPosition = leader.position();
        lastSeenFollowOnGround = leader.onGround();
        followAllowSprint = pathing().settings().allowSprint.get();
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
        stop();
        BlockState state = bot.serverLevel().getBlockState(pos);
        if (state.requiresCorrectToolForDrops() && bestCorrectTool(state) < 0) {
            reporter.accept("blocked: need a tool for " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " (" + ToolFacts.requirement(state) + ")");
            return;
        }
        start(Task.MINE_APPROACH, pos, reporter);
        int tool = bestCorrectTool(state);
        miningToolAlmostBroken = tool >= 0 && ItemDescriber.wear(bot.getInventory().getItem(tool)).equals("almost broken");
    }

    public void gather(ItemStack item, int count, Consumer<String> reporter) {
        start(Task.GATHER, bot.blockPosition(), reporter);
        gathering = new GatherTask(bot, this, item.getItem(), count);
    }

    void gatherMine(BlockPos pos) {
        BlockState state = bot.serverLevel().getBlockState(pos);
        if (state.requiresCorrectToolForDrops() && bestCorrectTool(state) < 0) {
            fail("need a tool for " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            return;
        }
        craftMine(pos);
        bestDistanceSq = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
    }

    void gatherFinished(String message) {
        gathering = null;
        finish(message);
    }

    private int bestCorrectTool(BlockState state) {
        int selected = bot.getInventory().selected;
        ItemStack held = bot.getInventory().getItem(selected);
        int best = !held.isEmpty() && held.isCorrectToolForDrops(state) ? selected : -1;
        for (int slot = 0; slot < bot.getInventory().getContainerSize(); slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)
                && (best < 0 || stack.getDestroySpeed(state) > bot.getInventory().getItem(best).getDestroySpeed(state))) best = slot;
        }
        return best;
    }

    /** Walks into reach of the block and right-clicks it with the held item. */
    public void use(BlockPos pos, Consumer<String> reporter) {
        start(Task.USE, pos, reporter);
    }

    public void lookIn(BlockPos pos, Consumer<String> reporter) {
        container(pos, ContainerTask.Action.INSPECT, stack -> true, 0, reporter);
    }

    /** A deliberate player-like sweep; other tools never start this task themselves. */
    public void look(Consumer<String> reporter) {
        start(Task.LOOK, bot.blockPosition(), reporter);
        lookTicks = 0;
        lookStartYaw = bot.getYRot();
        lookStartTick = bot.getServer().overworld().getGameTime();
    }

    private void tickLook() {
        float yaw = lookStartYaw + ++lookTicks * 18;
        float pitch = 20 + 15 * (float) Math.sin(lookTicks * Math.PI / 10);
        turnTowards(yaw, pitch);
        Vec3 eye = bot.getEyePosition();
        for (int horizontal = -12; horizontal <= 12; horizontal += 2) {
            for (int vertical = -45; vertical <= 55; vertical += 3) {
                Vec3 direction = Vec3.directionFromRotation(Mth.clamp(bot.getXRot() + vertical, -89, 89),
                    bot.getYRot() + horizontal);
                WorldMemory.clip(bot, eye.add(direction.scale(16)), ClipContext.Block.VISUAL);
            }
        }
        if (lookTicks == 20) {
            finish(bot.worldMemory().looked(bot, lookStartTick));
        }
    }

    public void deposit(BlockPos pos, String filter, int count, Consumer<String> reporter) {
        transfer(pos, ContainerTask.Action.DEPOSIT, filter, count, reporter);
    }

    public void withdraw(BlockPos pos, String filter, int count, Consumer<String> reporter) {
        transfer(pos, ContainerTask.Action.WITHDRAW, filter, count, reporter);
    }

    private void transfer(BlockPos pos, ContainerTask.Action action, String filter, int count, Consumer<String> reporter) {
        try {
            container(pos, action, ContainerFilter.parse(bot, filter), count, reporter);
        } catch (IllegalArgumentException exception) {
            reporter.accept("failed: " + exception.getMessage());
        }
    }

    private void container(BlockPos pos, ContainerTask.Action action, Predicate<ItemStack> filter,
        int count, Consumer<String> reporter) {
        start(Task.CONTAINER, pos, reporter);
        containerTask = new ContainerTask(bot, this, pos, action, filter, count, message -> {
            containerTask = null;
            finish(message);
        });
    }

    public void craft(ItemStack item, int count, Consumer<String> reporter) {
        craft(item, count, CraftPlan.Mode.CRAFT, reporter);
    }

    public void get(ItemStack item, int count, Consumer<String> reporter) {
        craft(item, count, CraftPlan.Mode.OBTAIN, reporter);
    }

    private void craft(ItemStack item, int count, CraftPlan.Mode mode, Consumer<String> reporter) {
        start(Task.CRAFT, bot.blockPosition(), reporter);
        crafting = new CraftExecutor(bot, this, item, count, mode);
        crafting.start();
    }

    BlockHitResult craftApproach(BlockPos pos) {
        target = pos;
        return approachAndAim(pos);
    }

    boolean craftAim(Vec3 point) {
        lookAt(point);
        boolean facing = isLookingAt(point);
        if (facing) {
            WorldMemory.clip(bot, point, ClipContext.Block.VISUAL);
        }
        return facing;
    }

    boolean craftWalk(Vec3 point) {
        if (hasArrived(point)) {
            return true;
        }
        walkTowards(point);
        return false;
    }

    void craftMine(BlockPos pos) {
        target = pos;
        task = Task.MINE_APPROACH;
    }

    void craftFinished(String message) {
        abortBreaking();
        crafting = null;
        finish(message);
    }

    public void stop() {
        stopPathing();
        abortBreaking();
        if (gathering != null) {
            gathering = null;
            reporter.accept("failed: cancelled");
        }
        if (containerTask != null) {
            ContainerTask cancelled = containerTask;
            containerTask = null;
            cancelled.fail("cancelled");
        }
        if (crafting != null) {
            CraftExecutor cancelled = crafting;
            crafting = null;
            cancelled.cancel();
        }
        boolean wasFollowing = task == Task.FOLLOW;
        task = Task.IDLE;
        target = null;
        resumeTarget = null;
        resumeTask = null;
        navigationGoal = null;
        followTarget = null;
        followLegPosition = null;
        followRetryTicks = 0;
        miningToolAlmostBroken = false;
        if (wasFollowing) {
            pathing().settings().allowSprint.set(followAllowSprint);
            reporter.accept("stopped");
        }
    }

    public String describe() {
        if (gathering != null) return gathering.describe();
        if (crafting != null) {
            return crafting.describe();
        }
        if (task == Task.IDLE || target == null) {
            return "idle, open menu: " + openMenuDescription();
        }
        String where = target.getX() + " " + target.getY() + " " + target.getZ();
        String clearing = resumeTarget == null ? "" : "clearing the way to " + resumeTarget.toShortString() + ": ";
        return clearing + switch (task) {
            case GOTO -> "walking to " + where;
            case FOLLOW -> "following " + followName;
            case MINE_APPROACH -> "approaching block at " + where;
            case MINE_BREAK -> String.format(Locale.ROOT, "mining block at %s (%.0f%%)", where, breakProgress * 100.0F);
            case COLLECT -> "collecting drops at " + where;
            case USE -> "going to use block at " + where;
            case CRAFT -> "crafting";
            case GATHER -> "gathering";
            case CONTAINER -> "using container at " + where;
            case LOOK -> "looking around";
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
        if (usedContainerMenu != null) {
            if (bot.containerMenu == usedContainerMenu && usedContainerMenu.stillValid(bot)) {
                bot.containerMemory().observe(bot, usedContainerPos);
            } else {
                usedContainerMenu = null;
                usedContainerPos = null;
            }
        }
        // While the pathfinder drives, it owns the legs (it sets the inputs after this).
        if (pathGoal == null) {
            bot.zza = 0.0F;
            bot.xxa = 0.0F;
            bot.setJumping(false);
        }
        if (containerTask != null && !containerTask.checkTimeout()) {
            return;
        }
        if (crafting != null && !crafting.checkTimeout()) {
            return;
        }
        if (gathering != null && !gathering.checkTimeout()) return;
        if (target == null) {
            return;
        }
        switch (task) {
            case GOTO -> tickGoTo(target);
            case FOLLOW -> tickFollow();
            case MINE_APPROACH -> tickMineApproach(target);
            case MINE_BREAK -> tickMineBreak(target);
            case COLLECT -> tickCollect(target);
            case USE -> tickUse(target);
            case CRAFT -> crafting.tick();
            case GATHER -> gathering.tick();
            case CONTAINER -> containerTask.tick();
            case LOOK -> tickLook();
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
        miningToolAlmostBroken = false;
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
            if (task == Task.GOTO || task == Task.FOLLOW) {
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

    private void tickFollow() {
        Entity leader = followTarget;
        // Check dimension before removal: changing dimension can replace/remove the old entity.
        if (leader.level() != bot.level()) {
            fail(followName + " is in another dimension");
            return;
        }
        if (!leader.isAlive() || leader.isRemoved()
            || bot.serverLevel().getEntity(leader.getUUID()) != leader) {
            fail(followName + " is gone");
            return;
        }
        boolean visible = WorldMemory.clip(bot, leader.getEyePosition(), ClipContext.Block.VISUAL).getType() == HitResult.Type.MISS;
        if (visible) {
            lastSeenFollowPosition = leader.position();
            lastSeenFollowOnGround = leader.onGround();
        }
        double distanceSq = bot.position().distanceToSqr(lastSeenFollowPosition);
        pathing().settings().allowSprint.set(followAllowSprint && distanceSq > 64);
        if (distanceSq <= 9 && bot.onGround()) {
            stopPathing();
            bot.zza = 0;
            bot.xxa = 0;
            bot.setJumping(false);
            bot.setSprinting(false);
            followLegPosition = null;
            if (visible) {
                lookAt(leader.getEyePosition());
            }
            return;
        }
        boolean moved = followLegPosition != null && lastSeenFollowPosition.distanceToSqr(followLegPosition) > 9;
        if (moved || followLegPosition == null && followRetryTicks-- <= 0) {
            stopPathing();
            strategy = Strategy.NORMAL;
            closestRange = 1;
            navigationTicks = 0;
            followLegPosition = lastSeenFollowPosition;
            if (!setGroundGoal(lastSeenFollowPosition, lastSeenFollowOnGround)) {
                finishFollowLeg();
                return;
            }
        }
        if (followLegPosition != null) {
            tickGoTo(target);
        }
    }

    private void finishFollowLeg() {
        stopPathing();
        followLegPosition = null;
        followRetryTicks = 20;
    }

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
        // Compare walking, mining and building by time in the very first search.
        settings.allowPlace.set(hasBuildingBlocks());
        if ((strategy == Strategy.FALL || strategy == Strategy.CLOSEST) && healthyForFall()) {
            settings.maxFallHeightNoWater.set(5);
        }
    }

    private void finishNavigation(BlockPos pos) {
        if (task == Task.FOLLOW) {
            finishFollowLeg();
            return;
        }
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
            bot.containerMemory().forget(bot, pos);
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
        // Drops can bounce away from the mined block. Follow the visible item rather than
        // declaring success at the old block center while its drop remains just out of range.
        var drops = bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
            item -> item.isAlive() && bot.hasLineOfSight(item));
        var nearest = drops.stream().min(java.util.Comparator.comparingDouble(bot::distanceToSqr));
        Vec3 goal = nearest.map(ItemEntity::position).orElseGet(() -> Vec3.atBottomCenterOf(pos));
        if (!hasArrived(goal)) {
            walkTowards(goal);
        }
        if (--collectTicks <= 0 || (drops.isEmpty() && hasArrived(goal) && collectTicks < COLLECT_TICKS - 10)) {
            if (crafting != null) {
                task = Task.CRAFT;
                crafting.collected();
                return;
            }
            if (gathering != null) {
                task = Task.GATHER;
                gathering.collected();
                return;
            }
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
        if (!(bot.containerMenu instanceof CraftingMenu)
            && !ContainerMemory.containerSlots(bot).isEmpty()) {
            usedContainerPos = pos.immutable();
            usedContainerMenu = bot.containerMenu;
            bot.containerMemory().observe(bot, pos);
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
        boolean usingContainer = containerTask != null || crafting != null && crafting.isWithdrawing();
        if (usingContainer && !bot.canInteractWithBlock(pos, -0.5)) {
            if (pathTo(new GoalGetToBlock(pos)) == PathState.FAILED) {
                fail("cannot reach container at " + pos.toShortString());
            }
            return null;
        }
        if (level.getBlockState(pos).isAir()) {
            // Only forget when this is actually visible, never on a remote position lookup.
            boolean visible = bot.canInteractWithBlock(pos, -0.5) && findVisibleFace(level, pos) != null;
            if (visible) {
                bot.containerMemory().forget(bot, pos);
            }
            if (usingContainer) {
                fail(visible ? "not a container" : "cannot see container at " + pos.toShortString());
                return null;
            }
            if (resumeTarget != null) {
                resume();
            } else if (crafting != null) {
                crafting.stationGone();
            } else if (gathering != null) {
                task = Task.GATHER;
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
        BlockPos blocker = WorldMemory.clip(bot, center, ClipContext.Block.OUTLINE).getBlockPos();
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
            BlockHitResult hit = WorldMemory.clip(bot, point, ClipContext.Block.OUTLINE);
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
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            float score = toolScore(inventory.getItem(slot), state);
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        if (best >= Inventory.getSelectionSize()) {
            ItemStack held = inventory.getItem(inventory.selected);
            inventory.setItem(inventory.selected, inventory.getItem(best));
            inventory.setItem(best, held);
        } else {
            inventory.selected = best;
        }
        if (resumeTarget == null && (task == Task.MINE_APPROACH || task == Task.MINE_BREAK)) {
            miningToolAlmostBroken |= ItemDescriber.wear(bot.getMainHandItem()).equals("almost broken");
        }
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
        gathering = null;
        if (miningToolAlmostBroken) message += " (tool almost broken)";
        miningToolAlmostBroken = false;
        if (task == Task.FOLLOW) {
            pathing().settings().allowSprint.set(followAllowSprint);
        }
        // Clear FOLLOW before stop so it cannot emit a second terminal report.
        task = Task.IDLE;
        stop();
        reporter.accept(message);
    }

    private void fail(String message) {
        if (containerTask != null) {
            containerTask.fail(message);
            return;
        }
        if (crafting != null) {
            crafting.fail(message);
            return;
        }
        finish("failed: " + message);
    }
}
