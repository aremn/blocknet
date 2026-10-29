// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;

/**
 * Section-sparse eye evidence. No method here reads the world. RAM holds a working set of recently
 * used sections ({@link #MAX_SECTIONS}); older ones move to an unlimited {@link MemoryArchive} on
 * disk and come back when used again, so nothing the bot saw is ever forgotten. A small index of
 * notable blocks (containers, stations, machines, ores) covers RAM and archive alike.
 */
public final class ObservedMap {
    /** Sections kept in RAM (and in the SavedData); the rest live in the archive. */
    public static final int MAX_SECTIONS = 3072;
    public static final int DARK_STATE = -1;
    public record Cell(int stateId, long tick, Semantics.Kind semantic, boolean side, int epoch) {}
    private static final class Section {
        final long[] empty = new long[64];
        final Int2ObjectOpenHashMap<Cell> cells = new Int2ObjectOpenHashMap<>();
    }
    private final Map<String, Long2ObjectLinkedOpenHashMap<Section>> dimensions = new LinkedHashMap<>();
    private final Map<String, ObjectGraph> graphs = new HashMap<>();
    private String active = "";
    private Long2ObjectLinkedOpenHashMap<Section> sections;
    private ObjectGraph graph;
    private long cachedKey = Long.MIN_VALUE;
    private Section cached;
    private long tick;
    private final Runnable dirty;
    private MemoryArchive archive;
    private final Map<String, long[]> pendingArchived = new HashMap<>();
    /** dimension -> "category:kind" -> positions of notable blocks, in RAM and archive. */
    private final Map<String, Map<String, LongOpenHashSet>> notable = new HashMap<>();

    /** One remembered notable block. */
    public record Remembered(String category, String kind, BlockPos pos) {}

    /**
     * Connects the disk archive. A memory that was not loaded from the save starts clean, so an
     * archive left by an earlier bot with the same name is wiped.
     */
    public void attach(java.nio.file.Path root, HolderLookup.Provider registries, boolean fresh) {
        if (archive != null) return;
        archive = new MemoryArchive(root, registries);
        if (fresh) archive.wipe();
        else pendingArchived.forEach(archive::restoreKeys);
        pendingArchived.clear();
    }

    public boolean archiving() { return archive != null; }
    public int archivedCount() { return archive == null ? 0 : archive.size(); }

    private static String notableKey(Cell cell) {
        String category = cell.semantic().category();
        return switch (category) {
            case "container", "station", "machine", "ore" -> category + ":" + cell.semantic().kind();
            default -> null;
        };
    }

    private void index(String dimension, long pos, Cell old, Cell cell) {
        String before = old == null ? null : notableKey(old), after = cell == null ? null : notableKey(cell);
        if (Objects.equals(before, after)) return;
        var kinds = notable.computeIfAbsent(dimension, ignored -> new HashMap<>());
        if (before != null && kinds.containsKey(before)) kinds.get(before).remove(pos);
        if (after != null) kinds.computeIfAbsent(after, ignored -> new LongOpenHashSet()).add(pos);
    }

    /** Nearest remembered notable blocks whose "category:kind" matches, anywhere in this dimension. */
    public List<Remembered> remembered(String dimension, BlockPos from, java.util.function.Predicate<String> kind, int limit) {
        List<Remembered> result = new ArrayList<>();
        notable.getOrDefault(dimension, Map.of()).forEach((key, positions) -> {
            if (!kind.test(key)) return;
            String[] parts = key.split(":", 2);
            positions.forEach((long pos) -> result.add(new Remembered(parts[0], parts[1], BlockPos.of(pos))));
        });
        result.sort(Comparator.comparingDouble(r -> r.pos().distSqr(from)));
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }

    /** Brings archived sections around {@code center} back into RAM before they are needed. */
    public void warm(String dimension, BlockPos center, int radiusSections) {
        if (archive == null) return;
        int cx = center.getX() >> 4, cy = center.getY() >> 4, cz = center.getZ() >> 4;
        for (int x = -radiusSections; x <= radiusSections; x++) for (int y = -radiusSections; y <= radiusSections; y++)
            for (int z = -radiusSections; z <= radiusSections; z++) {
                long key = SectionPos.asLong(cx + x, cy + y, cz + z);
                if (archive.has(dimension, key)) thaw(dimension, key);
            }
    }

    /**
     * RAM lookup only. Reads never thaw: callers iterate the working set while asking about
     * neighbours, and a thaw (with its eviction) would change the map under them. Archived sections
     * come back through {@link #warm} around the bot and through writes.
     */
    private Section lookup(String dimension, long key) {
        var values = dimensions.get(dimension);
        return values == null ? null : values.get(key);
    }

    private Section thaw(String dimension, long key) {
        CompoundTag tag = archive.take(dimension, key);
        if (tag == null) return null;
        Section section = read(dimension, key, tag, archive.registries());
        dimensions.computeIfAbsent(dimension, ignored -> new Long2ObjectLinkedOpenHashMap<>()).putAndMoveToLast(key, section);
        if (sectionCount() > MAX_SECTIONS) evict();
        return section;
    }

    public ObservedMap(Runnable dirty) { this.dirty = dirty; }

    public void begin(String dimension, long time) {
        if (!active.equals(dimension) || sections == null) {
            active = dimension;
            sections = dimensions.computeIfAbsent(dimension, ignored -> new Long2ObjectLinkedOpenHashMap<>());
            graph = graphs.computeIfAbsent(dimension, ignored -> new ObjectGraph());
            cachedKey = Long.MIN_VALUE;
        }
        tick = time;
    }

    private static int index(int x, int y, int z) { return (y & 15) << 8 | (z & 15) << 4 | (x & 15); }
    private Section section(int x, int y, int z) {
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        if (key == cachedKey) return cached;
        Section section = sections.getAndMoveToLast(key);
        if (section == null && archive != null && archive.has(active, key)) section = thaw(active, key);
        if (section == null) {
            section = new Section();
            sections.put(key, section);
            if (sectionCount() > MAX_SECTIONS) evict();
        }
        cachedKey = key;
        return cached = section;
    }

    private void evict() {
        // Access order retains the sections around the bot's recent eye positions and rays.
        for (var entry : dimensions.entrySet()) {
            var values = entry.getValue();
            if (values.isEmpty()) continue;
            long key = values.firstLongKey();
            Section removed = values.removeFirst();
            if (archive != null) archive.put(entry.getKey(), key, write(entry.getKey(), key, removed));
            else for (var cell : removed.cells.int2ObjectEntrySet()) index(entry.getKey(), position(key, cell.getIntKey()).asLong(), cell.getValue(), null);
            ObjectGraph oldGraph = graphs.get(entry.getKey());
            if (oldGraph != null) for (int index : removed.cells.keySet()) oldGraph.remove(position(key, index).asLong());
            if (entry.getKey().equals(active) && key == cachedKey) cachedKey = Long.MIN_VALUE;
            return;
        }
    }

    public void empty(int x, int y, int z) {
        Section section = section(x, y, z);
        int index = index(x, y, z);
        long mask = 1L << (index & 63);
        if ((section.empty[index >> 6] & mask) != 0) return;
        section.empty[index >> 6] |= mask;
        Cell removed = section.cells.remove(index);
        if (removed != null) {
            graph.remove(BlockPos.asLong(x, y, z));
            index(active, BlockPos.asLong(x, y, z), removed, null);
        }
        dirty.run();
    }

    public void seen(int x, int y, int z, int stateId, Semantics.Kind kind, boolean side) {
        Section section = section(x, y, z);
        int index = index(x, y, z);
        Cell old = section.cells.get(index);
        if (old != null && old.stateId() == stateId && old.tick() == tick && old.epoch() == Semantics.epoch() && old.semantic().equals(kind)
                && (old.side() || !side)) return;
        // Preserve evidence that a face was seen from the side during this observation interval.
        Cell cell = new Cell(stateId, tick, kind, side || old != null && old.stateId() == stateId && old.side(), Semantics.epoch());
        section.cells.put(index, cell);
        section.empty[index >> 6] &= ~(1L << (index & 63));
        graph.put(BlockPos.asLong(x, y, z), old, cell);
        index(active, BlockPos.asLong(x, y, z), old, cell);
        dirty.run();
    }

    Cell current(int x, int y, int z) {
        long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        Section section = key == cachedKey ? cached : sections.get(key);
        return section == null ? null : section.cells.get(index(x, y, z));
    }

    public Cell cell(String dimension, BlockPos pos) {
        Section section = lookup(dimension, SectionPos.asLong(pos));
        return section == null ? null : section.cells.get(index(pos.getX(), pos.getY(), pos.getZ()));
    }

    public boolean known(String dimension, BlockPos pos) {
        Section section = lookup(dimension, SectionPos.asLong(pos));
        int i = index(pos.getX(), pos.getY(), pos.getZ());
        return section != null && (section.cells.containsKey(i) || (section.empty[i >> 6] & (1L << (i & 63))) != 0);
    }

    public boolean empty(String dimension, BlockPos pos) {
        Section section = lookup(dimension, SectionPos.asLong(pos));
        int i = index(pos.getX(), pos.getY(), pos.getZ());
        return section != null && (section.empty[i >> 6] & (1L << (i & 63))) != 0;
    }

    /** Visits the RAM working set only (recent memory); the archive is reached through lookups, warm and the index. */
    public void forEach(String dimension, BiConsumer<BlockPos, Cell> consumer) {
        var values = dimensions.get(dimension);
        if (values != null) values.forEach((key, section) -> section.cells.int2ObjectEntrySet()
            .forEach(entry -> consumer.accept(position(key, entry.getIntKey()), entry.getValue())));
    }

    /**
     * Like {@link #forEach} but only visits sections that can hold cells within {@code radius} of
     * {@code center}. Callers still filter by exact distance. Does not change the eviction order.
     */
    public void forEachNear(String dimension, BlockPos center, int radius, BiConsumer<BlockPos, Cell> consumer) {
        var values = dimensions.get(dimension);
        if (values == null) return;
        int minX = (center.getX() - radius) >> 4, maxX = (center.getX() + radius) >> 4;
        int minY = (center.getY() - radius) >> 4, maxY = (center.getY() + radius) >> 4;
        int minZ = (center.getZ() - radius) >> 4, maxZ = (center.getZ() + radius) >> 4;
        long span = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (span >= values.size()) {
            forEach(dimension, consumer);
            return;
        }
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            long key = SectionPos.asLong(x, y, z);
            Section section = values.get(key);
            if (section != null) section.cells.int2ObjectEntrySet()
                .forEach(entry -> consumer.accept(position(key, entry.getIntKey()), entry.getValue()));
        }
    }

    private static BlockPos position(long key, int index) {
        return new BlockPos((SectionPos.x(key) << 4) + (index & 15),
            (SectionPos.y(key) << 4) + (index >> 8), (SectionPos.z(key) << 4) + ((index >> 4) & 15));
    }

    /** Debug/test snapshot including free space; timestamps intentionally excluded. */
    public Map<Long, Integer> evidence(String dimension) {
        Map<Long, Integer> result = new HashMap<>();
        var values = dimensions.get(dimension);
        if (values != null) values.forEach((key, section) -> {
            for (int i = 0; i < 4096; i++) {
                Cell cell = section.cells.get(i);
                if (cell != null) result.put(position(key, i).asLong(), cell.stateId());
                else if ((section.empty[i >> 6] & (1L << (i & 63))) != 0) result.put(position(key, i).asLong(), 0);
            }
        });
        return Map.copyOf(result);
    }

    /** Conservative cave evidence: a dark side face, air, a seen rock roof, and an unknown horizontal edge. */
    public boolean caveMouth(String dimension, BlockPos pos) {
        Cell cell = cell(dimension, pos);
        if (cell == null || !cell.side() || !cell.semantic().category().equals("dark")) return false;
        boolean air = empty(dimension, pos.north()) || empty(dimension, pos.south())
            || empty(dimension, pos.east()) || empty(dimension, pos.west());
        boolean roof = false;
        for (int dy = 1; dy <= 3; dy++) {
            Cell above = cell(dimension, pos.above(dy));
            roof |= above != null && Set.of("stone", "dirt").contains(above.semantic().category());
        }
        boolean edge = !known(dimension, pos.north()) || !known(dimension, pos.south())
            || !known(dimension, pos.east()) || !known(dimension, pos.west());
        return air && roof && edge;
    }

    public ObjectGraph graph(String dimension) { return graphs.computeIfAbsent(dimension, ignored -> new ObjectGraph()); }
    public int sectionCount() { return dimensions.values().stream().mapToInt(Map::size).sum(); }
    public int cellCount() { return dimensions.values().stream().flatMap(m -> m.values().stream()).mapToInt(s -> s.cells.size()).sum(); }
    public int emptyCount() { return dimensions.values().stream().flatMap(m -> m.values().stream())
        .mapToInt(s -> Arrays.stream(s.empty).mapToInt(Long::bitCount).sum()).sum(); }
    public int estimatedBytes() { return sectionCount() * 640 + cellCount() * 64; }

    /** One section with a stable block-state palette, never runtime registry ids on disk. */
    private static CompoundTag write(String dimension, long key, Section section) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimension);
        tag.putLong("section", key);
        tag.putLongArray("empty", section.empty);
        ListTag palette = new ListTag();
        record PaletteKey(int state, Semantics.Kind semantic) {}
        Map<PaletteKey, Integer> ids = new HashMap<>();
        int[] cells = new int[section.cells.size() * 2];
        long[] ticks = new long[section.cells.size()];
        int offset = 0;
        for (var entry : section.cells.int2ObjectEntrySet()) {
            Cell cell = entry.getValue();
            int paletteId = ids.computeIfAbsent(new PaletteKey(cell.stateId(), cell.semantic()), value -> {
                CompoundTag item = new CompoundTag();
                if (value.state() >= 0) item.put("state", NbtUtils.writeBlockState(Block.stateById(value.state())));
                item.putString("class", value.semantic().category());
                item.putString("kind", value.semantic().kind());
                item.putBoolean("container", value.semantic().container());
                palette.add(item);
                return palette.size() - 1;
            });
            cells[offset * 2] = entry.getIntKey() | (cell.side() ? 4096 : 0);
            cells[offset * 2 + 1] = paletteId;
            ticks[offset++] = cell.tick();
        }
        tag.put("palette", palette);
        tag.putIntArray("cells", cells);
        tag.putLongArray("ticks", ticks);
        return tag;
    }

    /** Rebuilds a section and puts its cells into the object graph and the notable index. */
    private Section read(String dimension, long key, CompoundTag tag, HolderLookup.Provider registries) {
        Section section = new Section();
        long[] empty = tag.getLongArray("empty");
        System.arraycopy(empty, 0, section.empty, 0, Math.min(empty.length, 64));
        ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
        int[] cells = tag.getIntArray("cells");
        long[] ticks = tag.getLongArray("ticks");
        ObjectGraph graph = graph(dimension);
        for (int i = 0; i < ticks.length && i * 2 + 1 < cells.length; i++) {
            int packed = cells[i * 2], index = packed & 4095, paletteId = cells[i * 2 + 1];
            if (packed < 0 || packed > 8191 || paletteId < 0 || paletteId >= palette.size()) continue;
            CompoundTag item = palette.getCompound(paletteId);
            int state = !item.contains("state") ? DARK_STATE : Block.getId(NbtUtils.readBlockState(
                registries.lookupOrThrow(Registries.BLOCK), item.getCompound("state")));
            Cell cell = new Cell(state, ticks[i], new Semantics.Kind(item.getString("class"),
                item.getString("kind"), item.getBoolean("container")), (packed & 4096) != 0, Semantics.epoch() - 1);
            section.cells.put(index, cell);
            long pos = position(key, index).asLong();
            graph.put(pos, null, cell);
            index(dimension, pos, null, cell);
        }
        return section;
    }

    public void save(CompoundTag root) {
        ListTag list = new ListTag();
        dimensions.forEach((dimension, values) -> values.forEach((key, section) -> list.add(write(dimension, key, section))));
        root.put("sections", list);
        if (archive != null) {
            archive.flush();
            CompoundTag archived = new CompoundTag();
            archive.keys().forEach((dimension, keys) -> archived.putLongArray(dimension, keys.toLongArray()));
            root.put("archived", archived);
        }
        CompoundTag index = new CompoundTag();
        notable.forEach((dimension, kinds) -> {
            CompoundTag perDimension = new CompoundTag();
            kinds.forEach((kind, positions) -> { if (!positions.isEmpty()) perDimension.putLongArray(kind, positions.toLongArray()); });
            index.put(dimension, perDimension);
        });
        root.put("notable", index);
        ListTag refs = new ListTag();
        graphs.forEach((dimension, value) -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("dimension", dimension);
            value.saveRefs(tag);
            refs.add(tag);
        });
        root.put("object_refs", refs);
    }

    public void load(CompoundTag root, HolderLookup.Provider registries) {
        CompoundTag index = root.getCompound("notable");
        for (String dimension : index.getAllKeys()) {
            CompoundTag perDimension = index.getCompound(dimension);
            var kinds = notable.computeIfAbsent(dimension, ignored -> new HashMap<>());
            for (String kind : perDimension.getAllKeys())
                kinds.computeIfAbsent(kind, ignored -> new LongOpenHashSet()).addAll(LongArrayList.wrap(perDimension.getLongArray(kind)));
        }
        CompoundTag archived = root.getCompound("archived");
        for (String dimension : archived.getAllKeys()) pendingArchived.put(dimension, archived.getLongArray(dimension));
        for (Tag raw : root.getList("sections", Tag.TAG_COMPOUND)) {
            if (sectionCount() >= MAX_SECTIONS) break;
            CompoundTag tag = (CompoundTag) raw;
            String dimension = tag.getString("dimension");
            long key = tag.getLong("section");
            dimensions.computeIfAbsent(dimension, ignored -> new Long2ObjectLinkedOpenHashMap<>()).put(key, read(dimension, key, tag, registries));
        }
        for (Tag raw : root.getList("object_refs", Tag.TAG_COMPOUND)) {
            CompoundTag tag = (CompoundTag) raw;
            graph(tag.getString("dimension")).loadRefs(tag);
        }
    }
}
