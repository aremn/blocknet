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

/**
 * Generic menu transfer. Every mutation is a delayed player click; the cursor always returns. When the
 * menu is a storage-network terminal ({@link NetworkMenus}), the network side is clicked through its
 * adapter instead of slots: pick a stored stack onto the cursor, or put the carried stack in.
 */
final class ContainerTask {
    enum Action { INSPECT, DEPOSIT, WITHDRAW, AUTOCRAFT }
    private enum Phase { OPEN, SELECT, PLACE, RETURN, CRAFTING }
    /** Network planning runs on a background thread; a large job can take a while. */
    private static final int CRAFT_PLAN_TIMEOUT = 1200;

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
    private ItemStack capacityProbe = ItemStack.EMPTY;
    private Consumer<Map<ItemKey, Long>> movement = ignored -> {};
    /** Origin marker for a cursor that came out of a network: it goes back through the adapter. */
    private static final int NETWORK_ORIGIN = -2;
    private NetworkMenus.Adapter network;
    /** Other parts of the block to click when the first click opened nothing (a terminal on a cable). */
    private List<net.minecraft.world.phys.BlockHitResult> aims;
    private int aimIndex;
    private int openedTick;
    private String craftName = "";
    /** A freshly built or reloaded network boots for a moment; a player would wait before giving up. */
    private static final int NETWORK_BOOT = 40;

    ContainerTask probe(ItemStack stack) { capacityProbe = stack.copy(); return this; }
    ContainerTask movement(Consumer<Map<ItemKey, Long>> observer) { movement = observer; return this; }

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
        if (phase == Phase.CRAFTING) {
            // The terminal's own craft screens replace its menu until the job is submitted.
            String outcome = network.craftOutcome(bot);
            if (outcome == null) return;
            if (outcome.equals("submitted")) complete("autocraft submitted: " + requested + " " + craftName
                + " from the network at " + description() + "; the network crafts it, withdraw it when done");
            else fail(outcome.startsWith("failed: ") ? outcome.substring(8) : outcome);
            return;
        }
        if (menu != null) {
            if (bot.containerMenu != menu || !menu.stillValid(bot)) {
                fail("container menu closed");
                return;
            }
            // A network snapshot walks every stored key; it is remembered after each action instead.
            if (network == null) bot.containerMemory().observe(bot, pos);
        }
        if (delay-- > 0) {
            return;
        }
        delay = CraftExecutor.CLICK_DELAY - 1;
        switch (phase) {
            case OPEN -> { if (menu == null) open(); else start(); }
            case SELECT -> select();
            case PLACE -> place();
            case RETURN -> returnCursor();
            case CRAFTING -> {}
        }
    }

    boolean checkTimeout() {
        if (++phaseTicks > (phase == Phase.CRAFTING ? CRAFT_PLAN_TIMEOUT : CraftExecutor.PHASE_TIMEOUT)) {
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
        if (aims != null && aimIndex < aims.size()) hit = aims.get(aimIndex++);
        int held = bot.getMainHandItem().getCount();
        bot.gameMode.useItemOn(bot, bot.serverLevel(), bot.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        // A click that used up the held item placed it somewhere: never repeat that on another part.
        if (bot.containerMenu == bot.inventoryMenu && bot.getMainHandItem().getCount() == held) {
            if (aims == null) aims = controller.partAims(pos);
            if (aimIndex < aims.size()) {
                controller.trace("aim", "nothing opened; trying another part of " + description() + " (" + (aimIndex + 1) + "/" + aims.size() + ")");
                return;
            }
        }
        if (bot.containerMenu == bot.inventoryMenu
            || ContainerMemory.containerSlots(bot).isEmpty() && NetworkMenus.adapter(bot, bot.containerMenu) == null) {
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
        network = NetworkMenus.adapter(bot, menu);
        openedTick = bot.tickCount;
        bot.containerMemory().observe(bot, pos);
        start();
    }

    /** Plans the whole move once the menu is open; a network that is still synchronizing retries. */
    private void start() {
        if (network != null) {
            var snapshot = NetworkMenus.read(bot, menu);
            if (snapshot == null || snapshot.status() == NetworkMenus.Status.PENDING) return;
            if (snapshot.status() == NetworkMenus.Status.DISCONNECTED) {
                if (bot.tickCount - openedTick < NETWORK_BOOT) return;
                fail("network disconnected" + (snapshot.reason().isEmpty() ? "" : ": " + snapshot.reason()) + "; contents unknown");
                return;
            }
            if (!capacityProbe.isEmpty()) { fail("transfer does not support network terminals; use withdraw or deposit"); return; }
            if (action == Action.INSPECT) { complete("looked in " + description() + "; " + NetworkMenus.section(bot, menu, null, null)); return; }
            if (action == Action.AUTOCRAFT) {
                var entry = snapshot.entries().stream().filter(e -> e.craftable() && !e.item().isEmpty() && filter.test(e.item())).findFirst().orElse(null);
                if (entry == null) { fail("the network has no pattern for that; craftable: " + snapshot.entries().stream()
                    .filter(NetworkMenus.Entry::craftable).limit(12).map(NetworkMenus.Entry::key).toList()); return; }
                craftName = entry.key();
                String refused = network.requestCraft(bot, menu, entry.item(), Math.max(1, requested));
                if (refused != null) { fail(refused); return; }
                controller.trace("network", "asked the network to craft " + Math.max(1, requested) + " " + craftName);
                next(Phase.CRAFTING);
                return;
            }
        }
        if (action == Action.AUTOCRAFT) { fail("not a storage-network terminal"); return; }
        if (!capacityProbe.isEmpty()) {
            int room = destinations().stream().filter(s -> s.mayPlace(capacityProbe))
                .filter(s -> !s.hasItem() || ItemStack.isSameItemSameComponents(s.getItem(), capacityProbe))
                .mapToInt(s -> Math.max(0, s.getMaxStackSize(capacityProbe) - s.getItem().getCount())).sum();
            complete("capacity " + room); return;
        }
        if (action == Action.INSPECT) {
            complete("looked in " + description());
            return;
        }
        Map<ItemKey, Long> wanted = new TreeMap<>();
        long available = 0;
        if (network != null && action == Action.WITHDRAW) {
            for (var entry : NetworkMenus.read(bot, menu).entries()) {
                if (entry.item().isEmpty() || entry.amount() <= 0 || !filter.test(entry.item())) continue;
                long count = requested == 0 ? entry.amount() : Math.min(entry.amount(), requested - available);
                if (count > 0) { wanted.merge(new ItemKey(entry.item()), count, Long::sum); available += count; }
            }
        } else for (Slot slot : sources()) {
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
        if (network != null && action == Action.DEPOSIT) {
            // The network reports its own room by accepting or refusing each put; partial results are reported.
            next(Phase.SELECT);
            return;
        }
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
        if (network != null && action == Action.WITHDRAW) {
            var snapshot = NetworkMenus.read(bot, menu);
            var entry = snapshot == null ? null : snapshot.entries().stream()
                .filter(e -> !e.item().isEmpty() && e.amount() > 0 && filter.test(e.item())).findFirst().orElse(null);
            if (entry == null) { fail("items changed while transferring"); return; }
            network.pickup(bot, menu, entry.item());
            menu.broadcastChanges();
            if (menu.getCarried().isEmpty() || !filter.test(menu.getCarried())) {
                fail("network did not hand out " + entry.key() + " (unpowered or no access)");
                return;
            }
            origin = NETWORK_ORIGIN;
            batch = Math.min(remaining, menu.getCarried().getCount());
            next(Phase.PLACE);
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
        if (network != null && action == Action.DEPOSIT) {
            ItemKey key = new ItemKey(carried);
            int before = carried.getCount();
            network.putCarried(bot, menu, batch < before);
            menu.broadcastChanges();
            int transferred = before - menu.getCarried().getCount();
            if (transferred <= 0) { fail("network refused " + key + " (full, unpowered or not accepted)"); return; }
            remaining -= transferred;
            batch -= transferred;
            moved.merge(key, (long) transferred, Long::sum);
            phaseTicks = 0; // one item per click is progress, not a stuck phase
            if (batch == 0 || menu.getCarried().isEmpty()) next(Phase.RETURN);
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
            phaseTicks = 0;
            if (batch == 0 || menu.getCarried().isEmpty()) {
                next(Phase.RETURN);
            }
            return;
        }
        fail("no room (contents changed)");
    }

    private void returnCursor() {
        if (!menu.getCarried().isEmpty()) {
            if (origin == NETWORK_ORIGIN) { network.putCarried(bot, menu, false); menu.broadcastChanges(); }
            else click(origin, 0);
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
        // Network moves can stop part way (a full network); say what already moved.
        complete("failed: " + reason + (network == null || moved.isEmpty() ? "" : "; moved so far "
            + moved.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey()).collect(Collectors.joining(", "))));
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
            } else if (!menu.getCarried().isEmpty() && origin == NETWORK_ORIGIN) {
                network.putCarried(bot, menu, false);
                menu.broadcastChanges();
            }
            bot.containerMemory().observe(bot, pos);
        }
        bot.closeContainer();
        movement.accept(Map.copyOf(moved));
        completion.accept(report);
    }
}
