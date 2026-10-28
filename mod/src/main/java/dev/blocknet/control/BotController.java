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

    private enum Task { IDLE, GOTO, FOLLOW, MINE_APPROACH, MINE_BREAK, COLLECT, USE, EAT, PLACE, CRAFT, GATHER, CONTAINER, LOOK, EXPLORE, EXCAVATE }

    private SkillTask skill;
    private Integer surfaceY;
    int miningSurfaceY() {
        if (surfaceY == null || bot.getBlockY() > surfaceY) surfaceY = bot.getBlockY();
        return surfaceY;
    }

    public void mineOre(net.minecraft.world.item.Item item, int count, Consumer<String> report) {
        stop();
        skill = new MineOreTask(bot, item, count, 20*60*10, report);
    }

    /** Explicit depth also permits scaled terrain fixtures without changing worldgen knowledge. */
    public void mineOre(net.minecraft.world.item.Item item, int count, int y, Consumer<String> report) {
        stop();
        skill = new MineOreTask(bot, item, count, 20*60*10, y, report);
    }

    public String depthContext() {
        return "y=" + bot.getBlockY() + (surfaceY == null ? "" : ", surface y=" + surfaceY)
            + (skill == null ? "" : skill.depthContext());
    }

    public void obtain(net.minecraft.world.item.Item item, int count, Consumer<String> report) {
        stop();
        skill = new ObtainTask(bot, item, count, report);
    }

    void skillAction(Runnable action) {
        SkillTask owner = skill;
        skill = null;
        try { action.run(); } finally { skill = owner != null && !owner.done ? owner : null; }
    }

    void skillFinished(SkillTask owner, String message, Consumer<String> report) {
        if (skill == owner) {
            skill = null;
            stop();
            bot.moments().report(message);
        }
        report.accept(message);
    }

    private MiningTask excavating;
    private long lastPanorama = Long.MIN_VALUE;
    private Vec3 panoramaPosition;
    private String panoramaDimension;
    private boolean quickLook;
    private int lookFrame;
    private int lookTicks;
    private float lookStartYaw;
    private long lookStartTick;
    private String lookFocus = "";
    private final dev.blocknet.vision.Scene scene;
    private ExploreTask exploring;
    private java.util.Set<String> exploreBefore;
    public dev.blocknet.vision.Scene scene() { return scene; }

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
        this.scene = new dev.blocknet.vision.Scene(bot);
    }

    private net.minecraft.world.entity.LivingEntity danger;
    private int dangerTicks;
    private int lastMobHurt;
    private boolean dangerWarned;
    private boolean explicitAttack;
    private final java.util.Map<java.util.UUID, Double> dangerDistances = new java.util.HashMap<>();

    public boolean defending() { return danger != null; }

    public void mobHurt(net.minecraft.world.damagesource.DamageSource source) {
        if (bot.isAlive() && source.getEntity() instanceof net.minecraft.world.entity.Mob mob) {
            lastMobHurt = bot.tickCount;
            if (danger == null) beginDanger(mob);
        }
    }

    /** Refs for entities are their observed UUIDs; type names also resolve only visible entities. */
    public void attack(String query, Consumer<String> report) {
        var seen = bot.vision().entities().stream().filter(e -> e.uuid().toString().equals(query)
            || e.type().equals(query.replace("minecraft:", ""))).findFirst();
        Entity entity = seen.map(e -> bot.serverLevel().getEntity(e.uuid())).orElse(null);
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living) || !living.isAlive()
                || !bot.hasLineOfSight(living)) { report.accept("none seen for " + query + "; try look"); return; }
        start(Task.IDLE, bot.blockPosition(), report);
        explicitAttack = true;
        beginDanger(living);
    }

    private void beginDanger(net.minecraft.world.entity.LivingEntity mob) {
        danger = mob; dangerTicks = 0; dangerWarned = false;
        stopPathing(); abortBreaking();
        if (task == Task.MINE_BREAK) { task = Task.MINE_APPROACH; breakProgress = 0; breakTicks = 0; }
        bot.releaseUsingItem();
        bot.moments().emit("danger_reflex", "paused task to defend against "
            + BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath());
    }

    private boolean tickDanger() {
        if (!bot.isAlive()) return true;
        var visible = bot.level().getEntities(bot, bot.getBoundingBox().inflate(16),
            e -> e instanceof net.minecraft.world.entity.monster.Enemy && e.isAlive() && bot.hasLineOfSight(e));
        if (danger == null) {
            for (Entity entity : visible) {
                double distance = bot.distanceToSqr(entity);
                Double previous = dangerDistances.put(entity.getUUID(), distance);
                boolean approaching = previous != null && distance < previous - .01
                    || entity.getDeltaMovement().dot(bot.position().subtract(entity.position())) > .01;
                if (distance <= 36 && approaching) { beginDanger((net.minecraft.world.entity.LivingEntity) entity); break; }
            }
            dangerDistances.keySet().removeIf(id -> visible.stream().noneMatch(e -> e.getUUID().equals(id)));
        }
        if (danger == null) return false;
        if (!danger.isAlive() || danger.isRemoved() || bot.distanceToSqr(danger) > 256) {
            danger = null;
            // Check the next visible threat before resuming work.
            var next = visible.stream().filter(e -> bot.distanceToSqr(e) <= 36).findFirst();
            if (next.isPresent()) { beginDanger((net.minecraft.world.entity.LivingEntity) next.get()); return true; }
            if (task == Task.EAT || task == Task.PLACE) {
                for (int slot = 0; slot < 36; slot++) {
                    if (bot.getInventory().getItem(slot).is(actionItem.getItem())) {
                        hold(slot);
                        if (task == Task.EAT) bot.gameMode.useItem(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
                        break;
                    }
                }
            }
            bot.moments().emit("danger_clear", "resuming paused task");
            if (gathering != null && task == Task.GATHER) gathering.resumeSelection();
            if (explicitAttack) { explicitAttack = false; finish("attacked; threat gone"); }
            return false;
        }
        stopPathing();
        bot.zza = 0; bot.xxa = 0; bot.setJumping(false);
        boolean low = bot.getHealth() < bot.getMaxHealth() * .3f;
        boolean retreat = low || visible.stream().filter(e -> bot.distanceToSqr(e) <= 64).count() > 1;
        if (!dangerWarned && (low || ++dangerTicks >= 200 && bot.tickCount - lastMobHurt < 60)) {
            dangerWarned = true;
            bot.moments().emit("danger_failed", "defense needs help; health " + bot.getHealth());
        }
        if (!bot.hasLineOfSight(danger)) return true; // Never pursue hidden live coordinates.
        if (retreat) {
            Vec3 away = bot.position().subtract(danger.position()).multiply(1, 0, 1).normalize();
            Vec3 goal = bot.position().add(away.scale(3));
            BlockPos feet = BlockPos.containing(goal);
            if (canStandOn(bot.serverLevel(), feet.below()) && isRoom(bot.serverLevel(), feet)
                    && isRoom(bot.serverLevel(), feet.above())) walkTowards(goal);
            return true;
        }
        selectWeapon();
        double distance = bot.distanceTo(danger);
        if (distance > 2.7) walkTowards(danger.position());
        else {
            lookAt(danger.getEyePosition());
            if (distance < 1.6) bot.zza = -.5f;
            if (isLookingAt(danger.getEyePosition()) && bot.canInteractWithEntity(danger.getBoundingBox(), 0)
                    && bot.getAttackStrengthScale(.5f) >= .95f) {
                bot.attack(danger);
                bot.swing(InteractionHand.MAIN_HAND);
            }
        }
        return true;
    }

    private void selectWeapon() {
        Inventory inventory = bot.getInventory();
        int best = inventory.selected;
        double score = -1;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            var kinds = ToolFacts.kinds(stack);
            int rank = kinds.contains("sword") ? 3 : kinds.contains("axe") ? 2 : kinds.contains("pickaxe") ? 1 : 0;
            // Use vanilla item attribute modifiers to choose between weapons of the same kind.
            double[] damage = {0};
            stack.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) damage[0] += modifier.amount();
            });
            double candidate = rank * 1000 + damage[0];
            if (candidate > score) { score = candidate; best = slot; }
        }
        if (best >= Inventory.getSelectionSize()) {
            ItemStack held = inventory.getItem(inventory.selected);
            inventory.setItem(inventory.selected, inventory.getItem(best)); inventory.setItem(best, held);
        } else inventory.selected = best;
    }

    void resumeCraftCleanup() { abortBreaking(); stopPathing(); task = Task.CRAFT; }

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
        gather(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(), count, reporter);
    }

    public void gather(String target, int count, Consumer<String> reporter) {
        target = dev.blocknet.bridge.BridgeTools.normalizeRef(target);
        start(Task.GATHER, bot.blockPosition(), reporter);
        scene.goal(target);
        gathering = new GatherTask(bot, this, target, count);
    }

    public void goTo(String ref, Consumer<String> reporter) {
        final String normalizedRef = dev.blocknet.bridge.BridgeTools.normalizeRef(ref);
        var object = bot.vision().resolve(normalizedRef);
        if (object == null) { reporter.accept("none seen for " + ref + "; try look or explore"); return; }
        start(Task.GOTO, object.nearest(), reporter);
        navigationGoal = new GoalGetToBlock(object.nearest());
    }

    public void tunnel(Direction direction, int length, Consumer<String> reporter) {
        start(Task.EXCAVATE, bot.blockPosition(), reporter);
        excavating = new MiningTask(bot, this, direction, length, false);
    }

    public void descend(int y, Direction direction, Consumer<String> reporter) {
        miningSurfaceY();
        start(Task.EXCAVATE, bot.blockPosition(), reporter);
        excavating = new MiningTask(bot, this, direction, y, true);
    }

    void miningLeg(Direction direction, int limit, boolean descending, Consumer<String> reporter) {
        start(Task.EXCAVATE, bot.blockPosition(), reporter);
        excavating = new MiningTask(bot, this, direction, limit, descending, true);
    }

    void miningFinished(String message) { excavating = null; finish(message); }

    public void explore(String direction, int distance, Consumer<String> reporter) {
        start(Task.EXPLORE, bot.blockPosition(), reporter);
        exploring = new ExploreTask(bot, this, direction, Math.min(64, Math.max(1, distance)));
    }

    void explored(java.util.Set<String> before) {
        exploring = null;
        // Keep the same task/reporter through the final panorama, including cancellation.
        exploreBefore = before;
        task = Task.LOOK;
        beginLook("");
    }

    void gatherMine(BlockPos pos) {
        BlockState state = bot.serverLevel().getBlockState(pos);
        if (state.requiresCorrectToolForDrops() && bestCorrectTool(state) < 0) {
            finish(toolHint(state));
            return;
        }
        int tool = bestCorrectTool(state);
        if (skill != null && tool >= 0) {
            ItemStack stack = bot.getInventory().getItem(tool);
            if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 5) {
                finish("stopped: pickaxe almost broken"); return;
            }
        }
        craftMine(pos);
        bestDistanceSq = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
    }

    void gatherFinished(String message) {
        gathering = null;
        finish(message);
    }

    String toolHint(BlockState state) {
        if (!state.requiresCorrectToolForDrops() || bestCorrectTool(state) >= 0) return "";
        var probes = List.of(net.minecraft.world.item.Items.WOODEN_PICKAXE, net.minecraft.world.item.Items.STONE_PICKAXE,
            net.minecraft.world.item.Items.IRON_PICKAXE, net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        for (var tool : probes) {
            ItemStack stack = new ItemStack(tool);
            if (!stack.isCorrectToolForDrops(state)) continue;
            var plan = new dev.blocknet.crafting.CraftingCalculation(bot.serverLevel(), dev.blocknet.crafting.PlannerCatalogs.get(bot.serverLevel()),
                dev.blocknet.crafting.CraftingCalculation.Limits.DEFAULT, p -> CraftStations.EXECUTABLE.contains(p.station()))
                .plan(stack, 1, new ArrayList<>(bot.getInventory().items), CraftStations.remembered(bot).keySet(), List.of(), CraftPlan.Mode.CRAFT);
            String needs = plan.missing().entrySet().stream().map(e -> e.getValue() + " " + e.getKey().replace("minecraft:", ""))
                .collect(java.util.stream.Collectors.joining(", "));
            return "blocked: need a pickaxe for " + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()
                + " (craft " + BuiltInRegistries.ITEM.getKey(tool).getPath() + ": "
                + (needs.isEmpty() ? "try plan for materials and stations" : "needs " + needs) + ")";
        }
        return "blocked: need " + ToolFacts.requirement(state) + "; try inv or plan";
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
    private boolean sneakUse;
    private ItemStack actionItem = ItemStack.EMPTY;
    private int foodBefore, itemBefore, actionTicks;
    private Direction placeFace;

    public void use(BlockPos pos, Consumer<String> reporter) { use(pos, false, reporter); }

    public void use(BlockPos pos, boolean sneak, Consumer<String> reporter) {
        start(Task.USE, pos, reporter);
        sneakUse = sneak;
    }

    /** Select through the same inventory menu swap a player uses. */
    private void hold(int slot) {
        bot.closeContainer();
        if (slot != bot.getInventory().selected) {
            bot.inventoryMenu.clicked(slot < 9 ? slot + 36 : slot, bot.getInventory().selected,
                net.minecraft.world.inventory.ClickType.SWAP, bot);
        }
    }

    public void eat(ItemStack requested, Consumer<String> reporter) {
        start(Task.EAT, bot.blockPosition(), reporter);
        int best = -1;
        double score = -Double.MAX_VALUE;
        int missing = 20 - bot.getFoodData().getFoodLevel();
        boolean foundFood = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty() || !requested.isEmpty() && stack.getItem() != requested.getItem()) continue;
            var food = stack.getFoodProperties(bot);
            if (food == null) continue;
            foundFood = true;
            if (missing == 0 && !food.canAlwaysEat()) continue;
            boolean harmful = food.effects().stream().anyMatch(e -> !e.effect().getEffect().value().isBeneficial());
            double value = Math.min(missing, food.nutrition()) * 10 - Math.max(0, food.nutrition() - missing) * 4
                + food.saturation() - (harmful ? 10000 : 0);
            if (value > score) { best = i; score = value; }
        }
        if (best < 0) { finish(foundFood ? "not hungry" : "failed: no food in inventory"); return; }
        hold(best);
        actionItem = bot.getMainHandItem().copy();
        itemBefore = actionItem.getCount();
        foodBefore = bot.getFoodData().getFoodLevel();
        actionTicks = 0;
        bot.gameMode.useItem(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (!bot.isUsingItem()) fail("cannot eat " + BuiltInRegistries.ITEM.getKey(actionItem.getItem()).getPath());
    }

    private void tickEat() {
        if (++actionTicks > 1200) { fail("eating timed out"); return; }
        if (bot.isUsingItem()) return;
        if (bot.getMainHandItem().getItem() != actionItem.getItem() || bot.getMainHandItem().getCount() < itemBefore) {
            finish("ate " + BuiltInRegistries.ITEM.getKey(actionItem.getItem()).getPath() + " (food " + foodBefore
                + " -> " + bot.getFoodData().getFoodLevel() + ")");
        } else fail("eating interrupted");
    }

    public void place(ItemStack item, BlockPos pos, Direction face, Consumer<String> reporter) {
        start(Task.PLACE, pos, reporter);
        int slot = -1;
        for (int i = 0; i < 36; i++) if (bot.getInventory().getItem(i).is(item.getItem())) { slot = i; break; }
        if (slot < 0) { fail("item missing from inventory"); return; }
        if (!(item.getItem() instanceof net.minecraft.world.item.BlockItem)) { fail("not a block item"); return; }
        hold(slot);
        actionItem = item.copy();
        placeFace = face;
        actionTicks = 0;
    }

    private void tickPlace(BlockPos pos) {
        if (++actionTicks > 400) { fail("no visible free placement face at " + pos.toShortString()); return; }
        if (!bot.level().getBlockState(pos).canBeReplaced()) { fail("position occupied"); return; }
        for (Direction face : Direction.values()) {
            if (placeFace != null && face != placeFace) continue;
            BlockHitResult hit = BlockPlacement.face(bot, pos, face);
            if (hit == null) continue;
            stopPathing();
            if (!craftAim(hit.getLocation())) return;
            BlockPlacement.click(bot, hit);
            if (bot.level().getBlockState(pos).is(((net.minecraft.world.item.BlockItem) actionItem.getItem()).getBlock())) {
                bot.worldMemory().remember(bot, pos);
                finish("placed " + BuiltInRegistries.ITEM.getKey(actionItem.getItem()).getPath() + " at "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ());
                return;
            }
        }
        if (pathTo(new GoalGetToBlock(pos)) == PathState.FAILED) fail("cannot reach placement at " + pos.toShortString());
    }

    public void lookIn(BlockPos pos, Consumer<String> reporter) {
        container(pos, ContainerTask.Action.INSPECT, stack -> true, 0, reporter);
    }

    /** A deliberate player-like sweep; other tools never start this task themselves. */
    public void look(Consumer<String> reporter) { look("", reporter); }

    public void look(String focus, Consumer<String> reporter) {
        start(Task.LOOK, bot.blockPosition(), reporter);
        beginLook(focus, false);
    }

    public void look(String focus, boolean fresh, Consumer<String> reporter) {
        start(Task.LOOK, bot.blockPosition(), reporter);
        beginLook(focus, fresh);
    }

    private void beginLook(String focus) { beginLook(focus, true); }

    private void beginLook(String focus, boolean fresh) {
        lookFocus = focus;
        long now = bot.serverLevel().getGameTime();
        quickLook = !fresh && panoramaPosition != null && now - lastPanorama < 600
            && panoramaDimension.equals(bot.level().dimension().location().toString())
            && bot.position().distanceToSqr(panoramaPosition) < 36;
        lookFrame = bot.vision().completedFrames();
        if (quickLook) bot.vision().startFrame(); else bot.vision().startPanorama();
        lookTicks = 0;
        lookStartYaw = bot.getYRot();
        lookStartTick = bot.getServer().overworld().getGameTime();
    }

    private void tickLook() {
        if (quickLook) {
            bot.vision().tick();
            if (bot.vision().completedFrames() > lookFrame) {
                String report = scene.looked(lookFocus) + " (looked " + ((bot.serverLevel().getGameTime() - lastPanorama) / 20) + "s ago)";
                bot.vision().endPanorama();
                finish(report);
            }
            return;
        }
        float yaw = lookStartYaw + ++lookTicks * 18;
        float pitch = Mth.clamp(36.80378F * (float) Math.sin(lookTicks * Math.PI / 5), -35, 35);
        turnTowards(yaw, pitch);
        bot.vision().tick();
        if (lookTicks == 20) {
            lastPanorama = bot.serverLevel().getGameTime();
            panoramaPosition = bot.position();
            panoramaDimension = bot.level().dimension().location().toString();
            String report = exploreBefore == null ? scene.looked(lookFocus) : scene.discoveries(exploreBefore);
            exploreBefore = null;
            bot.vision().endPanorama();
            finish(report);
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

    boolean exploreWalk(BlockPos pos) {
        Vec3 point = Vec3.atBottomCenterOf(pos);
        // Enter the waypoint cell before replanning; the normal 0.6m approach tolerance can
        // otherwise report arrival while still standing in the previous cell.
        if (bot.position().subtract(point).horizontalDistanceSqr() <= .09) return true;
        walkTowards(point);
        return false;
    }

    boolean gatherRevisit(BlockPos pos) {
        if (++navigationTicks > MAX_PATH_TICKS) { fail("cannot revisit remembered resource"); return false; }
        PathState state = pathTo(new GoalGetToBlock(pos));
        if (state == PathState.FAILED) { fail("cannot reach remembered resource"); return false; }
        return state == PathState.ARRIVED;
    }

    void endGatherRevisit() { stopPathing(); }

    void replanExcavation() { task = Task.EXCAVATE; }

    /** Plug a seen source by clicking an actually visible solid face, like a player. */
    enum FluidSeal { NONE, AIMING, SEALED }
    FluidSeal sealWater(BlockPos pos) {
        var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos);
        if (cell == null || cell.stateId() < 0) return FluidSeal.NONE;
        var fluid = net.minecraft.world.level.block.Block.stateById(cell.stateId()).getFluidState();
        if (!fluid.is(net.minecraft.tags.FluidTags.WATER) || !fluid.isSource()) return FluidSeal.NONE;
        for (int slot = 0; slot < 36; slot++) {
            var stack = bot.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof net.minecraft.world.item.BlockItem block)
                || !block.getBlock().defaultBlockState().isCollisionShapeFullBlock(bot.level(), pos)
                || block.getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                || !InventoryBehavior.isAcceptableThrowaway(stack, pathing().settings())) continue;
            for (Direction side : Direction.values()) {
                BlockPos anchor = pos.relative(side);
                Vec3 face = Vec3.atCenterOf(anchor).add(Vec3.atLowerCornerOf(side.getOpposite().getNormal()).scale(.499));
                var hit = WorldMemory.clip(bot, face, ClipContext.Block.OUTLINE);
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().relative(hit.getDirection()).equals(pos)
                    || !bot.canInteractWithBlock(anchor, -.5)) continue;
                lookAt(face);
                if (!isLookingAt(face)) return FluidSeal.AIMING;
                boolean sneaking = bot.isShiftKeyDown();
                hold(slot);
                try {
                    bot.setShiftKeyDown(true);
                    bot.gameMode.useItemOn(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                    bot.swing(InteractionHand.MAIN_HAND);
                } finally { bot.setShiftKeyDown(sneaking); hold(slot); }
                bot.vision().observe(Vec3.atCenterOf(pos));
                return bot.level().getFluidState(pos).isEmpty() ? FluidSeal.SEALED : FluidSeal.NONE;
            }
        }
        return FluidSeal.NONE;
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
        if (skill != null) {
            SkillTask cancelled = skill;
            skill = null;
            cancelled.complete("failed: cancelled");
        }
        danger = null; explicitAttack = false;
        if (excavating != null) { excavating = null; reporter.accept("failed: cancelled"); }
        exploring = null;
        exploreBefore = null;
        if (task == Task.LOOK) bot.vision().endPanorama();
        bot.releaseUsingItem();
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
        if (task == Task.EAT || task == Task.PLACE) reporter.accept("failed: cancelled");
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
        if (skill != null) return skill.describe();
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
            case EAT -> "eating";
            case PLACE -> "placing block at " + where;
            case CRAFT -> "crafting";
            case GATHER -> "gathering";
            case CONTAINER -> "using container at " + where;
            case LOOK -> "looking around";
            case EXPLORE -> "exploring observed frontier";
            case EXCAVATE -> "excavating";
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
        if (tickDanger()) return;
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
        if (excavating != null && !excavating.checkTimeout()) return;
        if (skill != null) {
            skill.tick();
        }
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
            case EAT -> tickEat();
            case PLACE -> tickPlace(target);
            case CRAFT -> crafting.tick();
            case GATHER -> gathering.tick();
            case CONTAINER -> containerTask.tick();
            case LOOK -> tickLook();
            case EXPLORE -> exploring.tick();
            case EXCAVATE -> excavating.tick();
            case IDLE -> {}
        }
    }

    private void start(Task newTask, BlockPos pos, Consumer<String> newReporter) {
        stop();
        task = newTask;
        target = pos.immutable();
        reporter = message -> { if (skill == null) bot.moments().report(message); newReporter.accept(message); };
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
            bot.worldMemory().forgetOwn(bot, pos);
            bot.vision().afterBreak(pos);
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
        if (excavating != null && !excavating.collectionSafe(pos)) return;
        if (gathering != null && !gathering.collectionSafe(pos)) return;
        // Drops can bounce away from the mined block. Follow the visible item rather than
        // declaring success at the old block center while its drop remains just out of range.
        var drops = bot.level().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3),
            item -> item.isAlive() && bot.hasLineOfSight(item));
        var nearest = drops.stream().min(java.util.Comparator.comparingDouble(bot::distanceToSqr));
        Vec3 goal = nearest.map(ItemEntity::position).orElseGet(() -> Vec3.atBottomCenterOf(pos));
        // A bouncing drop may be over the next, still unchecked cave/drop. Stay on the
        // verified stair; pickup reach is enough for drops from its own three cells.
        if (excavating != null) goal = excavating.collectionPoint();
        else if (gathering != null && gathering.collectionPoint() != null) goal = gathering.collectionPoint();
        if (!hasArrived(goal) && (excavating == null || excavating.collectionWalk(pos))
                && (gathering == null || gathering.collectionWalk(pos))) {
            // Open a walkable entry into an ore pocket when its drops are beyond pickup reach.
            if (gathering != null && gathering.followsOre() && bot.horizontalCollision && resumeTarget == null) {
                var clearance = bot.level().clip(new ClipContext(bot.getEyePosition(), new Vec3(goal.x, bot.getY() + .5, goal.z),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
                if (clearance.getType() == HitResult.Type.BLOCK && clearance.getBlockPos().getY() >= bot.getBlockY()
                    && bot.canInteractWithBlock(clearance.getBlockPos(), -.5)) {
                    BlockState state = bot.level().getBlockState(clearance.getBlockPos());
                    if (!state.getFluidState().isEmpty()) { fail("water or lava blocks ore pickup"); return; }
                    String hint = toolHint(state);
                    if (!hint.isEmpty()) { finish(hint); return; }
                    if (state.getDestroySpeed(bot.level(), clearance.getBlockPos()) >= 0) {
                        resumeTarget = pos; resumeTask = Task.COLLECT;
                        target = clearance.getBlockPos(); task = Task.MINE_APPROACH;
                        return;
                    }
                }
            }
            walkTowards(goal);
        }
        if (--collectTicks <= 0 || (drops.isEmpty() && hasArrived(goal) && collectTicks < COLLECT_TICKS - 10)) {
            if (crafting != null) {
                task = Task.CRAFT;
                crafting.collected();
                return;
            }
            if (excavating != null) { task = Task.EXCAVATE; return; }
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
        boolean sneaking = bot.isShiftKeyDown();
        bot.setShiftKeyDown(sneakUse);
        InteractionResult result;
        try { result = bot.gameMode.useItemOn(bot, level, bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit); }
        finally { bot.setShiftKeyDown(sneaking); }
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
        excavating = null;
        if (miningToolAlmostBroken) message += " (tool almost broken)";
        miningToolAlmostBroken = false;
        if (task == Task.FOLLOW) {
            pathing().settings().allowSprint.set(followAllowSprint);
        }
        if (skill == null) bot.moments().report(message);
        // Clear FOLLOW before stop so it cannot emit a second terminal report.
        task = Task.IDLE;
        SkillTask owner = skill;
        skill = null;
        stop();
        skill = owner;
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
