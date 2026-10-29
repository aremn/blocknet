// SPDX-License-Identifier: MIT
package dev.blocknet.map;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What the map has learned about one level (ADR-0011), shared by every bot: structures from the
 * server's structure starts and per-chunk cave samples, clustered into caves on demand. Ores are
 * never recorded here.
 */
public final class MapMemory extends SavedData {
    /** A structure start; {@code ref} is stable ("v1"), {@code name} is its registry id. */
    public record Structure(String ref, String name, BoundingBox box, int pieces, boolean underground) {
        public BlockPos center() { return box.getCenter(); }
    }

    /** Air sampled below the surface of one chunk; {@code entrance} is sky-lit cave air or null. */
    public record CaveChunk(int samples, int minY, int maxY, long sumX, long sumY, long sumZ,
            BlockPos entrance, int entranceLight) {}

    /** Neighbouring cave chunks with overlapping depths, with the entrance closest to the asker. */
    public record Cave(String ref, List<Long> chunks, int minY, int maxY, int volume, BlockPos center, BlockPos entrance) {}

    static final int SAMPLE_VOLUME = 48;

    private final Map<String, Structure> structures = new LinkedHashMap<>();
    private final Map<Long, CaveChunk> caveChunks = new HashMap<>();
    private final Map<Long, String> caveRefs = new HashMap<>();
    private final Set<Long> scanned = new HashSet<>();
    private int structureCounter, caveCounter;

    public static MapMemory of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MapMemory::new, MapMemory::load), "blocknet_map");
    }

    boolean scanned(long chunk) { return scanned.contains(chunk); }

    void markScanned(long chunk, CaveChunk cave) {
        scanned.add(chunk);
        if (cave != null) caveChunks.put(chunk, cave);
        setDirty();
    }

    /** Records a structure once per start; returns its ref. */
    public String structure(String name, ChunkPos start, BoundingBox box, int pieces, boolean underground) {
        String key = name + "@" + start.toLong();
        Structure known = structures.get(key);
        if (known != null) return known.ref();
        Structure structure = new Structure("v" + ++structureCounter, name, box, pieces, underground);
        structures.put(key, structure);
        setDirty();
        return structure.ref();
    }

    public Collection<Structure> structures() { return Collections.unmodifiableCollection(structures.values()); }

    public Optional<Structure> structureByRef(String ref) {
        return structures.values().stream().filter(s -> s.ref().equals(ref)).findFirst();
    }

    public CaveChunk caveChunk(long chunk) { return caveChunks.get(chunk); }

    /** Clusters cave chunks (4-neighbours whose depth ranges overlap); merged caves keep the lower ref. */
    public List<Cave> caves(BlockPos from) {
        List<Cave> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Long> order = new ArrayList<>(caveChunks.keySet());
        order.sort(Comparator.naturalOrder());
        for (long start : order) {
            if (!seen.add(start)) continue;
            List<Long> members = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                long chunk = queue.remove();
                members.add(chunk);
                CaveChunk here = caveChunks.get(chunk);
                int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
                for (long next : new long[]{ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z), ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1)}) {
                    CaveChunk other = caveChunks.get(next);
                    if (other != null && !seen.contains(next) && other.minY() <= here.maxY() + 3 && here.minY() <= other.maxY() + 3) {
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
            String ref = members.stream().map(caveRefs::get).filter(Objects::nonNull)
                .min(Comparator.comparingInt(r -> Integer.parseInt(r.substring(1)))).orElse(null);
            if (ref == null) { ref = "k" + ++caveCounter; setDirty(); }
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE, samples = 0;
            long sx = 0, sy = 0, sz = 0;
            BlockPos entrance = null;
            for (long chunk : members) {
                if (!ref.equals(caveRefs.put(chunk, ref))) setDirty();
                CaveChunk cave = caveChunks.get(chunk);
                minY = Math.min(minY, cave.minY());
                maxY = Math.max(maxY, cave.maxY());
                samples += cave.samples();
                sx += cave.sumX(); sy += cave.sumY(); sz += cave.sumZ();
                if (cave.entrance() != null && (entrance == null || cave.entrance().distSqr(from) < entrance.distSqr(from))) entrance = cave.entrance();
            }
            result.add(new Cave(ref, members, minY, maxY, samples * SAMPLE_VOLUME,
                new BlockPos((int) (sx / samples), (int) (sy / samples), (int) (sz / samples)), entrance));
        }
        return result;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        structures.forEach((key, s) -> {
            CompoundTag value = new CompoundTag();
            value.putString("key", key);
            value.putString("ref", s.ref());
            value.putString("name", s.name());
            value.putIntArray("box", new int[]{s.box().minX(), s.box().minY(), s.box().minZ(), s.box().maxX(), s.box().maxY(), s.box().maxZ()});
            value.putInt("pieces", s.pieces());
            value.putBoolean("underground", s.underground());
            list.add(value);
        });
        tag.put("structures", list);
        ListTag caves = new ListTag();
        caveChunks.forEach((chunk, c) -> {
            CompoundTag value = new CompoundTag();
            value.putLong("chunk", chunk);
            value.putIntArray("y", new int[]{c.samples(), c.minY(), c.maxY(), c.entranceLight()});
            value.putLongArray("sum", new long[]{c.sumX(), c.sumY(), c.sumZ()});
            if (c.entrance() != null) value.putLong("entrance", c.entrance().asLong());
            String ref = caveRefs.get(chunk);
            if (ref != null) value.putString("ref", ref);
            caves.add(value);
        });
        tag.put("caves", caves);
        tag.putLongArray("scanned", scanned.stream().mapToLong(Long::longValue).toArray());
        tag.putInt("structureCounter", structureCounter);
        tag.putInt("caveCounter", caveCounter);
        return tag;
    }

    public static MapMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        MapMemory memory = new MapMemory();
        for (Tag raw : tag.getList("structures", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            int[] b = value.getIntArray("box");
            if (b.length != 6) continue;
            memory.structures.put(value.getString("key"), new Structure(value.getString("ref"), value.getString("name"),
                new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]), value.getInt("pieces"), value.getBoolean("underground")));
        }
        for (Tag raw : tag.getList("caves", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            int[] y = value.getIntArray("y");
            long[] sum = value.getLongArray("sum");
            if (y.length != 4 || sum.length != 3) continue;
            long chunk = value.getLong("chunk");
            memory.caveChunks.put(chunk, new CaveChunk(y[0], y[1], y[2], sum[0], sum[1], sum[2],
                value.contains("entrance") ? BlockPos.of(value.getLong("entrance")) : null, y[3]));
            if (value.contains("ref")) memory.caveRefs.put(chunk, value.getString("ref"));
        }
        for (long chunk : tag.getLongArray("scanned")) memory.scanned.add(chunk);
        memory.structureCounter = tag.getInt("structureCounter");
        memory.caveCounter = tag.getInt("caveCounter");
        return memory;
    }
}
