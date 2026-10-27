// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.control.CraftStations;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

/** All semantic policy lives here; namespaces of content providers are never special-cased. */
public final class Semantics {
    public record Kind(String category, String kind, boolean container) {}
    public static final Kind DARK = new Kind("dark", "dark", false);
    private static final Int2ObjectOpenHashMap<Kind> CACHE = new Int2ObjectOpenHashMap<>();
    private static int epoch;
    private Semantics() {}
    static void clear() { CACHE.clear(); epoch++; }
    static int epoch() { return epoch; }

    public static Kind state(BlockState state) {
        return CACHE.computeIfAbsent(Block.BLOCK_STATE_REGISTRY.getId(state), ignored -> classify(state));
    }

    private static Kind classify(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (state.is(BlockTags.LOGS)) return new Kind("log", path.replaceFirst("_(log|wood|stem|hyphae)$", ""), false);
        if (state.is(BlockTags.LEAVES)) return new Kind("leaves", path.replaceFirst("_leaves$", ""), false);
        String ore = state.getTags().map(tag -> tag.location()).filter(id -> id.getNamespace().equals("c")
            && id.getPath().startsWith("ores/")).map(id -> id.getPath().substring(5)).sorted().findFirst().orElse(null);
        if (ore != null) return new Kind("ore", ore, false);
        String station = CraftStations.kind(state);
        if (station == null) station = state.getTags().map(tag -> tag.location().getPath())
            .filter(tag -> tag.startsWith("player_workstations/")).sorted().findFirst().orElse(null);
        if (station != null) return new Kind("station", station, false);
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || tagged(state, "stones"))
            return new Kind("stone", "stone", false);
        if (state.is(BlockTags.DIRT)) return new Kind("dirt", "dirt", false);
        if (state.is(BlockTags.SAND)) return new Kind("sand", "sand", false);
        if (state.getBlock() instanceof FallingBlock && path.equals("gravel")) return new Kind("gravel", "gravel", false);
        if (state.getBlock() instanceof CropBlock || state.is(BlockTags.CROPS)) return new Kind("crop", path, false);
        if (built(state)) return new Kind("built", "structure", false);
        if (state.getFluidState().is(FluidTags.WATER)) return new Kind("water", "water", false);
        if (state.getFluidState().is(FluidTags.LAVA)) return new Kind("lava", "lava", false);
        return new Kind("other", path, false);
    }

    private static boolean tagged(BlockState state, String path) {
        return state.getTags().anyMatch(tag -> tag.location().getPath().equals(path));
    }

    private static boolean built(BlockState state) {
        return state.is(BlockTags.PLANKS) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOODEN_STAIRS)
            || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
            || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.RAILS)
            || tagged(state, "glass_blocks") || tagged(state, "glass_panes") || state.getBlock() instanceof TransparentBlock;
    }

    /** Called only for a cell actually reached by an eye ray, never for candidate discovery. */
    public static Kind observed(BotPlayer bot, BlockPos pos, BlockState state) {
        Kind kind = state(state);
        if (!state.hasBlockEntity()) return kind;
        var entity = bot.level().getBlockEntity(pos);
        boolean items = entity instanceof Container;
        boolean machine = false;
        for (Direction side : new Direction[] {null, Direction.DOWN, Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            items |= bot.level().getCapability(Capabilities.ItemHandler.BLOCK, pos, side) != null;
            machine |= bot.level().getCapability(Capabilities.EnergyStorage.BLOCK, pos, side) != null
                || bot.level().getCapability(Capabilities.FluidHandler.BLOCK, pos, side) != null;
        }
        if (kind.category().equals("station")) return new Kind("station", kind.kind(), items);
        if (machine || (items && !(entity instanceof Container))) return new Kind("machine", kind.kind(), items);
        return items ? new Kind("container", kind.kind(), true) : kind;
    }
}
