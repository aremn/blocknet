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
    private final Set<Long> columns = new HashSet<>();
    private final boolean logs;
    private boolean checked;
    private final int count;
    private final int initial;
    private final Direction forward;
    private BlockPos step;
    private boolean tunneling;
    private int ticks;
    private int lastCount;
    private int emptyMines;

    GatherTask(BotPlayer bot, BotController controller, String target, int count) {
        this.bot = bot;
        this.controller = controller;
        this.target = target.replace("minecraft:", "");
        this.count = count;
        object = bot.vision().objects().stream().filter(o -> o.ref().equals(target)).findFirst().orElse(null);
        logs = this.target.equals("logs") || this.target.equals("wood") || object != null && object.category().equals("tree");
        item = logs ? Items.OAK_LOG : object != null && object.category().equals("ore") ? (object.kind().equals("iron") ? Items.RAW_IRON : BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(object.kind()))) : switch (this.target) {
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

    void collected() {
        int now = held();
        emptyMines = now > lastCount ? 0 : emptyMines + 1;
        lastCount = now;
    }

    /** Test the recorded state, including loot with a suitable probe, before asking to mine. */
    private boolean matches(BlockPos pos) {
        var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos);
        return cell != null && cell.stateId() >= 0 && matches(pos, Block.stateById(cell.stateId()));
    }

    private boolean matches(BlockPos pos, BlockState state) {
        if (object != null) {
            if (!object.bounds().contains(Vec3.atCenterOf(pos))) return false;
            if (logs && !columns.contains(BlockPos.asLong(pos.getX(), 0, pos.getZ()))) return false;
            if (!logs && !Semantics.state(state).kind().equals(object.kind())) return false;
        }
        if (logs) return state.is(BlockTags.LOGS);
        if (target.equals("coal") || target.equals("iron")) {
            var kind = Semantics.state(state);
            return kind.category().equals("ore") && kind.kind().equals(target);
        }
        if (target.equals("dirt")) return state.is(BlockTags.DIRT);
        if (target.equals("sand")) return state.is(BlockTags.SAND);
        if (state.getBlock().asItem() == item) return true;
        ItemStack tool = bot.getMainHandItem();
        for (Item probe : new Item[] {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE}) {
            ItemStack candidate = new ItemStack(probe);
            if (candidate.isCorrectToolForDrops(state)) { tool = candidate; break; }
        }
        return Block.getDrops(state, bot.serverLevel(), pos, null, bot, tool).stream().anyMatch(stack -> stack.is(item));
    }

    private boolean mine(BlockPos pos) {
        var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), pos);
        if (cell != null && cell.stateId() >= 0) {
            String hint = controller.toolHint(Block.stateById(cell.stateId()));
            if (!hint.isEmpty()) { controller.gatherFinished(hint); return false; }
        }
        controller.gatherMine(pos);
        return true;
    }

    private boolean visible(BlockPos pos) {
        var hit = bot.level().clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(pos),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    void tick() {
        if (object == null && target.matches("[a-z][0-9]+")) { noSight(); return; }
        if (held() - initial >= count) {
            controller.gatherFinished("gathered " + (held() - initial) + " " + name());
            return;
        }
        if (!checked) {
            // Close-range rays are observations too; preserve direct gather on ground underfoot.
            bot.vision().observe(Vec3.atCenterOf(bot.blockPosition().below()));
            bot.worldMemory().glance(bot, 4);
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
                    mine(seen);
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
        var candidate = bot.worldMemory().nearby(bot, 64).stream()
            .map(entry -> entry.pos()).filter(pos -> pos.getY() >= feet.getY())
            .filter(this::matches).filter(this::visible)
            .min(Comparator.comparingDouble(pos -> bot.position().distanceToSqr(Vec3.atCenterOf(pos))));
        if (candidate.isPresent()) {
            mine(candidate.get());
            return;
        }
        BlockPos ground = feet.below();
        if (object == null && (tunneling || visible(ground) && matches(ground))) {
            tunneling = true;
            step = feet.relative(forward).below();
            if (step.getY() < bot.level().getMinBuildHeight()) noSight();
            return;
        }
        // A visible surface below the feet can still supply the resource without a tunnel.
        var surface = bot.worldMemory().nearby(bot, 64).stream().map(entry -> entry.pos())
            .filter(this::matches).filter(this::visible).findFirst();
        if (surface.isPresent()) mine(surface.get());
        else noSight();
    }

    private void noSight() {
        controller.gatherFinished("none seen for " + target + "; " + controller.scene().unexplored() + "; try explore");
    }
}
