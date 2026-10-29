package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import dev.blocknet.vision.Semantics;
import dev.blocknet.vision.ObjectGraph;
import dev.blocknet.vision.VoxelRay;
import java.util.Set;
import java.util.HashSet;
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
    private final String target;
    private final ObjectGraph.ObjectView object;
    private final boolean wholeObject;
    private final Set<Long> columns = new HashSet<>();
    private final boolean logs;
    private boolean checked;
    private java.util.Iterator<Vec3> survey;
    private Vec3 surveyOrigin;
    private final Set<BlockPos> vein = new HashSet<>();
    private String oreKind;
    private final int count;
    private final int initial;
    private final Direction forward;
    private BlockPos step;
    private BlockPos stairFeet;
    private boolean tunneling;
    private int ticks;
    private int lastCount;
    private int emptyMines;
    private boolean revisited;
    private boolean revisiting;
    private boolean harvesting;
    boolean caveOnly;
    private final java.util.Map<BlockState, Boolean> dropMemo = new java.util.HashMap<>();
    private long dropMemoTick = Long.MIN_VALUE;

    GatherTask(BotPlayer bot, BotController controller, String target, int count) {
        this.bot = bot;
        this.controller = controller;
        this.target = target.replace("minecraft:", "");
        object = bot.vision().resolve(target);
        // Count 0 with an observed object: take that object and stop (one tree, one cliff face).
        wholeObject = count == 0 && object != null;
        this.count = wholeObject ? Integer.MAX_VALUE : Math.max(1, count);
        logs = this.target.equals("logs") || this.target.equals("wood") || object != null && object.category().equals("tree");
        item = logs ? Items.OAK_LOG : object != null && object.category().equals("cliff") ? Items.COBBLESTONE : object != null && object.category().equals("ore") ? (object.kind().equals("iron") ? Items.RAW_IRON : BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(object.kind()))) : switch (this.target) {
            case "stone", "cobblestone" -> Items.COBBLESTONE;
            case "coal" -> Items.COAL;
            case "iron" -> Items.RAW_IRON;
            default -> BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(target) == null ? ResourceLocation.withDefaultNamespace("air") : ResourceLocation.parse(target));
        };
        if (object != null) bot.worldMemory().observed().forEach(bot.level().dimension().location().toString(), (pos, cell) -> {
            if (object.bounds().contains(Vec3.atCenterOf(pos)) && cell.semantic().category().equals("log") && cell.semantic().kind().equals(object.kind()))
                columns.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
        });
        initial = held();
        lastCount = initial;
        forward = bot.getDirection();
    }

    /** Registry/loot knowledge, not a search for world targets. Only suggest a proven survival source. */
    static String sourceHint(BotPlayer bot, ItemStack target) {
        BlockPos pos = bot.blockPosition();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            float hardness = state.getDestroySpeed(bot.level(), pos);
            if (!Float.isFinite(hardness) || hardness < 0 || state.isAir()) continue;
            boolean drops = block.asItem() == target.getItem();
            if (!drops) {
                ItemStack tool = ItemStack.EMPTY;
                for (Item probe : new Item[] {Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE}) {
                    ItemStack candidate = new ItemStack(probe);
                    if (candidate.isCorrectToolForDrops(state)) { tool = candidate; break; }
                }
                drops = Block.getDrops(state, bot.serverLevel(), pos, null, bot, tool).stream().anyMatch(stack -> stack.is(target.getItem()));
            }
            if (drops) return "; it is gathered: gather " + (target.is(ItemTags.LOGS) ? "logs" : BuiltInRegistries.ITEM.getKey(target.getItem()));
        }
        return "";
    }

    private String name() { return logs ? "logs" : BuiltInRegistries.ITEM.getKey(item).getPath(); }
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
            if (logs ? stack.is(ItemTags.LOGS) : stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    boolean followsOre() { return oreKind != null; }

    /** Defense may move us before selection has produced a mining target. Keep the goal and
     * vein evidence, but re-observe the new position instead of trusting a partial old glance. */
    void resumeSelection() {
        checked = false;
        survey = null;
        step = null;
        tunneling = false;
    }

    private boolean finishSurvey() {
        // A knockback/fall is not a stable place to decide which ground resource is available.
        if (!bot.onGround()) return false;
        Vec3 eye = bot.getEyePosition();
        if (survey == null || eye.distanceToSqr(surveyOrigin) > 1) {
            surveyOrigin = eye;
            var points = new java.util.ArrayList<Vec3>();
            for (BlockPos pos : BlockPos.withinManhattan(BlockPos.containing(eye), 4, 4, 4)) {
                Vec3 point = Vec3.atCenterOf(pos);
                if (eye.distanceToSqr(point) <= 16) points.add(point);
            }
            survey = points.iterator();
        }
        while (survey.hasNext()) {
            if (!bot.vision().canObserve()) return false;
            bot.vision().observe(survey.next());
        }
        return true;
    }

    /** Headroom drops wait until the opening is clear. Never walk into an unchecked hole. */
    boolean collectionWalk(BlockPos broken) { return !caveOnly && (step == null || broken.getY() <= step.getY()); }
    Vec3 collectionPoint() { return step == null ? null : Vec3.atBottomCenterOf(step); }

    boolean collectionSafe(BlockPos broken) {
        return step == null || !collectionWalk(broken) || supportSafe();
    }

    private boolean supportSafe() {
        if (!bot.vision().canObserve()) return false;
        BlockPos support = step.below();
        // Aim at the top surface: a center ray can hit the current stair's lip instead.
        var hit = bot.vision().observe(Vec3.atCenterOf(support).add(0, .499, 0));
        if (hit.end() == VoxelRay.End.UNKNOWN) return false;
        if (hit.end() == VoxelRay.End.HIT && !support.equals(hit.pos())) {
            controller.exploreWalk(stairFeet);
            return false;
        }
        if (hit.end() != VoxelRay.End.HIT || hit.state() == null || !hit.state().getFluidState().isEmpty()
                || !hit.state().isFaceSturdy(bot.level(), support, Direction.UP)) {
            noSight();
            return false;
        }
        return true;
    }

    private void nextStair(BlockPos feet) {
        controller.miningSurfaceY();
        tunneling = true;
        stairFeet = feet.immutable();
        step = feet.relative(forward).below();
        if (step.getY() < bot.level().getMinBuildHeight()) noSight();
    }

    void collected() {
        int now = held();
        if (now > lastCount) emptyMines = 0;
        else if (harvesting) emptyMines++;
        lastCount = now;
    }

    /** Test the recorded state, including loot with a suitable probe, before asking to mine. */
    private boolean matches(BlockPos pos) {
        var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos);
        return cell != null && cell.stateId() >= 0 && matches(pos, Block.stateById(cell.stateId()));
    }

    private boolean matches(BlockPos pos, BlockState state) {
        if (object != null) {
            if (logs && !object.bounds().contains(Vec3.atCenterOf(pos))) return false;
            if (logs && !columns.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()))) return false;
            if (!logs && ((!object.category().equals("cliff") && !Semantics.state(state).kind().equals(object.kind()))
                || vein.isEmpty() && !object.bounds().contains(Vec3.atCenterOf(pos)))) return false;
        }
        if (logs) return state.is(BlockTags.LOGS);
        if (target.equals("coal") || target.equals("iron")) {
            var kind = Semantics.state(state);
            return kind.category().equals("ore") && kind.kind().equals(target);
        }
        if (target.equals("dirt")) return state.is(BlockTags.DIRT);
        if (target.equals("sand")) return state.is(BlockTags.SAND);
        if (state.getBlock().asItem() == item) return true;
        long tick = bot.serverLevel().getGameTime();
        if (tick != dropMemoTick) {
            dropMemo.clear();
            dropMemoTick = tick;
        }
        // A loot roll is the expensive part, and thousands of remembered stone cells share one state:
        // roll once per state per tick instead of once per cell (which lagged the whole server).
        return dropMemo.computeIfAbsent(state, ignored -> {
            ItemStack tool = bot.getMainHandItem();
            for (Item probe : new Item[] {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE}) {
                ItemStack candidate = new ItemStack(probe);
                if (candidate.isCorrectToolForDrops(state)) { tool = candidate; break; }
            }
            return Block.getDrops(state, bot.serverLevel(), pos, null, bot, tool).stream().anyMatch(stack -> stack.is(item));
        });
    }

    private boolean mine(BlockPos pos, String why) {
        var seen = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos);
        controller.trace("gather", "mining " + (seen == null || seen.stateId() < 0 ? "block"
            : BuiltInRegistries.BLOCK.getKey(Block.stateById(seen.stateId()).getBlock()).getPath()) + " at " + pos.toShortString()
            + String.format(java.util.Locale.ROOT, " (%.1fm): ", Math.sqrt(bot.position().distanceToSqr(Vec3.atCenterOf(pos)))) + why
            + "; have " + (held() - initial) + (wholeObject ? " (taking all of " + target + ")" : "/" + count) + " " + name());
        harvesting = matches(pos);
        var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos);
        if (cell != null && cell.stateId() >= 0) {
            String hint = controller.toolHint(Block.stateById(cell.stateId()));
            if (!hint.isEmpty()) { controller.gatherFinished(hint); return false; }
        }
        var semantic = cell == null ? null : cell.semantic();
        if (semantic != null && semantic.category().equals("ore")) {
            oreKind = semantic.kind();
            vein.add(pos.immutable());
        }
        controller.gatherMine(pos);
        return true;
    }

    private boolean visible(BlockPos pos) {
        Vec3 eye = bot.getEyePosition(), center = Vec3.atCenterOf(pos);
        var points = new java.util.ArrayList<Vec3>();
        points.add(center);
        for (Direction face : Direction.values()) {
            Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
            if (normal.dot(eye.subtract(center)) > 0) points.add(center.add(normal.scale(.45)));
        }
        for (Vec3 point : points) {
            var hit = bot.level().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return true;
        }
        return false;
    }

    void tick() {
        if (object == null && target.matches("[a-z][0-9]+")) { noSight(); return; }
        if (held() - initial >= count) {
            controller.gatherFinished("gathered " + (held() - initial) + " " + name());
            return;
        }
        if (object != null && !revisited) {
            if (!visible(object.nearest()) && !revisiting) {
                revisiting = true;
                controller.trace("gather", target + " is remembered, not visible now: walking back to " + object.nearest().toShortString() + " to look again");
            }
            if (!visible(object.nearest()) && !controller.gatherRevisit(object.nearest())) return;
            controller.endGatherRevisit();
            // The ref is a destination, not permission to mine a stale block state.
            if (!finishSurvey()) return;
            revisited = true;
        }
        if (!checked) {
            // Close-range rays are observations too; preserve direct gather on ground underfoot.
            var ground = bot.vision().observe(Vec3.atCenterOf(bot.blockPosition().below()).add(0, .499, 0));
            if (ground.end() == VoxelRay.End.UNKNOWN) return;
            if (!finishSurvey()) return;
            checked = true;
            var first = bot.worldMemory().nearby(bot, 64).stream().map(e -> e.pos()).filter(this::matches).filter(this::visible).findFirst();
            if (first.isPresent()) {
                var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), first.get());
                String hint = controller.toolHint(Block.stateById(cell.stateId()));
                if (!hint.isEmpty()) { controller.gatherFinished(hint); return; }
            }
        }
        if (emptyMines >= 4) {
            controller.gatherFinished("failed: cannot collect " + name() + "; check tool and inventory space");
            return;
        }
        if (step != null) {
            // Clear headroom, feet, then the next stair, revealing each block before choosing it.
            for (BlockPos pos : new BlockPos[] { step.above(2), step.above(), step }) {
                // A time-sliced ray can be deferred under load; that is not an absent resource.
                var observed = bot.vision().observe(Vec3.atCenterOf(pos));
                if (observed.end() == VoxelRay.End.UNKNOWN) return;
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
                    mine(seen, "digging the next stair (headroom, feet, then floor)");
                    return;
                }
                if (!bot.level().getFluidState(pos).isEmpty()) { noSight(); return; }
            }
            if (!supportSafe()) return;
            // The interaction tolerance is too loose for a waypoint: it can accept the old
            // column or the falling body before the feet have landed on the new stair.
            if (!controller.exploreWalk(step) || !bot.onGround() || Math.abs(bot.getY() - step.getY()) > .25) return;
            step = null;
        }
        BlockPos feet = bot.blockPosition();
        // Finish observing local ground before considering a distant remembered surface.
        var groundHit = bot.vision().observe(Vec3.atCenterOf(feet.below()).add(0, .499, 0));
        if (groundHit.end() == VoxelRay.End.UNKNOWN) return;
        bot.worldMemory().glance(bot, 4);
        // Newly seen connected ore wins over other remembered deposits, even below our feet.
        if (oreKind != null) {
            var connected = bot.worldMemory().nearby(bot, 64).stream().map(e -> e.pos())
                .filter(this::matches).filter(this::visible)
                .filter(pos -> !caveOnly || pos.getY() >= feet.getY() && bot.canInteractWithBlock(pos, -.5))
                .filter(pos -> bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos).semantic().kind().equals(oreKind))
                .filter(pos -> vein.stream().anyMatch(old -> Math.abs(pos.getX()-old.getX()) <= 1
                    && Math.abs(pos.getY()-old.getY()) <= 1 && Math.abs(pos.getZ()-old.getZ()) <= 1))
                .min(Comparator.comparingDouble(pos -> bot.position().distanceToSqr(Vec3.atCenterOf(pos))));
            if (connected.isPresent()) { mine(connected.get(), "next block of the " + oreKind + " vein I am following"); return; }
        }
        if (caveOnly) {
            var wall = bot.worldMemory().nearby(bot, 6).stream().map(e -> e.pos())
                .filter(p -> p.getY() >= feet.getY() && bot.canInteractWithBlock(p, -.5))
                .filter(this::matches).filter(this::visible).findFirst();
            if (wall.isPresent()) mine(wall.get(), "visible wall ore within reach of the cave route"); else noSight();
            return;
        }
        if (tunneling) { nextStair(feet); return; }
        // Prefer exposed resources above the floor before opening a staircase.
        var candidate = bot.worldMemory().nearby(bot, 64).stream()
            .map(entry -> entry.pos()).filter(pos -> pos.getY() >= feet.getY())
            .filter(this::matches).filter(this::visible)
            .min(Comparator.comparingDouble(pos -> bot.position().distanceToSqr(Vec3.atCenterOf(pos))));
        if (candidate.isPresent() && bot.canInteractWithBlock(candidate.get(), -.5)) {
            mine(candidate.get(), "nearest visible match at or above my feet, already in reach");
            return;
        }
        BlockPos ground = feet.below();
        var groundCell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), ground);
        boolean cover = item == Items.COBBLESTONE && groundCell != null && groundCell.stateId() >= 0
            && (Block.stateById(groundCell.stateId()).is(BlockTags.DIRT) || Block.stateById(groundCell.stateId()).is(BlockTags.SAND)
                || Block.stateById(groundCell.stateId()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock);
        if (object == null && groundHit.end() == VoxelRay.End.HIT && ground.equals(groundHit.pos()) && (matches(ground) || cover)) {
            // Physics runs after the controller: wait for a real landing before fixing the
            // next stair's origin, otherwise floor(blockY) can still be a falling cell.
            if (!bot.onGround()) return;
            controller.trace("gather", "nothing matching in reach; the ground under me is "
                + BuiltInRegistries.BLOCK.getKey(Block.stateById(groundCell.stateId()).getBlock()).getPath()
                + (cover ? " (cover over stone)" : "") + ", so I dig a staircase down");
            nextStair(feet);
            return;
        }
        if (candidate.isPresent()) { mine(candidate.get(), "nearest visible match at or above my feet; walking there"); return; }
        // A visible surface below the feet can still supply the resource without a tunnel.
        var surface = bot.worldMemory().nearby(bot, 64).stream().map(entry -> entry.pos())
            .filter(this::matches).filter(this::visible).findFirst();
        if (surface.isPresent()) mine(surface.get(), "a visible match below my feet");
        else noSight();
    }

    private void noSight() {
        if (wholeObject && held() > initial) {
            controller.trace("gather", "nothing left of " + target + " that I can see; that was the whole " + object.kind() + " " + object.category());
            controller.gatherFinished("gathered " + (held() - initial) + " " + name() + ": the whole " + target + " " + object.kind() + " " + object.category());
            return;
        }
        controller.trace("gather", "no visible " + name() + " I can safely reach from " + bot.blockPosition().toShortString()
            + " (only blocks a ray from my eyes reaches count; " + bot.worldMemory().observed().cellCount() + " cells remembered)");
        controller.gatherFinished("none seen for " + target + (held() > initial ? "; gathered " + (held() - initial) + "/" + count : "") + "; " + controller.scene().unexplored() + "; try explore");
    }
}
