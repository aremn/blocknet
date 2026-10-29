// SPDX-License-Identifier: MIT
package dev.blocknet.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.blocknet.Blocknet;
import dev.blocknet.state.ToolFacts;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.*;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Registry knowledge, never coordinates of world blocks. Replaced atomically after reload. */
public final class Knowledge {
    public record Range(String dimension, Integer min, Integer max, int peak, double frequency, Set<String> biomes) {
        String text() {
            return dimension + (min == null ? " unknown range" : " y " + min + ".." + max + " peak " + peak)
                + String.format(Locale.ROOT, " (~%.2f/chunk)", frequency)
                + (biomes.isEmpty() ? "" : " (" + biomes.stream().sorted().limit(3).collect(Collectors.joining(",")) + (biomes.size() > 3 ? ",+" + (biomes.size()-3) : "") + ")");
        }
    }
    public record Ore(Item drop, Set<Block> blocks, List<Range> ranges) {}
    private static final Map<MinecraftServer, Knowledge> CACHE = new WeakHashMap<>();
    private final Map<Block, Set<Item>> drops = new HashMap<>();
    private final Map<Item, Ore> ores = new HashMap<>();
    private final Map<Item, Set<String>> otherSources = new HashMap<>();
    private final Map<ResourceLocation, Set<Item>> lootTables = new HashMap<>();

    public static void started(ServerStartedEvent event) { rebuild(event.getServer()); }
    public static void reload(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) rebuild(event.getPlayerList().getServer());
    }
    private static void rebuild(MinecraftServer server) {
        long start = System.nanoTime();
        CACHE.put(server, new Knowledge(server));
        Blocknet.LOGGER.info("KNOWLEDGE built {} ore groups in {} ms", CACHE.get(server).ores.size(), (System.nanoTime()-start)/1_000_000);
    }
    public static Knowledge get(ServerLevel level) {
        return CACHE.computeIfAbsent(level.getServer(), Knowledge::new);
    }

    private Knowledge(MinecraftServer server) {
        var access = server.reloadableRegistries().get();
        var ops = access.createSerializationContext(JsonOps.INSTANCE);
        Map<ResourceLocation, Set<Item>> tables = new HashMap<>();
        Map<ResourceLocation, Set<ResourceLocation>> references = new HashMap<>();
        access.registryOrThrow(Registries.LOOT_TABLE).entrySet().forEach(entry -> {
            Set<Item> items = new LinkedHashSet<>();
            Set<ResourceLocation> refs = new LinkedHashSet<>();
            LootTable.DIRECT_CODEC.encodeStart(ops, entry.getValue()).result().ifPresent(json -> lootItems(json, items, refs));
            tables.put(entry.getKey().location(), Set.copyOf(items));
            references.put(entry.getKey().location(), refs);
        });
        // A table may include another (a white sheep's table holds its wool and refers to the
        // plain sheep table for mutton): what it can drop is the union of both.
        Map<ResourceLocation, Set<Item>> resolved = new HashMap<>();
        tables.keySet().forEach(id -> resolved.put(id, Set.copyOf(resolve(id, tables, references, new HashSet<>()))));
        tables.putAll(resolved);
        tables.forEach((id, items) -> {
            if (id.getPath().startsWith("entities/")) for (Item item : items)
                otherSources.computeIfAbsent(item, k -> new TreeSet<>()).add("mob drops: " + id.getPath().substring(9));
        });
        for (Block block : BuiltInRegistries.BLOCK)
            drops.put(block, tables.getOrDefault(block.getLootTable().location(), Set.of()));
        lootTables.putAll(tables);

        Map<Item, Set<Block>> blocks = new HashMap<>();
        Map<Item, List<Range>> ranges = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            var generator = level.getChunkSource().getGenerator();
            var biomes = generator.getBiomeSource().possibleBiomes();
            var context = new WorldGenerationContext(generator, level);
            Map<PlacedFeature, Set<String>> features = new LinkedHashMap<>();
            for (var biome : biomes) for (var step : biome.value().getGenerationSettings().features()) for (var placed : step)
                features.computeIfAbsent(placed.value(), k -> new TreeSet<>()).add(biome.unwrapKey().map(k -> shortId(k.location())).orElse("unknown"));
            features.forEach((placed, present) -> {
                Set<Item> featureDrops = new HashSet<>();
                placed.getFeatures().forEach(feature -> {
                    if (!(feature.config() instanceof OreConfiguration ore)) return;
                    for (var target : ore.targetStates) {
                        Block block = target.state.getBlock();
                        if (!dev.blocknet.vision.Semantics.state(target.state).category().equals("ore")) continue;
                        Set<Item> candidates = drops.getOrDefault(block, Set.of());
                        // Prefer the non-silk item, grouping stone/deepslate variants by shared drop.
                        List<Item> normal = candidates.stream().filter(i -> !(i instanceof BlockItem)).toList();
                        if (normal.isEmpty()) normal = candidates.stream().filter(i -> i != block.asItem()).toList();
                        if (normal.isEmpty() && block.asItem() != Items.AIR) normal = List.of(block.asItem());
                        for (Item drop : normal) {
                            blocks.computeIfAbsent(drop, k -> new LinkedHashSet<>()).add(block);
                            featureDrops.add(drop);
                        }
                    }
                });
                if (featureDrops.isEmpty()) return;
                Integer min = null, max = null;
                int peak = 0;
                double frequency = 1;
                for (var modifier : placed.placement()) {
                    var json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).result().orElse(null);
                    if (json == null || !json.isJsonObject()) continue;
                    JsonObject data = json.getAsJsonObject();
                    if (modifier instanceof HeightRangePlacement) {
                        var height = data.getAsJsonObject("height");
                        String type = height.get("type").getAsString();
                        if (type.equals("minecraft:uniform") || type.equals("minecraft:trapezoid")) {
                            min = VerticalAnchor.CODEC.parse(JsonOps.INSTANCE, height.get("min_inclusive")).getOrThrow().resolveY(context);
                            max = VerticalAnchor.CODEC.parse(JsonOps.INSTANCE, height.get("max_inclusive")).getOrThrow().resolveY(context);
                            peak = Math.floorDiv(min + max, 2);
                        }
                    } else if (modifier instanceof CountPlacement) {
                        var count = net.minecraft.util.valueproviders.IntProvider.CODEC.parse(JsonOps.INSTANCE, data.get("count")).result();
                        if (count.isPresent()) frequency *= (count.get().getMinValue() + count.get().getMaxValue()) / 2.0;
                    } else if (modifier instanceof RarityFilter) frequency /= data.get("chance").getAsDouble();
                }
                Range range = new Range(shortId(level.dimension().location()), min, max, peak, frequency,
                    present.size() == biomes.size() ? Set.of() : Set.copyOf(present));
                for (Item drop : featureDrops) ranges.computeIfAbsent(drop, k -> new ArrayList<>()).add(range);
            });
        }
        blocks.forEach((item, values) -> {
            Map<String, Range> combined = new LinkedHashMap<>();
            for (Range r : ranges.getOrDefault(item, List.of())) {
                String key = r.dimension + ":" + r.min + ":" + r.max + ":" + r.peak + ":" + new TreeSet<>(r.biomes);
                combined.merge(key, r, (a, b) -> new Range(a.dimension, a.min, a.max, a.peak, a.frequency+b.frequency, a.biomes));
            }
            ores.put(item, new Ore(item, Set.copyOf(values), List.copyOf(combined.values())));
        });
    }

    private static void lootItems(JsonElement json, Set<Item> result, Set<ResourceLocation> refs) {
        if (json.isJsonArray()) { json.getAsJsonArray().forEach(e -> lootItems(e, result, refs)); return; }
        if (!json.isJsonObject()) return;
        var object = json.getAsJsonObject();
        String type = object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : "";
        if (type.equals("minecraft:item") && object.has("name")) {
            var id = ResourceLocation.tryParse(object.get("name").getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) result.add(BuiltInRegistries.ITEM.get(id));
        }
        if (type.equals("minecraft:loot_table") && object.has("value") && object.get("value").isJsonPrimitive()) {
            var id = ResourceLocation.tryParse(object.get("value").getAsString());
            if (id != null) refs.add(id);
        }
        object.entrySet().forEach(e -> lootItems(e.getValue(), result, refs));
    }

    private static Set<Item> resolve(ResourceLocation id, Map<ResourceLocation, Set<Item>> tables,
            Map<ResourceLocation, Set<ResourceLocation>> references, Set<ResourceLocation> seen) {
        Set<Item> items = new LinkedHashSet<>(tables.getOrDefault(id, Set.of()));
        if (!seen.add(id)) return items;
        for (var ref : references.getOrDefault(id, Set.of())) items.addAll(resolve(ref, tables, references, seen));
        return items;
    }
    public static String shortId(ResourceLocation id) { return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString(); }
    public Ore ore(Item item) {
        if (ores.containsKey(item)) return ores.get(item);
        if (item instanceof BlockItem block) return ores.values().stream().filter(o -> o.blocks.contains(block.getBlock())).findFirst().orElse(null);
        return null;
    }
    /** Every item some ore drops in this world's generation (coal, raw iron, diamond, ...). */
    public Set<Item> oreItems() { return Collections.unmodifiableSet(ores.keySet()); }
    public Ore ore(Block block) { return ores.values().stream().filter(o -> o.blocks.contains(block)).findFirst().orElse(null); }
    public String text(Block block) {
        Ore ore = ore(block);
        if (ore != null) return text(ore.drop);
        return shortId(BuiltInRegistries.BLOCK.getKey(block)) + ": needs "
            + ToolFacts.minimumTool(block.defaultBlockState()).map(i -> shortId(BuiltInRegistries.ITEM.getKey(i)))
                .orElse(block.defaultBlockState().requiresCorrectToolForDrops() ? "unknown tool" : "hand")
            + "; drops " + drops(block).stream().map(i -> shortId(BuiltInRegistries.ITEM.getKey(i))).sorted().collect(Collectors.joining(", "));
    }
    public Set<Item> drops(Block block) { return drops.getOrDefault(block, Set.of()); }
    /** What a creature can drop, from its own current loot table (a sheep's depends on its colour). */
    public Set<Item> drops(net.minecraft.world.entity.LivingEntity creature) {
        return lootTables.getOrDefault(creature.getLootTable().location(), Set.of());
    }
    public String unsupported(Item item) { return "unsupported: " + String.join(", ", otherSources.getOrDefault(item, Set.of("trading or another unsupported source"))); }
    public String text(Item item) {
        Ore ore = ore(item);
        Block block = item instanceof BlockItem b ? b.getBlock() : null;
        if (ore != null) {
            block = ore.blocks.stream().sorted(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString())).findFirst().orElseThrow();
            String names = ore.blocks.stream().map(b -> shortId(BuiltInRegistries.BLOCK.getKey(b))).sorted().collect(Collectors.joining(" + "));
            String ranges = ore.ranges.stream().sorted(Comparator.comparingDouble(Range::frequency).reversed()).map(Range::text).distinct().collect(Collectors.joining("; "));
            return names + ": " + ranges + "; needs " + ToolFacts.minimumTool(block.defaultBlockState()).map(i -> shortId(BuiltInRegistries.ITEM.getKey(i))).orElse(block.defaultBlockState().requiresCorrectToolForDrops() ? "unknown tool" : "hand")
                + "; drops " + shortId(BuiltInRegistries.ITEM.getKey(ore.drop));
        }
        if (block != null) return shortId(BuiltInRegistries.BLOCK.getKey(block)) + ": needs "
            + ToolFacts.minimumTool(block.defaultBlockState()).map(i -> shortId(BuiltInRegistries.ITEM.getKey(i))).orElse(block.defaultBlockState().requiresCorrectToolForDrops() ? "unknown tool" : "hand")
            + "; drops " + drops(block).stream().map(i -> shortId(BuiltInRegistries.ITEM.getKey(i))).sorted().collect(Collectors.joining(", "));
        return shortId(BuiltInRegistries.ITEM.getKey(item)) + ": " + unsupported(item);
    }
}
