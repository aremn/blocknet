// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import com.google.gson.*;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.vision.ObservedMap;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** Read-only projection of eye memory. Never reads world blocks or invokes a sensor. */
public final class MemorySnapshot {
    private MemorySnapshot() {}
    public static JsonObject capture(BotPlayer bot, JsonObject args) {
        if (args.has("mode") && args.get("mode").getAsString().equals("voxels")) return voxels(bot, args);
        String dimension = bot.level().dimension().location().toString();
        ObservedMap memory = bot.worldMemory().observed();
        int x = integer(args, "x", bot.getBlockX()), z = integer(args, "z", bot.getBlockZ());
        int radius = Math.clamp(integer(args, "radius", 48), 4, 64);
        int ceiling = Math.clamp(integer(args, "y", bot.getBlockY() + 16), -2048, 2048);
        boolean slice = args.has("mode") && args.get("mode").getAsString().equals("slice");
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000) throw new IllegalArgumentException("Map center out of range");
        long now = bot.serverLevel().getGameTime();
        record Known(BlockPos pos, ObservedMap.Cell cell) {}
        Map<Long, Known> columns = new TreeMap<>();
        int[] heights = {bot.getBlockY(), bot.getBlockY()};
        memory.forEach(dimension, (pos, cell) -> {
            heights[0] = Math.min(heights[0], pos.getY()); heights[1] = Math.max(heights[1], pos.getY());
            if (Math.abs((long) pos.getX() - x) > radius || Math.abs((long) pos.getZ() - z) > radius
                || (slice ? pos.getY() != ceiling : pos.getY() > ceiling)) return;
            long key = BlockPos.asLong(pos.getX(), 0, pos.getZ());
            Known old = columns.get(key);
            if (old == null || old.pos().getY() < pos.getY()) columns.put(key, new Known(pos, cell));
        });
        JsonObject out = new JsonObject();
        out.addProperty("bot", bot.getGameProfile().getName()); out.addProperty("dimension", dimension);
        out.addProperty("tick", now); out.addProperty("mode", slice ? "slice" : "top");
        out.addProperty("x", x); out.addProperty("z", z); out.addProperty("y", ceiling); out.addProperty("radius", radius);
        out.addProperty("minY", heights[0]); out.addProperty("maxY", heights[1]);
        out.addProperty("sections", memory.sectionCount()); out.addProperty("archivedSections", memory.archivedCount()); out.addProperty("knownBlocks", memory.cellCount());
        out.addProperty("knownAir", memory.emptyCount()); out.addProperty("perception", bot.fullPerception() ? "full" : "assisted");
        out.addProperty("task", bot.controller().describe());
        out.add("position", position(bot.blockPosition()));
        JsonArray cells = new JsonArray();
        columns.values().forEach(k -> {
            JsonArray row = position(k.pos());
            row.add(k.cell().stateId() < 0 ? "unknown_dark" : BuiltInRegistries.BLOCK.getKey(Block.stateById(k.cell().stateId()).getBlock()).toString());
            row.add(k.cell().semantic().category()); row.add(Math.max(0, now - k.cell().tick()) / 20);
            cells.add(row);
        });
        if (slice) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            BlockPos pos = new BlockPos(x + dx, ceiling, z + dz);
            if (memory.empty(dimension, pos)) { JsonArray row = position(pos); row.add("air"); row.add("air"); row.add(JsonNull.INSTANCE); cells.add(row); }
        }
        out.add("cells", cells);
        JsonArray objects = new JsonArray();
        // Raw graph queries don't scan blocks and don't rewrite the observed map.
        memory.graph(dimension).objects(bot.getEyePosition(), now - 4).stream().limit(512).forEach(o -> {
            JsonObject row = new JsonObject(); row.addProperty("ref", o.ref()); row.addProperty("kind", o.kind());
            row.addProperty("category", o.category()); row.add("position", position(o.nearest()));
            row.addProperty("age", Math.max(0, now - o.lastSeenTick()) / 20); row.addProperty("blocks", o.knownMembers()); objects.add(row);
        });
        out.add("objects", objects);
        out.addProperty("containers", bot.containerMemory().text(bot.getServer().overworld().getGameTime(), null));
        out.addProperty("job", dev.blocknet.memory.JobMemory.of(bot).transfer().toString());
        return out;
    }
    /**
     * The remembered blocks around a point for the 3D viewer: only cells with a face toward air or
     * the unknown (the rest can never be seen), as flat {@code dx, dy, dz, palette, ageSeconds}
     * rows relative to the center, at most {@link #VOXEL_LIMIT}, nearest first.
     */
    static JsonObject voxels(BotPlayer bot, JsonObject args) {
        String dimension = bot.level().dimension().location().toString();
        ObservedMap memory = bot.worldMemory().observed();
        BlockPos center = new BlockPos(integer(args, "x", bot.getBlockX()), integer(args, "y", bot.getBlockY()), integer(args, "z", bot.getBlockZ()));
        if (Math.abs((long) center.getX()) > 30_000_000 || Math.abs((long) center.getZ()) > 30_000_000) throw new IllegalArgumentException("Map center out of range");
        int radius = Math.clamp(integer(args, "radius", 64), 8, 96);
        long now = bot.serverLevel().getGameTime();
        Map<Long, ObservedMap.Cell> known = new HashMap<>();
        memory.forEachNear(dimension, center, radius, (pos, cell) -> {
            if (Math.abs(pos.getX() - center.getX()) <= radius && Math.abs(pos.getY() - center.getY()) <= radius
                && Math.abs(pos.getZ() - center.getZ()) <= radius) known.put(pos.asLong(), cell);
        });
        record Voxel(BlockPos pos, ObservedMap.Cell cell, long distance) {}
        List<Voxel> surface = new ArrayList<>();
        known.forEach((key, cell) -> {
            BlockPos pos = BlockPos.of(key);
            for (var side : net.minecraft.core.Direction.values()) {
                if (!known.containsKey(pos.relative(side).asLong())) {
                    surface.add(new Voxel(pos, cell, pos.distManhattan(center)));
                    break;
                }
            }
        });
        surface.sort(Comparator.comparingLong(Voxel::distance));
        Map<String, Integer> palette = new LinkedHashMap<>();
        JsonArray paletteOut = new JsonArray();
        JsonArray rows = new JsonArray();
        for (Voxel v : surface.subList(0, Math.min(VOXEL_LIMIT, surface.size()))) {
            String id = v.cell().stateId() < 0 ? "unknown_dark" : BuiltInRegistries.BLOCK.getKey(Block.stateById(v.cell().stateId()).getBlock()).toString();
            String category = v.cell().semantic().category();
            int index = palette.computeIfAbsent(id + "|" + category, ignored -> {
                JsonArray entry = new JsonArray(); entry.add(id); entry.add(category); paletteOut.add(entry);
                return paletteOut.size() - 1;
            });
            rows.add(v.pos().getX() - center.getX()); rows.add(v.pos().getY() - center.getY()); rows.add(v.pos().getZ() - center.getZ());
            rows.add(index); rows.add(Math.max(0, now - v.cell().tick()) / 20);
        }
        JsonObject out = new JsonObject();
        out.addProperty("bot", bot.getGameProfile().getName()); out.addProperty("dimension", dimension);
        out.addProperty("mode", "voxels"); out.addProperty("tick", now); out.addProperty("radius", radius);
        out.add("center", position(center)); out.add("position", position(bot.blockPosition()));
        out.addProperty("yaw", bot.getYRot()); out.addProperty("pitch", bot.getXRot());
        out.addProperty("knownBlocks", memory.cellCount()); out.addProperty("archivedSections", memory.archivedCount()); out.addProperty("surface", surface.size());
        out.addProperty("truncated", surface.size() > VOXEL_LIMIT);
        out.addProperty("perception", bot.fullPerception() ? "full" : "assisted");
        out.addProperty("task", bot.controller().describe());
        out.add("palette", paletteOut); out.add("voxels", rows);
        JsonArray objects = new JsonArray();
        memory.graph(dimension).objects(bot.getEyePosition(), now - 4).stream().limit(512).forEach(o -> {
            JsonObject row = new JsonObject(); row.addProperty("ref", o.ref()); row.addProperty("kind", o.kind());
            row.addProperty("category", o.category()); row.add("position", position(o.nearest()));
            row.addProperty("age", Math.max(0, now - o.lastSeenTick()) / 20); objects.add(row);
        });
        out.add("objects", objects);
        return out;
    }

    static final int VOXEL_LIMIT = 250_000;

    private static int integer(JsonObject args, String key, int fallback) { return args.has(key) ? args.get(key).getAsInt() : fallback; }
    private static JsonArray position(BlockPos pos) { JsonArray a = new JsonArray(); a.add(pos.getX()); a.add(pos.getY()); a.add(pos.getZ()); return a; }
}
