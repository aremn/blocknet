// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Virtual storage that an open menu shows besides its physical slots (network terminals). The body
 * only knows this interface; a mod adapter is registered by {@link dev.blocknet.compat.Compat} when
 * its mod is loaded. Adapters read what the server already prepared for this player's open menu and
 * never look up storage through the world.
 */
public final class NetworkMenus {
    public enum Status { READY, EMPTY, DISCONNECTED, PENDING }

    /**
     * One stored resource. {@code amount} is in the resource's own smallest unit; zero means craftable
     * only. {@code item} is a one-item sample for item resources and empty for anything else.
     */
    public record Entry(String key, long amount, String unit, boolean craftable, ItemStack item) {}

    public record Snapshot(Status status, String reason, List<Entry> entries) {}

    /**
     * Mutations are the terminal's own player actions (what its screen sends for a click), so energy,
     * permissions and visibility stay the network's decision. The caller checks the cursor afterwards.
     */
    public interface Adapter {
        boolean handles(AbstractContainerMenu menu);
        /** The open menu's network contents, or null when the menu is no longer this player's. */
        Snapshot read(ServerPlayer player, AbstractContainerMenu menu);
        /** Click a stored item with an empty cursor: up to one stack lands on the cursor. */
        void pickup(ServerPlayer player, AbstractContainerMenu menu, ItemStack item);
        /** Click the network area with a carried stack: insert all of it, or one item when {@code single}. */
        void putCarried(ServerPlayer player, AbstractContainerMenu menu, boolean single);
        /**
         * Ask the network to autocraft {@code count} of a craftable item through the terminal's own craft
         * request screens. Null when the request started; otherwise why it could not.
         */
        default String requestCraft(ServerPlayer player, AbstractContainerMenu menu, ItemStack item, long count) {
            return "this terminal cannot request crafts";
        }
        /** The started request: null while the network plans it, then "submitted ..." or "failed: ...". */
        default String craftOutcome(ServerPlayer player) { return "failed: this terminal cannot request crafts"; }
        /**
         * Encode a crafting pattern for {@code recipe} on an open pattern-encoding terminal the way a recipe
         * viewer's transfer button and the Encode button do, then take the pattern out. Null on success.
         */
        default String encodePattern(ServerPlayer player, AbstractContainerMenu menu,
                net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> recipe) {
            return "this menu cannot encode patterns";
        }
    }

    private static final List<Adapter> ADAPTERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private record Revision(int menuId, int hash, int number) {}
    private static final Map<BotPlayer, Revision> REVISIONS = new WeakHashMap<>();
    static final int PAGE = 40;

    private NetworkMenus() {}

    public static void register(Adapter adapter) { ADAPTERS.add(adapter); }

    public static Snapshot read(ServerPlayer player, AbstractContainerMenu menu) {
        var adapter = adapter(player, menu);
        return adapter == null ? null : adapter.read(player, menu);
    }

    /** The adapter handling this open menu, or null for an ordinary menu. */
    public static Adapter adapter(ServerPlayer player, AbstractContainerMenu menu) {
        for (var adapter : ADAPTERS) if (adapter.handles(menu)) return adapter;
        return null;
    }

    /**
     * The network section of {@code menu}, or null for an ordinary menu. Amounts stay exact decimal
     * text; craftable-only keys are listed apart and never counted as stock. A page token is bound to
     * the menu and the content revision, so a changed network makes old pages stale.
     */
    public static String section(BotPlayer bot, AbstractContainerMenu menu, String filter, String page) {
        var snapshot = read(bot, menu);
        if (snapshot == null) return null;
        if (snapshot.status() == Status.PENDING) return "network pending: terminal not synchronized yet; read menu again next tick";
        if (snapshot.status() == Status.DISCONNECTED) {
            REVISIONS.remove(bot);
            return "network disconnected" + (snapshot.reason().isEmpty() ? "" : ": " + snapshot.reason()) + "; contents unknown, not empty";
        }
        var sorted = new ArrayList<>(snapshot.entries());
        sorted.sort(Comparator.comparingLong(Entry::amount).reversed().thenComparing(Entry::key));
        int hash = sorted.stream().map(e -> e.key() + "=" + e.amount() + (e.craftable() ? "+" : "")).toList().hashCode();
        var last = REVISIONS.get(bot);
        int number = last == null || last.menuId() != menu.containerId ? 1 : last.hash() == hash ? last.number() : last.number() + 1;
        REVISIONS.put(bot, new Revision(menu.containerId, hash, number));
        String revision = menu.containerId + "." + number;
        if (snapshot.status() == Status.EMPTY) return "network empty (connected, nothing stored); revision " + revision;

        int offset = 0;
        if (page != null && !page.isBlank()) {
            int cut = page.lastIndexOf('.');
            if (cut < 0 || !page.substring(0, cut).equals(revision)) return "failed: stale network page (revision now " + revision + "); read menu again";
            try { offset = Integer.parseInt(page.substring(cut + 1)); } catch (NumberFormatException e) { return "failed: invalid network page"; }
        }
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        List<Entry> stored = new ArrayList<>(), craftOnly = new ArrayList<>();
        for (var entry : sorted) {
            if (!needle.isEmpty() && !entry.key().toLowerCase(Locale.ROOT).contains(needle)) continue;
            (entry.amount() > 0 ? stored : craftOnly).add(entry);
        }
        long kinds = snapshot.entries().stream().filter(e -> e.amount() > 0).count();
        var rows = new ArrayList<String>();
        rows.add("network ready; revision " + revision + "; stored kinds " + kinds
            + (needle.isEmpty() ? "" : "; matching " + stored.size()));
        int end = Math.min(stored.size(), offset + PAGE);
        for (int i = offset; i < end; i++) {
            var entry = stored.get(i);
            rows.add(entry.amount() + (entry.unit().isEmpty() ? "" : " " + entry.unit()) + " " + entry.key() + (entry.craftable() ? " (also craftable)" : ""));
        }
        if (end < stored.size()) rows.add("more: page " + revision + "." + end);
        if (offset == 0 && !craftOnly.isEmpty())
            rows.add("craftable only, none stored: " + String.join(", ", craftOnly.stream().limit(PAGE).map(Entry::key).toList())
                + (craftOnly.size() > PAGE ? " and " + (craftOnly.size() - PAGE) + " more" : ""));
        return String.join("; ", rows);
    }
}
