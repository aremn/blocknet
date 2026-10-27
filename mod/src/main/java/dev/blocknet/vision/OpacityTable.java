// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** Lazy state-id table. Dynamic shapes are never cached as full or empty. */
public final class OpacityTable {
    public static final byte OPAQUE = 1, TRANSPARENT = 2, PARTIAL = 3, FLUID = 4;
    private static byte[] classes = new byte[Block.BLOCK_STATE_REGISTRY.size()];
    private OpacityTable() {}

    public static void reload(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            classes = new byte[Block.BLOCK_STATE_REGISTRY.size()];
            Semantics.clear();
        }
    }

    public static byte classify(BlockState state, BlockGetter level, BlockPos pos) {
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id >= classes.length) classes = Arrays.copyOf(classes, id + 1024);
        byte result = classes[id];
        if (result != 0) return result;
        if (state.is(BlockTags.LEAVES) || state.getFluidState().is(FluidTags.LAVA)) result = OPAQUE;
        else if (state.getBlock().hasDynamicShape()) result = PARTIAL;
        else {
            var shape = state.getVisualShape(level, pos, CollisionContext.empty());
            result = shape.isEmpty() ? (state.getFluidState().is(FluidTags.WATER) ? FLUID : TRANSPARENT)
                : Block.isShapeFullBlock(shape) ? OPAQUE : PARTIAL;
        }
        classes[id] = result;
        return result;
    }
}
