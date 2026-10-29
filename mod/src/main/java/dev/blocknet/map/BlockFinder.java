// SPDX-License-Identifier: MIT
package dev.blocknet.map;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Full perception (ADR-0012): the nearest blocks of a kind in already loaded chunks, through walls,
 * like a player who simply knows where things are. Section palettes skip sections that cannot
 * hold a match, so only the few sections with the block are walked. Never loads a chunk.
 */
public final class BlockFinder {
    private BlockFinder() {}

    /** Up to {@code limit} matches within {@code chunkRadius} chunks, nearest to {@code from} first. */
    public static List<BlockPos> nearest(ServerLevel level, BlockPos from, Predicate<BlockState> match, int chunkRadius, int limit) {
        int cx = from.getX() >> 4, cz = from.getZ() >> 4;
        List<int[]> chunks = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++)
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) chunks.add(new int[]{dx, dz});
        chunks.sort(Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        List<BlockPos> found = new ArrayList<>();
        double worst = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] offset : chunks) {
            // Once enough are found, a chunk whose nearest edge is farther than the worst kept one cannot help.
            double edge = Math.max(0, (Math.max(Math.abs(offset[0]), Math.abs(offset[1])) - 1) * 16.0);
            if (found.size() >= limit && edge * edge > worst) break;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx + offset[0], cz + offset[1]);
            if (chunk == null) continue;
            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) continue;
                int baseY = level.getSectionYFromSectionIndex(index) << 4;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (!match.test(section.getBlockState(x, y, z))) continue;
                    pos.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                    found.add(pos.immutable());
                }
            }
            if (found.size() >= limit) {
                found.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
                if (found.size() > limit * 4) found.subList(limit * 4, found.size()).clear();
                worst = found.get(Math.min(limit, found.size()) - 1).distSqr(from);
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
        return found.size() > limit ? List.copyOf(found.subList(0, limit)) : List.copyOf(found);
    }
}
