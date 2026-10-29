package dev.blocknet.memory;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.control.NetworkMenus;
import dev.blocknet.crafting.ItemKey;
import dev.blocknet.crafting.RememberedSource;
import dev.blocknet.state.ItemDescriber;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Last menu observations, never live storage queries. One overworld SavedData per bot UUID. An open
 * storage-network terminal is remembered by its stored network items ({@code network} entries).
 */
public final class ContainerMemory extends SavedData {
    public record Location(String dimension, long position) {}
    public record Entry(Location location, String blockId, Map<ItemKey, Long> contents, long seenTick,
                        List<Long> positions, boolean network) {
        public Entry {
            contents = java.util.Collections.unmodifiableMap(new TreeMap<>(contents));
            positions = List.copyOf(positions);
        }
    }

    private final Map<Location, Entry> entries = new LinkedHashMap<>();

    public static ContainerMemory forBot(BotPlayer bot) {
        return bot.getServer().overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(ContainerMemory::new, ContainerMemory::load),
            "blocknet_containers_" + bot.getUUID());
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** All active, non-player menu slots, including output slots the player can see. */
    public static List<Slot> containerSlots(BotPlayer bot) {
        if (bot.containerMenu == bot.inventoryMenu) {
            return List.of();
        }
        return bot.containerMenu.slots.stream()
            .filter(slot -> slot.container != bot.getInventory() && slot.isActive()).toList();
    }

    public void observe(BotPlayer bot, BlockPos pos) {
        var slots = containerSlots(bot);
        var network = bot.containerMenu == bot.inventoryMenu ? null : NetworkMenus.read(bot, bot.containerMenu);
        if (network != null && network.status() == NetworkMenus.Status.PENDING) return;
        if (network != null && network.status() == NetworkMenus.Status.DISCONNECTED) {
            // Unknown is not empty: an unpowered or cut-off network no longer counts as stock.
            forget(bot, pos);
            return;
        }
        if (network == null && slots.isEmpty()) {
            return;
        }
        var state = bot.level().getBlockState(pos);
        List<Long> positions = new ArrayList<>();
        positions.add(pos.asLong());
        // The open menu exposes both halves; normalize either opening to the same identity.
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            positions.add(pos.relative(ChestBlock.getConnectedDirection(state)).asLong());
        }
        positions.sort(Long::compare);
        String dimension = bot.level().dimension().location().toString();
        entries.values().removeIf(entry -> entry.location().dimension().equals(dimension)
            && entry.positions().stream().anyMatch(positions::contains));
        Map<ItemKey, Long> contents = new TreeMap<>();
        // Only real stored amounts; craftable-only keys and the terminal's own grid slots are not stock.
        if (network != null) for (var entry : network.entries()) {
            if (!entry.item().isEmpty() && entry.amount() > 0) contents.merge(new ItemKey(entry.item()), entry.amount(), Long::sum);
        } else for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                contents.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum);
            }
        }
        Location location = new Location(dimension, positions.getFirst());
        entries.put(location, new Entry(location, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
            contents, bot.getServer().overworld().getGameTime(), positions, network != null));
        setDirty();
    }

    public void forget(BotPlayer bot, BlockPos pos) {
        String dimension = bot.level().dimension().location().toString();
        if (entries.values().removeIf(entry -> entry.location().dimension().equals(dimension)
            && entry.positions().contains(pos.asLong()))) {
            setDirty();
        }
    }

    public List<RememberedSource> sources(BotPlayer bot) {
        // "Find/mine it" jobs gather from the world; chests are not a source then.
        if (bot.controller().worldOnly()) return List.of();
        String dimension = bot.level().dimension().location().toString();
        // Two terminals may show one network; without a network identity only the nearest one counts.
        boolean[] network = {false};
        return entries.values().stream().filter(entry -> entry.location().dimension().equals(dimension))
            .sorted(Comparator.comparingDouble(entry -> bot.position().distanceToSqr(
                net.minecraft.world.phys.Vec3.atCenterOf(BlockPos.of(entry.location().position())))))
            .filter(entry -> !entry.network() || !network[0] && (network[0] = true))
            .map(entry -> new RememberedSource(dimension, entry.location().position(), entry.blockId(), entry.contents()))
            .toList();
    }

    public String text(long now, Predicate<ItemStack> filter) {
        return text(now, filter, null);
    }

    public String text(long now, Predicate<ItemStack> filter, Location only) {
        return entries.values().stream().filter(entry -> only == null || entry.location().dimension().equals(only.dimension())
            && entry.positions().contains(only.position())).filter(entry -> filter == null || entry.contents().keySet().stream()
            .anyMatch(key -> filter.test(key.stack()))).map(entry -> {
                var source = new RememberedSource(entry.location().dimension(), entry.location().position(), entry.blockId(), entry.contents());
                long seconds = Math.max(0, now - entry.seenTick()) / 20;
                String age = seconds < 60 ? seconds + " sec" : seconds / 60 + " min";
                String contents = entry.contents().entrySet().stream().map(item -> {
                    var description = ItemDescriber.describe(item.getKey().stack());
                    return (item.getValue() == 1 ? "" : item.getValue() + " ") + description.id()
                        + (description.maxDurability() == 0 ? "" : " " + description.remaining() + "/" + description.maxDurability())
                        + (description.customName().isEmpty() ? "" : " \"" + description.customName() + "\"");
                }).collect(Collectors.joining(", "));
                return source.description() + (entry.network() ? " network terminal" : "") + " [" + source.dimension() + "] (seen " + age + " ago): "
                    + (contents.isEmpty() ? "empty" : contents);
            }).collect(Collectors.collectingAndThen(Collectors.joining("\n"), text -> text.isEmpty() ? "no remembered containers" : text));
    }

    /** Compact answer for the future brain; deliberately uses snapshots only. */
    public String whereIs(ItemKey item) {
        String answer = entries.values().stream().filter(entry -> entry.contents().getOrDefault(item, 0L) > 0)
            .map(entry -> entry.contents().get(item) + " " + item + " in "
                + new RememberedSource(entry.location().dimension(), entry.location().position(), entry.blockId(), entry.contents()).description()
                + " [" + entry.location().dimension() + "]")
            .collect(Collectors.joining("; "));
        return answer.isEmpty() ? "no remembered " + item : answer;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag value = new CompoundTag();
            value.putString("dimension", entry.location().dimension());
            value.putLong("position", entry.location().position());
            value.putString("block", entry.blockId());
            value.putLong("seen", entry.seenTick());
            value.putLongArray("positions", entry.positions());
            if (entry.network()) value.putBoolean("network", true);
            ListTag contents = new ListTag();
            entry.contents().forEach((key, count) -> {
                CompoundTag item = new CompoundTag();
                item.put("stack", key.stack().save(registries));
                item.putLong("count", count);
                contents.add(item);
            });
            value.put("contents", contents);
            list.add(value);
        }
        tag.put("containers", list);
        return tag;
    }

    public static ContainerMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        ContainerMemory memory = new ContainerMemory();
        for (Tag raw : tag.getList("containers", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            Location location = new Location(value.getString("dimension"), value.getLong("position"));
            Map<ItemKey, Long> contents = new TreeMap<>();
            for (Tag rawItem : value.getList("contents", Tag.TAG_COMPOUND)) {
                CompoundTag item = (CompoundTag) rawItem;
                ItemStack stack = ItemStack.parseOptional(registries, item.getCompound("stack"));
                if (!stack.isEmpty() && item.getLong("count") > 0) {
                    contents.merge(new ItemKey(stack), item.getLong("count"), Long::sum);
                }
            }
            List<Long> positions = java.util.Arrays.stream(value.getLongArray("positions")).boxed().toList();
            memory.entries.put(location, new Entry(location, value.getString("block"), contents, value.getLong("seen"), positions,
                value.getBoolean("network")));
        }
        return memory;
    }
}
