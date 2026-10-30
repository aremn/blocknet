// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * "Расчисти площадку": a square around a centre is brought to one floor height, the block the
 * player stands on. Natural blocks above it (trees, soil, stone, plants) are broken like a player
 * breaks them, and holes in the floor are filled from the bag. Anything else (planks, glass,
 * machines, chests, crops) is someone's build and is left alone, so the bot never wrecks a base.
 */
final class ClearTask extends SkillTask {
    static final int MAX_RADIUS = BotController.CLEAR_MAX_RADIUS;
    /** How high above the floor natural blocks are taken down (tall trees). */
    private static final int HEIGHT = 12;
    /** Leaves higher than this decay by themselves once their logs are gone. */
    private static final int LEAF_HEIGHT = 3;
    /** How deep a hole in the floor is filled. */
    private static final int FILL_DEPTH = 4;
    private final BlockPos centre;
    private final int radius, floor;
    private final Set<BlockPos> skipped = new HashSet<>();
    private final Map<String, Integer> broken = new LinkedHashMap<>();
    private int filled;
    private boolean outOfFill;

    ClearTask(BotPlayer bot, BlockPos centre, int radius, Consumer<String> report) {
        super(bot, report, 20 * 60 * 15);
        this.centre = centre.immutable();
        this.radius = radius;
        // The centre is where the player stands: the floor is the block under their feet.
        this.floor = centre.getY() - 1;
    }

    /** Ground, stone, trees and wild plants: what a world generates, by tags, never by mod. */
    static boolean natural(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
            || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Tags.Blocks.GRAVELS) || state.is(Tags.Blocks.ORES)
            || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.REPLACEABLE_BY_TREES)
            || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(Blocks.CLAY) || state.is(Blocks.BROWN_MUSHROOM)
            || state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.COBWEB);
    }

    @Override String describe() {
        int count = broken.values().stream().mapToInt(Integer::intValue).sum();
        return "clearing " + (2 * radius + 1) + "x" + (2 * radius + 1) + " to floor y=" + floor + ": broke " + count + ", filled " + filled;
    }

    @Override void work() {
        if (busy) return;
        BlockPos next = nextToBreak();
        if (next != null) {
            BlockState state = bot.serverLevel().getBlockState(next);
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            action(cb -> controller.mine(next, cb), message -> {
                // Gone or changed (sand that fell into its place is a new block to take next).
                if (bot.serverLevel().getBlockState(next) != state) broken.merge(name, 1, Integer::sum);
                else { skipped.add(next); controller.trace("clear", "left " + name + " at " + next.toShortString() + ": " + message); }
            });
            return;
        }
        BlockPos hole = outOfFill ? null : nextHole();
        if (hole != null) {
            int slot = fillSlot();
            if (slot < 0) { outOfFill = true; return; }
            ItemStack fill = bot.getInventory().getItem(slot).copyWithCount(1);
            action(cb -> controller.place(fill, hole, Direction.UP, cb), message -> {
                if (message.startsWith("placed")) filled++;
                else { skipped.add(hole); controller.trace("clear", "could not fill " + hole.toShortString() + ": " + message); }
            });
            return;
        }
        complete(summary());
    }

    private String summary() {
        int count = broken.values().stream().mapToInt(Integer::intValue).sum();
        int holes = holesLeft();
        StringBuilder text = new StringBuilder("cleared " + (2 * radius + 1) + "x" + (2 * radius + 1) + " around "
            + centre.getX() + " " + centre.getZ() + " to floor y=" + floor + ": broke " + count);
        if (!broken.isEmpty()) {
            text.append(" (");
            broken.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(4)
                .forEach(e -> text.append(e.getValue()).append(' ').append(e.getKey()).append(", "));
            text.setLength(text.length() - 2);
            text.append(')');
        }
        text.append(", filled ").append(filled).append(filled == 1 ? " hole" : " holes");
        int builds = 0;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, 0, -radius), centre.offset(radius, HEIGHT, radius))) {
            BlockState state = bot.serverLevel().getBlockState(pos);
            // Bedrock and barriers are nobody's build and nothing a player could break.
            if (!state.isAir() && !natural(state) && state.getFluidState().isEmpty() && state.getDestroySpeed(bot.serverLevel(), pos) >= 0) builds++;
        }
        if (builds > 0) text.append("; left ").append(builds).append(" blocks of builds alone");
        long unreachable = skipped.stream().filter(p -> !bot.serverLevel().getBlockState(p).isAir()).count();
        if (unreachable > 0) text.append("; could not break ").append(unreachable).append(" natural blocks (out of reach or no tool)");
        if (holes > 0) text.append("; ").append(holes).append(outOfFill ? " holes left: no dirt or stone to fill them" : " holes left");
        return (holes > 0 || unreachable > 0 ? "partly " : "") + text;
    }

    /** The highest natural block above the floor in the square, nearest the bot first. */
    @Nullable
    private BlockPos nextToBreak() {
        var level = bot.serverLevel();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, 0, -radius), centre.offset(radius, HEIGHT, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !natural(state) || skipped.contains(pos)) continue;
            int above = pos.getY() - floor;
            if (state.is(BlockTags.LEAVES) && above > LEAF_HEIGHT) continue;
            // Logs first (a tree's leaves then fall by themselves), then top-down so nothing is left hanging.
            double score = (state.is(BlockTags.LOGS) ? 0 : 1000) - above * 10 + pos.distSqr(bot.blockPosition()) * 0.01;
            if (score < bestScore) { bestScore = score; best = pos.immutable(); }
        }
        return best;
    }

    /** A floor cell with nothing to stand on, whose lowest open cell rests on something solid. */
    @Nullable
    private BlockPos nextHole() {
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            BlockPos top = new BlockPos(centre.getX() + dx, floor, centre.getZ() + dz);
            if (!open(top)) continue;
            BlockPos cell = top;
            while (cell.getY() > floor - FILL_DEPTH && open(cell.below())) cell = cell.below();
            if (open(cell.below()) || skipped.contains(cell)) continue;
            return cell;
        }
        return null;
    }

    private int holesLeft() {
        int holes = 0;
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
            if (open(new BlockPos(centre.getX() + dx, floor, centre.getZ() + dz))) holes++;
        return holes;
    }

    private boolean open(BlockPos pos) {
        BlockState state = bot.serverLevel().getBlockState(pos);
        return state.canBeReplaced() && (state.isAir() || natural(state) || !state.getFluidState().isEmpty());
    }

    /** Dirt, cobblestone or other plain ground from the bag; never the tools or a build block. */
    private int fillSlot() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem block && natural(block.getBlock().defaultBlockState())
                    && !block.getBlock().defaultBlockState().is(BlockTags.LOGS) && !block.getBlock().defaultBlockState().is(BlockTags.LEAVES)
                    && block.getBlock().defaultBlockState().isCollisionShapeFullBlock(bot.serverLevel(), BlockPos.ZERO)
                    && !block.getBlock().defaultBlockState().is(BlockTags.SAND) && !block.getBlock().defaultBlockState().is(Tags.Blocks.GRAVELS)
                    && !block.getBlock().defaultBlockState().is(Tags.Blocks.ORES)) {
                return slot;
            }
            if (stack.is(net.minecraft.world.item.Items.COBBLESTONE) || stack.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE)) return slot;
        }
        return -1;
    }
}
