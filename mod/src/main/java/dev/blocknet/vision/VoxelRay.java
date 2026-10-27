// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import dev.blocknet.body.BotPlayer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Amanatides-Woo traversal, exclusively through already-loaded server chunk sections. */
public final class VoxelRay {
    public enum End { HIT, RANGE, UNKNOWN }
    public record Result(End end, BlockPos pos, Direction normal, double distance, BlockState state, boolean dark) {}
    private final BotPlayer bot;
    private final Long2ObjectOpenHashMap<LevelChunk> chunks =
        new Long2ObjectOpenHashMap<>();
    private long chunkTick = Long.MIN_VALUE;
    private ServerLevel chunkLevel;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    public VoxelRay(BotPlayer bot) { this.bot = bot; }

    /** Passed cells stream directly into the map; no per-ray list or per-air-cell allocation. */
    public Result cast(Vec3 eye, Vec3 direction, double range, ObservedMap map) {
        var level = bot.serverLevel();
        if (chunkLevel != level || chunkTick != level.getGameTime()) {
            chunks.clear(); chunkLevel = level; chunkTick = level.getGameTime();
        }
        double dx = direction.x, dy = direction.y, dz = direction.z;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        int x = Mth.floor(eye.x), y = Mth.floor(eye.y), z = Mth.floor(eye.z);
        double ax = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double ay = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double az = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = boundary(eye.x, x, dx), ty = boundary(eye.y, y, dy), tz = boundary(eye.z, z, dz);
        double t = 0, water = 0;
        Direction face = Direction.getNearest((float) -dx, (float) -dy, (float) -dz);
        int chunkX = Integer.MIN_VALUE, chunkZ = chunkX, sectionY = chunkX;
        LevelChunk chunk = null;
        LevelChunkSection section = null;
        Vec3 end = null;
        // Safety net: a ray visits at most range*sqrt(3)+1 cells; never let one stall the server.
        int steps = (int) (range * 1.75) + 64;
        while (t <= range && steps-- > 0) {
            if (x >> 4 != chunkX || z >> 4 != chunkZ) {
                chunkX = x >> 4; chunkZ = z >> 4;
                long key = ChunkPos.asLong(chunkX, chunkZ);
                chunk = chunks.get(key);
                if (chunk == null) {
                    chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (chunk != null) chunks.put(key, chunk);
                }
                sectionY = Integer.MIN_VALUE;
                if (chunk == null) return new Result(End.UNKNOWN, new BlockPos(x, y, z), face, t, null, false);
            }
            if (level.isOutsideBuildHeight(y)) return new Result(End.UNKNOWN, new BlockPos(x, y, z), face, t, null, false);
            if (y >> 4 != sectionY) {
                sectionY = y >> 4;
                section = chunk.getSection(level.getSectionIndex(y));
            }
            if (section.hasOnlyAir()) {
                double jumpX = sectionBoundary(eye.x, x, dx), jumpY = sectionBoundary(eye.y, y, dy),
                    jumpZ = sectionBoundary(eye.z, z, dz);
                double stop = Math.min(range, Math.min(jumpX, Math.min(jumpY, jumpZ)));
                // Rasterize only the crossed cells into the empty mask. Geometry/state traversal jumps the section.
                if (map != null) emptySegment(map, eye, dx, dy, dz, t, stop);
                if (stop >= range) return new Result(End.RANGE, null, face, range, null, false);
                // Step the crossing axis to the next section by integer arithmetic. Recomputing it with
                // floor(eye + d*t) can land in the same cell when d is tiny and coordinates are large
                // (the nudge is below double precision), and the ray would never leave the section.
                int oldX = x, oldY = y, oldZ = z;
                t = Math.max(t, stop);
                x = Mth.floor(eye.x + dx * t); y = Mth.floor(eye.y + dy * t); z = Mth.floor(eye.z + dz * t);
                if (jumpX <= stop) x = sx > 0 ? ((oldX >> 4) << 4) + 16 : ((oldX >> 4) << 4) - 1;
                if (jumpY <= stop) y = sy > 0 ? ((oldY >> 4) << 4) + 16 : ((oldY >> 4) << 4) - 1;
                if (jumpZ <= stop) z = sz > 0 ? ((oldZ >> 4) << 4) + 16 : ((oldZ >> 4) << 4) - 1;
                face = jumpX <= jumpY && jumpX <= jumpZ ? (sx > 0 ? Direction.WEST : Direction.EAST)
                    : jumpY <= jumpZ ? (sy > 0 ? Direction.DOWN : Direction.UP) : (sz > 0 ? Direction.NORTH : Direction.SOUTH);
                tx = boundary(eye.x, x, dx); ty = boundary(eye.y, y, dy); tz = boundary(eye.z, z, dz);
                continue;
            }
            pos.set(x, y, z);
            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
            byte opacity = OpacityTable.classify(state, level, pos);
            double leave = Math.min(range, Math.min(tx, Math.min(ty, tz)));
            boolean hit = opacity == OpacityTable.OPAQUE;
            double distance = t;
            if (opacity == OpacityTable.PARTIAL) {
                if (end == null) end = eye.add(direction.scale(range));
                var clipped = state.getVisualShape(level, pos, CollisionContext.empty()).clip(eye, end, pos);
                hit = clipped != null;
                if (hit) { distance = eye.distanceTo(clipped.getLocation()); face = clipped.getDirection(); }
            }
            // Waterlogged geometry still occludes; only a ray missing that geometry traverses its water.
            if (!hit && state.getFluidState().is(FluidTags.WATER)) {
                double segment = Math.max(0, leave - t);
                hit = water + segment >= 6;
                if (hit) distance = t + Math.max(0, 6 - water);
                water += segment;
            }
            // Glass, fluids and non-full geometry are occupied observations, never labelled empty.
            if (map != null && (hit || opacity == OpacityTable.FLUID || opacity == OpacityTable.TRANSPARENT && !state.isAir())) record(map, state, x, y, z, face, distance);
            if (hit) {
                boolean dark = distance > 4 && !litNeighbour(face);
                return new Result(End.HIT, pos.immutable(), face, distance, dark ? null : state, dark);
            }
            if (map != null && state.isAir()) map.empty(x, y, z);
            // Advance every tied axis: a corner touch must not reveal a cell off the actual ray.
            double next = Math.min(tx, Math.min(ty, tz));
            if (next > range) break;
            if (tx <= next) { x += sx; tx += ax; face = sx > 0 ? Direction.WEST : Direction.EAST; }
            if (ty <= next) { y += sy; ty += ay; face = sy > 0 ? Direction.DOWN : Direction.UP; }
            if (tz <= next) { z += sz; tz += az; face = sz > 0 ? Direction.NORTH : Direction.SOUTH; }
            t = next;
        }
        return new Result(End.RANGE, null, face, range, null, false);
    }

    private void record(ObservedMap map, BlockState state, int x, int y, int z, Direction face, double distance) {
        int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
        ObservedMap.Cell old = map.current(x, y, z);
        if (old != null && old.tick() == bot.serverLevel().getGameTime() && old.stateId() == stateId && old.epoch() == Semantics.epoch()) {
            map.seen(x, y, z, stateId, old.semantic(), face.getAxis() != Direction.Axis.Y);
            return;
        }
        boolean dark = distance > 4 && !litNeighbour(face);
        int id = dark ? ObservedMap.DARK_STATE : stateId;
        Semantics.Kind kind = dark ? Semantics.DARK : old != null && old.stateId() == id && old.epoch() == Semantics.epoch() ? old.semantic()
            : Semantics.observed(bot, pos, state);
        map.seen(x, y, z, id, kind, face.getAxis() != Direction.Axis.Y);
    }

    private boolean litNeighbour(Direction face) {
        BlockPos neighbour = pos.relative(face);
        // A light probe must not synchronously load the chunk beyond a hit face.
        if (bot.serverLevel().getChunkSource().getChunkNow(neighbour.getX() >> 4, neighbour.getZ() >> 4) == null) return false;
        return visibleLight(bot.level(), neighbour) >= 4;
    }

    /**
     * Light as the eye uses it. Vanilla's raw brightness subtracts the full night darkening from
     * sky light, which leaves only 4 under an open night sky and less in any shade; yet players see
     * trees, terrain and mobs by moonlight. Night halves sky light instead, so a moonlit field stays
     * visible while caves and closed rooms (no sky light, no block light) stay dark.
     */
    public static int visibleLight(net.minecraft.world.level.Level level, BlockPos pos) {
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) - level.getSkyDarken() / 2;
        return Math.max(block, sky);
    }

    private static double boundary(double origin, int cell, double delta) {
        return delta == 0 ? Double.POSITIVE_INFINITY : ((delta > 0 ? cell + 1 : cell) - origin) / delta;
    }
    private static double sectionBoundary(double origin, int cell, double delta) {
        return delta == 0 ? Double.POSITIVE_INFINITY : (((cell >> 4) << 4) + (delta > 0 ? 16 : 0) - origin) / delta;
    }

    private static void emptySegment(ObservedMap map, Vec3 eye, double dx, double dy, double dz, double start, double stop) {
        double t = start;
        int x = Mth.floor(eye.x + dx * t), y = Mth.floor(eye.y + dy * t), z = Mth.floor(eye.z + dz * t);
        double tx = boundary(eye.x, x, dx), ty = boundary(eye.y, y, dy), tz = boundary(eye.z, z, dz);
        int steps = (int) ((stop - start) * 1.75) + 8;
        while (t < stop && steps-- > 0) {
            map.empty(x, y, z);
            double next = Math.min(tx, Math.min(ty, tz));
            if (tx <= next) { x += dx > 0 ? 1 : -1; tx += Math.abs(1 / dx); }
            if (ty <= next) { y += dy > 0 ? 1 : -1; ty += Math.abs(1 / dy); }
            if (tz <= next) { z += dz > 0 ? 1 : -1; tz += Math.abs(1 / dz); }
            t = next;
        }
    }
}
