// SPDX-License-Identifier: MIT
package dev.blocknet.map;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.vision.Frontiers;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** The bot's map as short text for the brain, plus ground spots to walk to (ADR-0011). */
public final class WorldMap {
    private static final int GRID = 4;

    private WorldMap() {}

    /** A place the bot can walk to: where to stand, and a line describing it. */
    public record Target(BlockPos stand, String label) {}

    public static String text(BotPlayer bot, String focus) {
        String f = focus == null ? "" : focus.trim().toLowerCase(Locale.ROOT);
        ServerLevel level = bot.serverLevel();
        MapMemory memory = MapMemory.of(level);
        List<String> lines = new ArrayList<>();
        if (f.isEmpty()) {
            lines.add("map: 1 cell = 16 blocks, north up; @ you, P player, S structure, C cave entrance, ~ water, ! lava, "
                + "^ 12+ above you, v 12+ below, . land, ? not loaded");
            lines.addAll(grid(bot, memory));
        }
        List<String> places = new ArrayList<>();
        for (var s : memory.structures()) {
            if (!f.isEmpty() && !s.name().contains(f) && !f.equals(s.ref()) && !f.equals("structure")) continue;
            places.add(new Place(s.ref(), s.center(), s.name() + " (" + s.pieces() + " pieces" + (s.underground() ? ", underground" : "")
                + ")").describe(bot));
        }
        for (var c : memory.caves(bot.blockPosition())) {
            if (!f.isEmpty() && !f.equals("cave") && !f.equals("caves") && !f.equals(c.ref())) continue;
            BlockPos at = c.entrance() != null ? c.entrance() : c.center();
            places.add(new Place(c.ref(), at, "cave" + (c.entrance() != null ? " entrance at y " + c.entrance().getY() : " (no entrance known)")
                + ", spans y " + c.minY() + ".." + c.maxY() + ", ~" + c.volume() + " air").describe(bot));
        }
        places.sort(Comparator.comparingInt(WorldMap::distanceOf));
        lines.add("places: " + (places.isEmpty() ? (f.isEmpty() ? "none known yet; the map fills in as you move" : "none matching " + f)
            : places.stream().limit(8).map(p -> p.substring(p.indexOf('|') + 1)).collect(Collectors.joining("; "))));
        // Things the eyes saw anywhere, even far away and long ago (memory index, RAM and disk).
        var remembered = bot.worldMemory().observed().remembered(bot.level().dimension().location().toString(), bot.blockPosition(),
            key -> f.isEmpty() ? !key.startsWith("ore:") : key.contains(f) || f.equals("stations") && key.startsWith("station:")
                || f.equals("containers") && key.startsWith("container:") || f.equals("machines") && key.startsWith("machine:"), 8);
        if (!remembered.isEmpty()) lines.add("remembered (seen before): " + remembered.stream().map(r -> r.kind() + " "
            + where(bot, Vec3.atCenterOf(r.pos())) + " at " + r.pos().getX() + " " + r.pos().getY() + " " + r.pos().getZ())
            .collect(Collectors.joining("; ")));
        java.util.function.Predicate<Radar.Contact> matches = c -> f.isEmpty() || c.type().contains(f) || c.ref().equals(f)
            || c.category().equals(f) || c.name().toLowerCase(Locale.ROOT).contains(f) || f.equals("items") && c.category().equals("item");
        List<Radar.Contact> contacts = bot.radar().contacts().stream().filter(c -> !c.category().equals("player") && !c.category().equals("item"))
            .filter(matches).limit(10).toList();
        lines.add("creatures: " + (contacts.isEmpty() ? "none within " + (int) Radar.RANGE + "m" : contacts.stream().map(c -> contact(bot, c))
            .collect(Collectors.joining("; "))));
        List<Radar.Contact> items = bot.radar().contacts().stream().filter(c -> c.category().equals("item")).filter(matches).limit(8).toList();
        if (!items.isEmpty()) lines.add("items on the ground: " + items.stream().map(c -> contact(bot, c)).collect(Collectors.joining("; ")));
        List<Radar.Contact> players = bot.radar().contacts().stream().filter(c -> c.category().equals("player")).filter(matches).limit(4).toList();
        if (!players.isEmpty()) lines.add("players: " + players.stream().map(c -> contact(bot, c)).collect(Collectors.joining("; ")));
        return String.join("\n", lines);
    }

    public static String contact(BotPlayer bot, Radar.Contact c) {
        long ago = (bot.serverLevel().getGameTime() - c.lastSeen()) / 20;
        return (c.category().equals("player") ? c.label() : c.ref() + " " + c.type() + (c.category().equals("item") ? " x" + c.count() : ""))
            + " " + where(bot, c.position())
            + (c.category().equals("hostile") ? " hostile" : "") + (c.live() ? "" : " (last seen " + ago + "s ago)");
    }

    private static String where(BotPlayer bot, Vec3 pos) {
        Vec3 delta = pos.subtract(bot.position());
        long dy = Math.round(delta.y);
        return Frontiers.direction(delta) + " " + Math.round(Math.sqrt(delta.x * delta.x + delta.z * delta.z)) + "m"
            + (Math.abs(dy) >= 6 ? (dy > 0 ? " up " : " down ") + Math.abs(dy) : "");
    }

    private record Place(String ref, BlockPos at, String what) {
        String describe(BotPlayer bot) {
            int distance = (int) Math.round(Math.sqrt(bot.blockPosition().distToCenterSqr(at.getX(), bot.getY(), at.getZ())));
            return String.format(Locale.ROOT, "%08d|%s %s %s", distance, ref, what, where(bot, Vec3.atCenterOf(at)));
        }
    }

    private static int distanceOf(String described) { return Integer.parseInt(described.substring(0, described.indexOf('|'))); }

    private static List<String> grid(BotPlayer bot, MapMemory memory) {
        ServerLevel level = bot.serverLevel();
        ChunkPos center = bot.chunkPosition();
        Map<Long, Character> marks = new HashMap<>();
        memory.caves(bot.blockPosition()).forEach(c -> c.chunks().forEach(chunk -> {
            var cave = memory.caveChunk(chunk);
            if (cave != null && cave.entrance() != null) marks.put(chunk, 'C');
        }));
        memory.structures().stream().filter(s -> !s.underground()).forEach(s -> marks.put(new ChunkPos(s.center()).toLong(), 'S'));
        bot.radar().contacts().stream().filter(c -> c.live() && c.category().equals("player"))
            .forEach(c -> marks.put(new ChunkPos(BlockPos.containing(c.position())).toLong(), 'P'));
        marks.put(center.toLong(), '@');
        List<String> rows = new ArrayList<>();
        for (int dz = -GRID; dz <= GRID; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -GRID; dx <= GRID; dx++) {
                long key = ChunkPos.asLong(center.x + dx, center.z + dz);
                Character mark = marks.get(key);
                row.append(mark != null ? mark : terrain(level, key, bot.getBlockY()));
                if (dx < GRID) row.append(' ');
            }
            rows.add(row.toString());
        }
        return rows;
    }

    private static char terrain(ServerLevel level, long key, int botY) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null) return '?';
        int water = 0, lava = 0, height = 0;
        for (int i = 0; i < 4; i++) {
            int x = chunk.getPos().getMinBlockX() + 4 + (i % 2) * 8;
            int z = chunk.getPos().getMinBlockZ() + 4 + (i / 2) * 8;
            BlockPos top = new BlockPos(x, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
            var fluid = chunk.getFluidState(top);
            if (fluid.is(FluidTags.WATER)) water++;
            else if (fluid.is(FluidTags.LAVA)) lava++;
            height += chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        if (lava > 0) return '!';
        if (water >= 2) return '~';
        int dy = height / 4 - botY;
        return dy >= 12 ? '^' : dy <= -12 ? 'v' : '.';
    }

    /** Where to stand for a map ref (structure "v1" or cave "k1"); null when the ref is unknown. */
    @Nullable
    public static Target target(BotPlayer bot, String ref) {
        ServerLevel level = bot.serverLevel();
        MapMemory memory = MapMemory.of(level);
        var structure = memory.structureByRef(ref);
        if (structure.isPresent()) {
            BlockPos center = structure.get().center();
            BlockPos ground = structure.get().underground() ? null : groundNear(level, center, 10, bot.blockPosition());
            return new Target(ground != null ? ground : center, ref + " " + structure.get().name());
        }
        for (var cave : memory.caves(bot.blockPosition())) {
            if (!cave.ref().equals(ref)) continue;
            if (cave.entrance() == null) return new Target(null, ref + " cave has no known entrance");
            BlockPos floor = floorBelow(level, cave.entrance(), 12);
            return new Target(floor != null ? floor : cave.entrance(), ref + " cave entrance");
        }
        return null;
    }

    /** The nearest known cave entrance within {@code radius} horizontal blocks, as a place to stand. */
    @Nullable
    public static Target nearestCaveEntrance(BotPlayer bot, int radius) {
        return MapMemory.of(bot.serverLevel()).caves(bot.blockPosition()).stream().filter(c -> c.entrance() != null)
            .filter(c -> horizontal(c.entrance(), bot.blockPosition()) <= (double) radius * radius)
            .min(Comparator.comparingDouble(c -> horizontal(c.entrance(), bot.blockPosition())))
            .map(c -> target(bot, c.ref())).orElse(null);
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Ground to stand on within {@code radius} columns of {@code center}: the lowest dry surface,
     * so a roof or a treetop never wins over the street next to it; ties go to the side nearer
     * {@code from}. Only loaded chunks count.
     */
    @Nullable
    public static BlockPos groundNear(ServerLevel level, BlockPos center, int radius, BlockPos from) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                BlockPos feet = new BlockPos(x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1, z);
                if (!standable(level, feet)) continue;
                double score = feet.getY() + Math.sqrt(horizontal(feet, from)) * 0.05 + Math.sqrt(dx * dx + dz * dz) * 0.1;
                if (score < bestScore) { bestScore = score; best = feet; }
            }
        }
        return best;
    }

    /** Ground spots around a structure's footprint (expanded by 3), at the level of its lowest neighbour. */
    @Nullable
    public static BlockPos groundBeside(ServerLevel level, int minX, int minZ, int maxX, int maxZ, BlockPos from) {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = minX - 3; x <= maxX + 3; x++) {
            for (int z = minZ - 3; z <= maxZ + 3; z++) {
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) continue;
                BlockPos feet = new BlockPos(x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1, z);
                if (standable(level, feet)) ring.add(feet);
            }
        }
        int lowest = ring.stream().mapToInt(BlockPos::getY).min().orElse(Integer.MAX_VALUE);
        return ring.stream().filter(p -> p.getY() <= lowest + 1).min(Comparator.comparingDouble(p -> p.distSqr(from))).orElse(null);
    }

    @Nullable
    private static BlockPos floorBelow(ServerLevel level, BlockPos start, int depth) {
        for (int down = 0; down <= depth; down++) {
            BlockPos feet = start.below(down);
            if (standable(level, feet)) return feet;
        }
        return null;
    }

    /** Feet and head free, a dry block to stand on below. */
    private static boolean standable(ServerLevel level, BlockPos feet) {
        var below = level.getBlockState(feet.below());
        var shape = below.getCollisionShape(level, feet.below());
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5 && below.getFluidState().isEmpty()
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getFluidState(feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }
}
