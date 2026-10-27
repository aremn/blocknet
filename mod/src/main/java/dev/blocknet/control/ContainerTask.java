package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.ItemKey;
import dev.blocknet.memory.ContainerMemory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Generic menu transfer. Every mutation is a delayed player click; the cursor always returns. */
final class ContainerTask {
    enum Action { INSPECT, DEPOSIT, WITHDRAW }
    private enum Phase { OPEN, SELECT, PLACE, RETURN }

    private final BotPlayer bot;
    private final BotController controller;
    private final BlockPos pos;
    private final Action action;
    private final Predicate<ItemStack> filter;
    private final long requested;
    private final Consumer<String> completion;
    private final Map<ItemKey, Long> moved = new TreeMap<>();
    private AbstractContainerMenu menu;
    private Phase phase = Phase.OPEN;
    private int delay;
    private int phaseTicks;
    private int origin = -1;
    private long remaining;
    private long batch;
    private boolean finished;

    ContainerTask(BotPlayer bot, BotController controller, BlockPos pos, Action action,
        Predicate<ItemStack> filter, long count, Consumer<String> completion) {
        this.bot = bot;
        this.controller = controller;
        this.pos = pos.immutable();
        this.action = action;
        this.filter = filter;
        requested = count;
        this.completion = completion;
        bot.closeContainer();
    }

    void tick() {
        if (finished) {
            return;
        }
        if (menu != null) {
            if (bot.containerMenu != menu || !menu.stillValid(bot)) {
                fail("container menu closed");
                return;
            }
            bot.containerMemory().observe(bot, pos);
        }
        if (delay-- > 0) {
            return;
        }
        delay = CraftExecutor.CLICK_DELAY - 1;
        switch (phase) {
            case OPEN -> open();
            case SELECT -> select();
            case PLACE -> place();
            case RETURN -> returnCursor();
        }
    }

    boolean checkTimeout() {
        if (++phaseTicks > CraftExecutor.PHASE_TIMEOUT) {
            fail("step 1: stuck in " + phase.name().toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        return !finished;
    }

    private List<Slot> sources() {
        return menu.slots.stream().filter(slot -> slot.isActive() && (action == Action.DEPOSIT
            ? slot.container == bot.getInventory() && slot.getContainerSlot() < 36
            : slot.container != bot.getInventory())).toList();
    }

    private List<Slot> destinations() {
        return menu.slots.stream().filter(slot -> slot.isActive() && (action == Action.DEPOSIT
            ? slot.container != bot.getInventory()
            : slot.container == bot.getInventory() && slot.getContainerSlot() < 36)).toList();
    }

    private void open() {
        var hit = controller.craftApproach(pos);
        if (hit == null || finished) {
            return;
        }
        bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        if (bot.containerMenu == bot.inventoryMenu || ContainerMemory.containerSlots(bot).isEmpty()) {
            // A locked/protected container is still a container; retain the last observation.
            var state = bot.level().getBlockState(pos);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            boolean replaced = bot.containerMemory().entries().stream().anyMatch(entry ->
                entry.location().dimension().equals(bot.level().dimension().location().toString())
                    && entry.positions().contains(pos.asLong()) && !entry.blockId().equals(blockId));
            if (replaced || !state.hasBlockEntity() && state.getMenuProvider(bot.level(), pos) == null) {
                bot.containerMemory().forget(bot, pos);
            }
            fail("not a container or container did not open" + bot.worldMemory().nearby(bot, 64).stream()
                .filter(e -> e.container() && !e.pos().equals(pos)).limit(3)
                .map(e -> "; remembered container " + e.pos().toShortString()).collect(Collectors.joining()));
            return;
        }
        menu = bot.containerMenu;
        bot.containerMemory().observe(bot, pos);
        if (action == Action.INSPECT) {
            complete("looked in " + description());
            return;
        }
        Map<ItemKey, Long> wanted = new TreeMap<>();
        long available = 0;
        for (Slot slot : sources()) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && slot.mayPickup(bot) && filter.test(stack)) {
                long count = requested == 0 ? stack.getCount() : Math.min(stack.getCount(), requested - available);
                if (count > 0) {
                    if (count < stack.getCount() && !slot.mayPlace(stack)) {
                        fail("cannot split a read-only output slot");
                        return;
                    }
                    wanted.merge(new ItemKey(stack), count, Long::sum);
                    available += count;
                }
            }
        }
        if (available == 0 || requested > available) {
            fail("nothing matching" + (available > 0 ? " (not enough items)" : ""));
            return;
        }
        remaining = available;
        // Reserve capacity for all identities together before picking anything up.
        var destinations = destinations();
        List<ItemStack> simulated = new ArrayList<>();
        destinations.forEach(slot -> simulated.add(slot.getItem().copy()));
        for (var entry : wanted.entrySet()) {
            long left = entry.getValue();
            ItemStack stack = entry.getKey().stack();
            for (int i = 0; i < destinations.size() && left > 0; i++) {
                Slot slot = destinations.get(i);
                ItemStack existing = simulated.get(i);
                if (slot.mayPlace(stack) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, stack))) {
                    int room = slot.getMaxStackSize(stack) - existing.getCount();
                    int transfer = (int) Math.min(left, Math.max(0, room));
                    if (transfer > 0) {
                        simulated.set(i, stack.copyWithCount(existing.getCount() + transfer));
                        left -= transfer;
                    }
                }
            }
            if (left > 0) {
                fail("no room");
                return;
            }
        }
        next(Phase.SELECT);
    }

    private void select() {
        if (remaining == 0) {
            complete((action == Action.DEPOSIT ? "deposited " : "withdrew ")
                + moved.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey())
                    .collect(Collectors.joining(", "))
                + (action == Action.DEPOSIT ? " into " : " from ") + description());
            return;
        }
        for (Slot slot : sources()) {
            if (slot.hasItem() && slot.mayPickup(bot) && filter.test(slot.getItem())) {
                origin = slot.index;
                batch = Math.min(remaining, slot.getItem().getCount());
                click(origin, 0);
                if (menu.getCarried().isEmpty()) {
                    fail("source rejected pickup");
                    return;
                }
                next(Phase.PLACE);
                return;
            }
        }
        fail("items changed while transferring");
    }

    private void place() {
        ItemStack carried = menu.getCarried();
        if (batch == 0 || carried.isEmpty()) {
            next(Phase.RETURN);
            return;
        }
        // Merge compatible stacks before occupying an empty slot needed by another identity.
        var destinations = destinations().stream()
            .sorted(Comparator.comparingInt((Slot slot) -> slot.hasItem() ? 0 : 1)).toList();
        for (Slot slot : destinations) {
            ItemStack existing = slot.getItem();
            if (!slot.mayPlace(carried) || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, carried)) {
                continue;
            }
            int room = slot.getMaxStackSize(carried) - existing.getCount();
            if (room <= 0) {
                continue;
            }
            ItemKey key = new ItemKey(carried);
            int before = carried.getCount();
            // A left click moves the whole cursor when the remaining count permits it.
            click(slot.index, batch >= before && room >= before ? 0 : 1);
            int transferred = before - menu.getCarried().getCount();
            if (transferred <= 0) {
                fail("destination rejected item");
                return;
            }
            remaining -= transferred;
            batch -= transferred;
            moved.merge(key, (long) transferred, Long::sum);
            if (batch == 0 || menu.getCarried().isEmpty()) {
                next(Phase.RETURN);
            }
            return;
        }
        fail("no room (contents changed)");
    }

    private void returnCursor() {
        if (!menu.getCarried().isEmpty()) {
            click(origin, 0);
            if (!menu.getCarried().isEmpty()) {
                fail("source changed while returning cursor");
                return;
            }
        }
        origin = -1;
        next(Phase.SELECT);
    }

    private void next(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void click(int slot, int button) {
        menu.clicked(slot, button, ClickType.PICKUP, bot);
        menu.broadcastChanges();
        bot.containerMemory().observe(bot, pos);
    }

    private String description() {
        String id = BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(pos).getBlock()).toString();
        return (id.startsWith("minecraft:") ? id.substring(10) : id) + " at "
            + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    void fail(String reason) {
        complete("failed: " + reason);
    }

    private void complete(String report) {
        if (finished) {
            return;
        }
        finished = true;
        if (menu != null && bot.containerMenu == menu) {
            if (!menu.getCarried().isEmpty() && origin >= 0) {
                // Cancellation/failure returns unplaced items to their source before vanilla close.
                menu.clicked(origin, 0, ClickType.PICKUP, bot);
            }
            bot.containerMemory().observe(bot, pos);
        }
        bot.closeContainer();
        completion.accept(report);
    }
}
