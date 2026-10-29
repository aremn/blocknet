// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import dev.blocknet.Blocknet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Unlimited eye memory on disk: sections that left the bot's RAM working set, grouped in region
 * files of 32 x 32 chunks (all heights), like Minecraft's own regions. Only regions that changed
 * are rewritten. A small RAM cache keeps recently used regions; the set of archived section keys
 * stays in RAM (8 bytes each) so a lookup never touches the disk for sections that were never seen.
 */
final class MemoryArchive {
    private static final int CACHED_REGIONS = 16;

    private static final class Region {
        final Long2ObjectOpenHashMap<CompoundTag> sections = new Long2ObjectOpenHashMap<>();
        boolean dirty;
    }

    private final Path root;
    private final HolderLookup.Provider registries;
    private final Map<String, LongOpenHashSet> stored = new HashMap<>();
    private final LinkedHashMap<String, Region> regions = new LinkedHashMap<>(16, 0.75f, true);

    MemoryArchive(Path root, HolderLookup.Provider registries) {
        this.root = root;
        this.registries = registries;
    }

    HolderLookup.Provider registries() { return registries; }

    /** Forget everything on disk: a fresh memory must not inherit an old bot's archive. */
    void wipe() {
        stored.clear();
        regions.clear();
        try {
            if (Files.exists(root)) try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        } catch (IOException exception) {
            Blocknet.LOGGER.warn("Cannot clear bot memory archive {}", root, exception);
        }
    }

    boolean has(String dimension, long section) {
        LongOpenHashSet keys = stored.get(dimension);
        return keys != null && keys.contains(section);
    }

    int size() { return stored.values().stream().mapToInt(LongOpenHashSet::size).sum(); }

    Map<String, LongOpenHashSet> keys() { return stored; }

    void restoreKeys(String dimension, long[] keys) {
        LongOpenHashSet set = stored.computeIfAbsent(dimension, ignored -> new LongOpenHashSet());
        for (long key : keys) set.add(key);
    }

    void put(String dimension, long section, CompoundTag tag) {
        Region region = region(dimension, section);
        region.sections.put(section, tag);
        region.dirty = true;
        stored.computeIfAbsent(dimension, ignored -> new LongOpenHashSet()).add(section);
    }

    /** Takes a section out of the archive (it becomes RAM memory again); null when absent. */
    @Nullable
    CompoundTag take(String dimension, long section) {
        if (!has(dimension, section)) return null;
        Region region = region(dimension, section);
        CompoundTag tag = region.sections.remove(section);
        region.dirty = true;
        stored.get(dimension).remove(section);
        return tag;
    }

    /** Writes every changed region; called when the world saves. */
    void flush() {
        regions.forEach(this::write);
    }

    private static String regionKey(String dimension, long section) {
        return dimension + "|" + (SectionPos.x(section) >> 5) + "|" + (SectionPos.z(section) >> 5);
    }

    private Path file(String key) {
        String[] parts = key.split("\\|");
        return root.resolve(parts[0].replaceAll("[^a-zA-Z0-9_.-]", "_")).resolve("r." + parts[1] + "." + parts[2] + ".dat");
    }

    private Region region(String dimension, long section) {
        String key = regionKey(dimension, section);
        Region region = regions.get(key);
        if (region != null) return region;
        region = new Region();
        Path file = file(key);
        if (Files.exists(file)) {
            try {
                CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                for (Tag raw : tag.getList("sections", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) raw;
                    region.sections.put(entry.getLong("section"), entry);
                }
            } catch (IOException exception) {
                Blocknet.LOGGER.warn("Cannot read bot memory region {}", file, exception);
            }
        }
        regions.put(key, region);
        while (regions.size() > CACHED_REGIONS) {
            var eldest = regions.entrySet().iterator().next();
            write(eldest.getKey(), eldest.getValue());
            regions.remove(eldest.getKey());
        }
        return region;
    }

    private void write(String key, Region region) {
        if (!region.dirty) return;
        Path file = file(key);
        try {
            if (region.sections.isEmpty()) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent());
                ListTag list = new ListTag();
                region.sections.values().forEach(list::add);
                CompoundTag tag = new CompoundTag();
                tag.put("sections", list);
                // Write next to the file and move it in place, so a crash never leaves half a region.
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                NbtIo.writeCompressed(tag, temporary);
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            region.dirty = false;
        } catch (IOException exception) {
            Blocknet.LOGGER.warn("Cannot write bot memory region {}", file, exception);
        }
    }
}
