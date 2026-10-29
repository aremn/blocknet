// SPDX-License-Identifier: MIT
package dev.blocknet.compat.ae2;

import appeng.helpers.InventoryAction;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import dev.blocknet.control.NetworkMenus;
import dev.blocknet.control.NetworkMenus.Entry;
import dev.blocknet.control.NetworkMenus.Snapshot;
import dev.blocknet.control.NetworkMenus.Status;
import dev.blocknet.crafting.ItemKey;
import java.util.ArrayList;
import java.util.HashSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Reads an open terminal through what its menu last prepared for this player in
 * {@code broadcastChanges}: the same stacks, visibility filter and link status a client receives. It
 * never reaches the grid, drives or cells directly. Changes go through {@code handleInteraction}, the
 * server half of a terminal click, so power, channels and the player's access still decide.
 */
public final class Ae2TerminalAdapter implements NetworkMenus.Adapter, dev.blocknet.control.BlockParts.Adapter {
    @Override
    public java.util.List<String> parts(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof appeng.api.parts.IPartHost host)) return java.util.List.of();
        var parts = new ArrayList<String>();
        for (var side : net.minecraft.core.Direction.values()) {
            var part = host.getPart(side);
            if (part != null) parts.add(side.getName() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(part.getPartItem().asItem()));
        }
        var cable = host.getPart(null);
        if (cable != null) parts.add("core " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cable.getPartItem().asItem()));
        return parts;
    }

    @Override
    public boolean handles(AbstractContainerMenu menu) { return menu instanceof MEStorageMenu && menu instanceof Ae2MenuView; }

    @Override
    public Snapshot read(ServerPlayer player, AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu terminal) || !(menu instanceof Ae2MenuView view)) return null;
        if (player.containerMenu != menu || !menu.stillValid(player)) return null;
        if (view.blocknet$broadcasts() == 0) return new Snapshot(Status.PENDING, "", java.util.List.of());
        var link = terminal.getLinkStatus();
        if (!link.connected()) {
            var why = link.statusDescription();
            return new Snapshot(Status.DISCONNECTED, why == null ? "no network" : why.getString(), java.util.List.of());
        }
        var entries = new ArrayList<Entry>();
        var craftables = new HashSet<AEKey>();
        for (var key : view.blocknet$craftables()) if (terminal.isKeyVisible(key)) craftables.add(key);
        for (var stack : view.blocknet$available()) {
            var key = stack.getKey();
            if (stack.getLongValue() <= 0 || !terminal.isKeyVisible(key)) continue;
            entries.add(entry(key, stack.getLongValue(), craftables.remove(key)));
        }
        for (var key : craftables) entries.add(entry(key, 0, true));
        return new Snapshot(entries.isEmpty() ? Status.EMPTY : Status.READY, "", entries);
    }

    private static Entry entry(AEKey key, long amount, boolean craftable) {
        if (key instanceof AEItemKey item) return new Entry(new ItemKey(item.toStack(1)).toString(), amount, "", craftable, item.toStack(1));
        // Fluids and other resources count in their own smallest unit (for fluids, 1/1000 bucket).
        String unit = key.getAmountPerUnit() > 1
            ? "(1/" + key.getAmountPerUnit() + " " + java.util.Objects.requireNonNullElse(key.getUnitSymbol(), "unit") + ")" : "";
        String kind = key instanceof AEFluidKey ? "fluid" : key.getType().getId().toString();
        return new Entry(kind + " " + key.getId(), amount, unit, craftable, ItemStack.EMPTY);
    }

    @Override
    public void pickup(ServerPlayer player, AbstractContainerMenu menu, ItemStack item) {
        var key = AEItemKey.of(item);
        if (key == null || !(menu instanceof MEStorageMenu terminal) || !(menu instanceof Ae2MenuView view)) return;
        // Only keys the screen was sent have a serial; a player cannot click anything else either.
        Long serial = view.blocknet$serial(key);
        if (serial != null) terminal.handleInteraction(serial, InventoryAction.PICKUP_OR_SET_DOWN);
    }

    /** Players whose craft request reached the confirmation screen; returning to a terminal after it means submitted. */
    private final java.util.Map<ServerPlayer, Boolean> confirming = new java.util.WeakHashMap<>();

    @Override
    public String requestCraft(ServerPlayer player, AbstractContainerMenu menu, ItemStack item, long count) {
        var key = AEItemKey.of(item);
        if (key == null || !(menu instanceof MEStorageMenu terminal) || !(menu instanceof Ae2MenuView view)) return "not a terminal";
        if (!view.blocknet$craftables().contains(key) || !terminal.isKeyVisible(key)) return "the network has no pattern for it";
        Long serial = view.blocknet$serial(key);
        if (serial == null) return "the terminal has not shown it yet";
        // The terminal's craft click opens the amount screen, whose confirm plans and (auto)starts the job.
        terminal.handleInteraction(serial, InventoryAction.AUTO_CRAFT);
        if (!(player.containerMenu instanceof CraftAmountMenu amount)) return "the terminal did not open its craft screen";
        confirming.put(player, false);
        amount.confirm((int) Math.min(Integer.MAX_VALUE, count), false, true);
        if (player.containerMenu instanceof CraftConfirmMenu) confirming.put(player, true);
        return null;
    }

    @Override
    public String craftOutcome(ServerPlayer player) {
        var menu = player.containerMenu;
        if (menu instanceof CraftConfirmMenu confirm) {
            confirming.put(player, true);
            var error = confirm.submitError.result();
            if (error != null && error.errorCode() != null) return "failed: network refused the job (" + error.errorCode() + ")";
            var plan = confirm.getPlan();
            if (plan != null && plan.isSimulation()) {
                var missing = plan.getEntries().stream().filter(e -> e.getMissingAmount() > 0)
                    .map(e -> e.getMissingAmount() + " " + name(e.getWhat())).toList();
                return "failed: network is short of " + (missing.isEmpty() ? "ingredients" : String.join(", ", missing));
            }
            if (plan != null && confirm.hasNoCPU()) return "failed: no free crafting CPU for the job";
            return null;
        }
        if (menu instanceof MEStorageMenu && Boolean.TRUE.equals(confirming.remove(player))) return "submitted";
        if (menu instanceof CraftAmountMenu) return null;
        confirming.remove(player);
        return "failed: the craft screens closed before the job started";
    }

    private static String name(AEKey key) {
        return key instanceof AEItemKey item ? new ItemKey(item.toStack(1)).toString() : key.getId().toString();
    }

    @Override
    public String encodePattern(ServerPlayer player, AbstractContainerMenu menu,
            net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> recipe) {
        if (!(menu instanceof appeng.menu.me.items.PatternEncodingTermMenu term))
            return "this terminal cannot encode patterns; open a pattern encoding terminal";
        var encoded = term.getSlots(appeng.menu.SlotSemantics.ENCODED_PATTERN).getFirst();
        var blank = term.getSlots(appeng.menu.SlotSemantics.BLANK_PATTERN).getFirst();
        if (encoded.hasItem()) return "the terminal's output slot already holds a pattern; take it out first";
        // A blank goes in by hand, like a player: pick it up from the inventory and drop it in the blank slot.
        if (!blank.hasItem()) {
            var source = term.slots.stream().filter(s -> s.container == player.getInventory()
                && appeng.core.definitions.AEItems.BLANK_PATTERN.is(s.getItem())).findFirst().orElse(null);
            if (source == null) return "no blank pattern in the inventory or the terminal";
            term.clicked(source.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
            term.clicked(blank.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
            if (!term.getCarried().isEmpty()) term.clicked(source.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, player);
            if (!blank.hasItem()) return "the terminal did not take the blank pattern";
        }
        // The same requests the terminal's screen sends: crafting mode, clear, then each grid ingredient.
        term.receiveClientAction("setMode", "\"CRAFTING\"");
        term.receiveClientAction("clear", null);
        var grid = term.getCraftingGridSlots();
        var ingredients = appeng.util.CraftingRecipeUtil.ensure3by3CraftingMatrix(recipe.value());
        var stored = menu instanceof Ae2MenuView view ? view.blocknet$available() : null;
        for (int i = 0; i < ingredients.size() && i < grid.length; i++) {
            var ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) continue;
            ItemStack pick = null;
            // Prefer what the network or the inventory actually holds, as the recipe viewer does.
            if (stored != null) for (var entry : stored) {
                if (entry.getKey() instanceof AEItemKey key && key.matches(ingredient)) { pick = key.toStack(); break; }
            }
            if (pick == null) pick = player.getInventory().items.stream().filter(ingredient).findFirst().map(st -> st.copyWithCount(1)).orElse(null);
            if (pick == null) pick = ingredient.getItems()[0].copyWithCount(1);
            term.setFilter(grid[i].index, pick);
        }
        term.receiveClientAction("encode", null);
        if (!encoded.hasItem()) return "the terminal refused to encode it (the grid does not make that recipe)";
        term.clicked(encoded.index, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, player);
        if (encoded.hasItem()) return "encoded, but no room to take the pattern; it stays in the terminal";
        return null;
    }

    @Override
    public void putCarried(ServerPlayer player, AbstractContainerMenu menu, boolean single) {
        // Serial -1 is the terminal's empty area, which only accepts the carried stack.
        if (menu instanceof MEStorageMenu terminal)
            terminal.handleInteraction(-1, single ? InventoryAction.SPLIT_OR_PLACE_SINGLE : InventoryAction.PICKUP_OR_SET_DOWN);
    }
}
