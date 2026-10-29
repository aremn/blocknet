// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.ItemKey;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.*;
import dev.blocknet.memory.JobMemory;

/**
 * Moves a container's contents like a player: open the source, take everything that fits, walk
 * to the destination and put back exactly what was taken (never the bot's own items), repeat until
 * the source is empty. Only menu clicks move items. The cargo is checkpointed so a crash or a
 * restart can finish the delivery.
 */
final class TransferTask extends SkillTask {
    private BlockPos source, destination;
    private final String label;
    private final Map<ItemKey, Long> delivered = new TreeMap<>();
    /** Taken from the source and not delivered yet. */
    private final Map<ItemKey, Long> cargo = new TreeMap<>();
    /** The source as this job left it after its last withdrawal; null until the first one. */
    private Map<ItemKey, Long> expected;
    private final Map<ItemKey, Long> room = new HashMap<>();
    private final Set<ItemKey> taken = new HashSet<>();
    private final Map<ItemKey, Long> tookThisTrip = new HashMap<>();
    private int phase, trips;
    private boolean incompatible, cargoMissing;

    TransferTask(BotPlayer bot, BlockPos source, BlockPos destination, String label, Consumer<String> report) {
        super(bot, report, 20 * 60 * 10);
        this.source = canonical(source); this.destination = canonical(destination); this.label = label;
        CompoundTag saved = JobMemory.of(bot).transfer();
        if (!saved.isEmpty()) {
            if (saved.getLong("source") != this.source.asLong() || saved.getLong("destination") != this.destination.asLong()
                    || !saved.getString("dimension").equals(bot.level().dimension().location().toString())) incompatible = true;
            else {
                readItems(saved, "cargo", cargo); readItems(saved, "delivered", delivered);
                trips = saved.getInt("trips");
                cargoMissing = cargo.entrySet().stream().anyMatch(e -> held(e.getKey()) < e.getValue());
                // Resume by delivering what is still carried.
                phase = cargo.isEmpty() ? 0 : 4;
            }
        }
    }
    private BlockPos canonical(BlockPos pos) {
        return bot.containerMemory().entries().stream().filter(e -> e.location().dimension().equals(bot.level().dimension().location().toString())
            && e.positions().contains(pos.asLong())).findFirst().map(e -> BlockPos.of(e.location().position())).orElse(pos).immutable();
    }
    private void readItems(CompoundTag tag, String field, Map<ItemKey, Long> items) {
        for (Tag raw : tag.getList(field, Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            ItemStack stack = ItemStack.parseOptional(bot.registryAccess(), row.getCompound("stack"));
            if (!stack.isEmpty() && row.getLong("count") > 0) items.put(new ItemKey(stack), row.getLong("count"));
        }
    }
    private void writeItems(CompoundTag tag, String field, Map<ItemKey, Long> items) {
        ListTag rows = new ListTag();
        items.forEach((key, n) -> { CompoundTag row = new CompoundTag(); row.put("stack", key.stack().save(bot.registryAccess())); row.putLong("count", n); rows.add(row); });
        tag.put(field, rows);
    }
    private void checkpoint() {
        if (incompatible) return;
        CompoundTag tag = new CompoundTag();
        tag.putLong("source", source.asLong()); tag.putLong("destination", destination.asLong());
        tag.putString("dimension", bot.level().dimension().location().toString()); tag.putInt("trips", trips);
        writeItems(tag, "cargo", cargo); writeItems(tag, "delivered", delivered);
        JobMemory.of(bot).transfer(tag);
    }
    @Override void ended(String message) {
        if (incompatible || cargoMissing) return;
        if (cargo.isEmpty()) JobMemory.of(bot).clearTransfer(); else checkpoint();
    }
    @Override String describe() { return "transfer " + label + "; delivered " + summary(delivered) + "; carrying " + summary(cargo) + "; trips " + trips; }
    private static String summary(Map<ItemKey, Long> items) {
        if (items.isEmpty()) return "nothing";
        StringJoiner text = new StringJoiner(", ");
        items.forEach((key, n) -> text.add(n + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(key.stack().getItem()).getPath()));
        return text.toString();
    }
    private int held(ItemKey key) { return bot.getInventory().items.stream().filter(s -> !s.isEmpty() && new ItemKey(s).equals(key)).mapToInt(ItemStack::getCount).sum(); }
    private Map<ItemKey, Long> contents(BlockPos pos) {
        return bot.containerMemory().entries().stream().filter(e -> e.location().dimension().equals(bot.level().dimension().location().toString())
            && e.positions().contains(pos.asLong())).findFirst().map(e -> (Map<ItemKey, Long>) new TreeMap<>(e.contents())).orElse(Map.of());
    }
    private boolean sourceEmpty() {
        return bot.containerMemory().entries().stream().filter(e -> e.location().dimension().equals(bot.level().dimension().location().toString())
            && e.positions().contains(source.asLong())).findFirst().map(e -> e.contents().isEmpty()).orElse(false);
    }
    private void blocked(String reason) { complete("failed: " + reason + "; " + describe()); }

    /** Room left for one kind in the bot's inventory. */
    private long inventoryRoom(ItemKey key) {
        long room = 0;
        for (ItemStack stack : bot.getInventory().items) {
            if (stack.isEmpty()) room += key.stack().getMaxStackSize();
            else if (new ItemKey(stack).equals(key)) room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return room;
    }

    /**
     * A trip: at the destination, ask how much of each source kind still fits there (no walking
     * between probes); at the source, take each kind up to that room and the inventory's room; back
     * at the destination, put exactly that cargo away. The first trip starts with a look at the source.
     */
    @Override void work() {
        if (busy) return;
        if (cargoMissing) { complete("failed: saved cargo is missing from inventory; restore it before resuming this job"); return; }
        if (incompatible) { complete("blocked: unfinished cargo job belongs to other containers; resume that job first"); return; }
        if (source.equals(destination)) { blocked("source equals destination"); return; }
        switch (phase) {
            case 0 -> action(cb -> controller.lookIn(source, cb), result -> {
                if (failed(result)) { blocked("source: " + result); return; }
                expected = contents(source);
                if (expected.isEmpty()) { complete("transferred " + label + "; source empty; delivered " + summary(delivered) + "; trips " + trips); return; }
                room.clear(); phase = 1;
            });
            case 1 -> {
                var next = expected.keySet().stream().filter(k -> !room.containsKey(k)).findFirst();
                if (next.isEmpty()) {
                    if (room.values().stream().allMatch(n -> n <= 0)) { blocked("destination has no room for " + summary(expected)); return; }
                    phase = 2;
                    return;
                }
                ItemKey key = next.get();
                action(cb -> controller.containerRoom(destination, key.stack(), cb), result -> {
                    if (failed(result) || !result.startsWith("capacity ")) { blocked("destination: " + result); return; }
                    room.put(key, Long.parseLong(result.substring(9)));
                });
            }
            // Someone else changing the source between trips must not end in a false success.
            case 2 -> action(cb -> controller.lookIn(source, cb), result -> {
                if (failed(result)) { blocked("source: " + result); return; }
                if (!expected.equals(contents(source))) { blocked("source changed outside this job; inspect and start a new transfer"); return; }
                taken.clear(); tookThisTrip.clear(); phase = 3;
            });
            case 3 -> {
                var next = expected.entrySet().stream().filter(e -> !taken.contains(e.getKey()))
                    .filter(e -> Math.min(e.getValue(), Math.min(room.getOrDefault(e.getKey(), 0L), inventoryRoom(e.getKey()))) > 0).findFirst();
                if (next.isEmpty()) {
                    // What the source must hold now if nobody else touched it: the plan minus this trip's take.
                    Map<ItemKey, Long> left = new TreeMap<>(expected);
                    tookThisTrip.forEach((k, n) -> { if (left.merge(k, -n, Long::sum) <= 0) left.remove(k); });
                    expected = left;
                    if (cargo.isEmpty()) {
                        if (expected.isEmpty()) complete("transferred " + label + "; source empty; delivered " + summary(delivered) + "; trips " + trips);
                        else blocked(room.values().stream().allMatch(n -> n <= 0) ? "destination is full" : "no inventory room for cargo");
                        return;
                    }
                    controller.trace("transfer", "carrying " + summary(cargo) + " to " + destination.toShortString());
                    phase = 4;
                    return;
                }
                ItemKey key = next.get().getKey();
                long want = Math.min(next.get().getValue(), Math.min(room.getOrDefault(key, 0L), inventoryRoom(key)));
                taken.add(key);
                action(cb -> controller.containerDetailed(source, false, s -> new ItemKey(s).equals(key), (int) want, moved -> {
                        moved.forEach((k, n) -> { cargo.merge(k, n, Long::sum); tookThisTrip.merge(k, n, Long::sum); }); checkpoint();
                    }, cb), result -> {
                        if (failed(result) && !cargo.containsKey(key)) blocked("source: " + result);
                    });
            }
            // Put back exactly the carried kinds and counts, at the same chest without walking.
            case 4 -> {
                var next = cargo.entrySet().stream().findFirst();
                if (next.isEmpty()) {
                    trips++; checkpoint();
                    if (expected == null || expected.isEmpty()) {
                        if (expected == null) { phase = 0; return; }
                        complete("transferred " + label + "; source empty; delivered " + summary(delivered) + "; trips " + trips);
                        return;
                    }
                    room.clear(); phase = 1;
                    return;
                }
                ItemKey key = next.get().getKey();
                long amount = next.get().getValue();
                action(cb -> controller.containerDetailed(destination, true, s -> new ItemKey(s).equals(key), (int) Math.min(Integer.MAX_VALUE, amount), moved -> {
                        long n = moved.getOrDefault(key, 0L);
                        if (n > 0) { delivered.merge(key, n, Long::sum); if (cargo.merge(key, -n, Long::sum) <= 0) cargo.remove(key); }
                        checkpoint();
                    }, cb), result -> {
                        if (cargo.containsKey(key)) blocked("destination is full or did not take " + summary(Map.of(key, cargo.get(key))) + ": " + result);
                    });
            }
            default -> throw new IllegalStateException("Invalid transfer phase");
        }
    }
}
