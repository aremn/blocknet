// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.ItemKey;
import java.util.*;
import net.minecraft.world.inventory.*;

/** Generic player menu API. Network terminals add contents through {@link NetworkMenus}; slots are never guessed. */
public final class MenuAccess {
    private static final Map<BotPlayer, Integer> LAST_CLICK = new WeakHashMap<>();
    private MenuAccess() {}
    public static String snapshot(BotPlayer bot) { return snapshot(bot, null, null); }
    /** Physical slots, then the network section when an adapter handles this menu (filtered and paged). */
    public static String snapshot(BotPlayer bot, String filter, String page) {
        var menu = bot.containerMenu;
        if (menu == bot.inventoryMenu) return "no open block menu; use a ref first";
        if (!menu.stillValid(bot)) return "failed: menu no longer reachable";
        List<String> rows = new ArrayList<>();
        rows.add("menu_id " + menu.containerId + "; type " + net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(menu.getType())
            + "; cursor " + (menu.getCarried().isEmpty() ? "empty" : menu.getCarried().getCount() + " " + new ItemKey(menu.getCarried())));
        for (var s : menu.slots) if (s.isActive()) rows.add("slot " + s.index + (s.container == bot.getInventory() ? " player" : " container")
            + ": " + (s.hasItem() ? s.getItem().getCount() + " " + new ItemKey(s.getItem()) : "empty")
            + "; pickup " + s.mayPickup(bot));
        var data = ((dev.blocknet.mixin.MenuDataAccessor) menu).blocknet$dataSlots();
        for(int i=0;i<data.size();i++) rows.add("data " + i + " = " + data.get(i).get());
        var network = NetworkMenus.section(bot, menu, filter, page);
        if (network != null) rows.add(network);
        return String.join("; ", rows);
    }
    /**
     * Encode a crafting pattern for {@code item} on the open pattern terminal. The recipe comes from the
     * server's own recipes (what a recipe viewer shows), preferring one whose inputs the bot or network has.
     */
    public static String encodePattern(BotPlayer bot, String item) {
        var menu = bot.containerMenu;
        if (menu == bot.inventoryMenu || !menu.stillValid(bot)) return "failed: open the pattern terminal first (use its ref)";
        var adapter = NetworkMenus.adapter(bot, menu);
        if (adapter == null) return "failed: the open menu is not a storage-network terminal";
        var id = net.minecraft.resources.ResourceLocation.tryParse(item.contains(":") ? item : "minecraft:" + item);
        var target = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (target == null) return "failed: unknown item " + item;
        var level = bot.serverLevel();
        var recipes = level.getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING).stream()
            .filter(r -> !r.value().isSpecial() && r.value().getResultItem(level.registryAccess()).is(target)).toList();
        if (recipes.isEmpty()) return "failed: no crafting-table recipe makes " + item + "; processing patterns are not supported yet";
        var have = bot.getInventory().items;
        var stored = NetworkMenus.read(bot, menu);
        var recipe = recipes.stream().max(java.util.Comparator.comparingLong(r -> r.value().getIngredients().stream()
            .filter(i -> !i.isEmpty() && (have.stream().anyMatch(i) || stored != null && stored.entries().stream().anyMatch(e -> !e.item().isEmpty() && i.test(e.item()))))
            .count())).orElseThrow();
        String refused = adapter.encodePattern(bot, menu, recipe);
        LAST_CLICK.put(bot, bot.tickCount);
        if (refused != null) return "failed: " + refused;
        var output = recipe.value().getResultItem(level.registryAccess());
        return "encoded a crafting pattern for " + output.getCount() + " " + new ItemKey(output) + " (recipe " + recipe.id()
            + "); the pattern is in the inventory: deposit it into a pattern provider to let the network craft it";
    }

    public static String click(BotPlayer bot, int menuId, int slot, int button, String mode) {
        var menu = bot.containerMenu;
        if (menu == bot.inventoryMenu || menu.containerId != menuId || !menu.stillValid(bot))
            return "failed: stale or unreachable menu; inspect menu again";
        if (bot.tickCount - LAST_CLICK.getOrDefault(bot, -100) < CraftExecutor.CLICK_DELAY)
            return "blocked: wait at least three game ticks between menu clicks";
        if (!Set.of("pickup", "quick_move", "button").contains(mode)) return "failed: unsupported click mode";
        if (mode.equals("button")) {
            if (button < 0 || button > 255) return "failed: invalid menu button";
            if (!menu.clickMenuButton(bot, button)) return "unsupported: menu rejected button; a custom adapter may be needed";
        } else {
            if (slot < 0 || slot >= menu.slots.size() || !menu.getSlot(slot).isActive() || button < 0 || button > 1)
                return "failed: invalid slot or mouse button";
            menu.clicked(slot, button, mode.equals("pickup") ? ClickType.PICKUP : ClickType.QUICK_MOVE, bot);
        }
        LAST_CLICK.put(bot, bot.tickCount); menu.broadcastChanges();
        return "clicked; " + snapshot(bot);
    }
}
