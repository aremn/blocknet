// SPDX-License-Identifier: MIT
package dev.blocknet.map;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.vision.Frontiers;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Full perception (ADR-0012) on demand: where the nearest blocks of a kind are, and which
 * containers or machines around hold an item, read from loaded chunks without walking or opening
 * anything. Read-only: it never creates, moves or changes an item.
 */
public final class WorldFind {
    private WorldFind() {}

    public static String find(BotPlayer bot, String what, int radius) {
        if (!bot.fullPerception()) return "unsupported: locate needs full perception (\"perception\": \"full\" in the bridge config)";
        String query = what.trim().toLowerCase(Locale.ROOT);
        int chunks = Math.clamp((radius + 15) / 16, 1, 12);
        Predicate<BlockState> blocks = blockPredicate(query);
        List<String> lines = new ArrayList<>();
        if (blocks != null) {
            var found = BlockFinder.nearest(bot.serverLevel(), bot.blockPosition(), blocks, chunks, 8);
            lines.add(found.isEmpty() ? "no " + query + " blocks within " + chunks * 16 + " blocks"
                : query + " blocks: " + String.join("; ", found.stream().map(pos -> place(bot, pos)).toList()));
        }
        Predicate<ItemStack> items = itemPredicate(query);
        if (items != null) lines.add(containers(bot, items, chunks, query));
        return lines.isEmpty() ? "unknown block, item or tag: " + what : String.join("\n", lines);
    }

    private static String place(BotPlayer bot, BlockPos pos) {
        Vec3 delta = Vec3.atCenterOf(pos).subtract(bot.position());
        return pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (" + Frontiers.direction(delta) + " "
            + Math.round(delta.length()) + "m)";
    }

    private static Predicate<BlockState> blockPredicate(String query) {
        if (query.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(query.substring(1));
            if (id == null) return null;
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
            return BuiltInRegistries.BLOCK.getTag(tag).isPresent() ? state -> state.is(tag) : null;
        }
        ResourceLocation id = ResourceLocation.tryParse(query.contains(":") ? query : "minecraft:" + query);
        if (id == null) return null;
        if (BuiltInRegistries.BLOCK.containsKey(id)) {
            Block block = BuiltInRegistries.BLOCK.get(id);
            return state -> state.is(block);
        }
        return null;
    }

    private static Predicate<ItemStack> itemPredicate(String query) {
        if (query.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(query.substring(1));
            if (id == null) return null;
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            return BuiltInRegistries.ITEM.getTag(tag).isPresent() ? stack -> stack.is(tag) : null;
        }
        ResourceLocation id = ResourceLocation.tryParse(query.contains(":") ? query : "minecraft:" + query);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return stack -> stack.is(item);
    }

    /** Containers and machines with an item inventory that hold the item, nearest first. */
    private static String containers(BotPlayer bot, Predicate<ItemStack> items, int chunks, String query) {
        var level = bot.serverLevel();
        int cx = bot.getBlockX() >> 4, cz = bot.getBlockZ() >> 4;
        record Holder(BlockPos pos, String block, int count) {}
        List<Holder> holders = new ArrayList<>();
        for (int dx = -chunks; dx <= chunks; dx++) for (int dz = -chunks; dz <= chunks; dz++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
            if (chunk == null) continue;
            for (BlockPos pos : List.copyOf(chunk.getBlockEntities().keySet())) {
                var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                if (handler == null) continue;
                int count = 0;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    if (!stack.isEmpty() && items.test(stack)) count += stack.getCount();
                }
                if (count > 0) holders.add(new Holder(pos, BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath(), count));
            }
        }
        holders.sort(Comparator.comparingDouble(h -> h.pos().distSqr(bot.blockPosition())));
        if (holders.isEmpty()) return "no container within " + chunks * 16 + " blocks holds " + query;
        return query + " stored in: " + String.join("; ", holders.stream().limit(8)
            .map(h -> h.block() + " with " + h.count() + " at " + place(bot, h.pos())).toList());
    }
}
