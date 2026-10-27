/*
 * This file is part of Blocknet's pathing module, derived from Baritone/Automatone.
 *
 * It is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 */

package dev.blocknet.pathing.utils;

import javax.annotation.Nullable;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Loaded chunks for any thread. Vanilla's {@code getChunkNow} returns null outside the server
 * thread, but paths are calculated in the background, so this reads the chunk holder directly
 * (made public by the mod's access transformer), like Automatone's mixin did.
 */
public final class LoadedChunks {
    private LoadedChunks() {}

    @Nullable
    public static LevelChunk get(ServerChunkCache cache, int chunkX, int chunkZ) {
        ChunkHolder holder = cache.getVisibleChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        return holder == null ? null : holder.getTickingChunk();
    }
}
