// SPDX-License-Identifier: MIT
package dev.blocknet.map;

import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fills {@link MapMemory} from chunks that are already loaded around bots, nearest first, one
 * chunk per tick (17x17 chunks fill in about 15 s). Never loads a chunk. Samples cave air on a 4x3x4 grid; ores are not looked at.
 */
public final class MapScanner {
    /** Chunks around each bot that the map covers. */
    public static final int RADIUS = 8;
    private static final int CHUNKS_PER_TICK = 1;
    /** The map must never compete with vision for the tick: stop after this many nanoseconds. */
    private static final long NANOS_PER_TICK = 300_000;
    private static final Map<ServerLevel, LinkedHashSet<Long>> QUEUES = new WeakHashMap<>();

    private MapScanner() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 == 0) {
            for (BotPlayer bot : BotManager.all()) {
                if (bot.isRemoved()) continue;
                enqueue(bot.serverLevel(), bot.chunkPosition());
            }
        }
        int budget = CHUNKS_PER_TICK;
        long deadline = System.nanoTime() + NANOS_PER_TICK;
        for (var entry : QUEUES.entrySet()) {
            var iterator = entry.getValue().iterator();
            while (budget > 0 && iterator.hasNext() && System.nanoTime() < deadline) {
                long chunk = iterator.next();
                iterator.remove();
                if (scan(entry.getKey(), chunk)) budget--;
            }
        }
    }

    private static void enqueue(ServerLevel level, ChunkPos center) {
        MapMemory memory = MapMemory.of(level);
        var queue = QUEUES.computeIfAbsent(level, ignored -> new LinkedHashSet<>());
        List<Long> missing = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                long chunk = ChunkPos.asLong(center.x + dx, center.z + dz);
                if (!memory.scanned(chunk) && !queue.contains(chunk)) missing.add(chunk);
            }
        }
        missing.sort(Comparator.comparingInt(c -> Math.abs(ChunkPos.getX(c) - center.x) + Math.abs(ChunkPos.getZ(c) - center.z)));
        queue.addAll(missing);
    }

    /** Scans one loaded chunk now; returns false when it is not loaded (it is retried later). */
    public static boolean scan(ServerLevel level, long chunkKey) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        if (chunk == null) return false;
        MapMemory memory = MapMemory.of(level);
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (var entry : chunk.getAllStarts().entrySet()) record(level, memory, registry.getKey(entry.getKey()), entry.getKey(), entry.getValue());
        // Pieces reaching into this chunk name their start chunk; record it if that chunk is loaded too.
        for (var entry : chunk.getAllReferences().entrySet()) {
            for (long startKey : entry.getValue()) {
                LevelChunk start = level.getChunkSource().getChunkNow(ChunkPos.getX(startKey), ChunkPos.getZ(startKey));
                if (start != null) record(level, memory, registry.getKey(entry.getKey()), entry.getKey(), start.getStartForStructure(entry.getKey()));
            }
        }
        memory.markScanned(chunkKey, caves(level, chunk));
        return true;
    }

    private static void record(ServerLevel level, MapMemory memory, net.minecraft.resources.ResourceLocation id, Structure structure, StructureStart start) {
        if (id == null || start == null || !start.isValid()) return;
        String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
        GenerationStep.Decoration step = structure.step();
        memory.structure(name, start.getChunkPos(), start.getBoundingBox(), start.getPieces().size(),
            step != GenerationStep.Decoration.SURFACE_STRUCTURES);
    }

    private static MapMemory.CaveChunk caves(ServerLevel level, LevelChunk chunk) {
        int samples = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, light = 0;
        long sx = 0, sy = 0, sz = 0;
        BlockPos entrance = null;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bottom = level.getMinBuildHeight() + 4;
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                int x = chunk.getPos().getMinBlockX() + cx * 4 + 2;
                int z = chunk.getPos().getMinBlockZ() + cz * 4 + 2;
                // getHeight is the top block itself.
                int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
                for (int y = bottom; y < surface - 6; y += 3) {
                    pos.set(x, y, z);
                    if (!chunk.getBlockState(pos).isAir()) continue;
                    samples++;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    sx += x; sy += y; sz += z;
                    int sky = level.getBrightness(LightLayer.SKY, pos);
                    if (sky > light || sky == light && sky > 0 && entrance != null && y > entrance.getY()) {
                        light = sky;
                        entrance = pos.immutable();
                    }
                }
            }
        }
        if (samples < 3) return null;
        return new MapMemory.CaveChunk(samples, minY, maxY, sx, sy, sz, light > 0 ? entrance : null, light);
    }
}
