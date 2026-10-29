// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Walk, face the recipient, then toss selected stacks exactly as the Q key does. */
final class GiveTask extends SkillTask {
    private final ServerPlayer recipient;
    private final String query;
    private final Item item;
    private final int requested;
    private int given, available;
    private boolean approached;
    private int settled;
    private final net.minecraft.core.BlockPos departure;
    private boolean backedAway;

    GiveTask(BotPlayer bot, @Nullable ServerPlayer recipient, String query, int count, Consumer<String> report) {
        super(bot, report, 20 * 60 * 5);
        this.recipient = recipient;
        this.query = query;
        this.requested = count;
        departure = bot.blockPosition().immutable();
        ResourceLocation id = ResourceLocation.tryParse(query);
        item = query.equals("all") ? null : id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        for (var stack : bot.getInventory().items) if (matches(stack)) available += stack.getCount();
    }

    private boolean matches(ItemStack stack) { return !stack.isEmpty() && (query.equals("all") || item != null && stack.is(item)); }
    @Override String describe() { return (recipient == null ? "dropping " : "giving ") + query + ": " + given + (recipient == null ? "" : " to " + recipient.getScoreboardName()); }

    @Override void work() {
        if (busy) return;
        if (given >= (requested > 0 ? requested : available) && given > 0) {
            if (recipient != null && !backedAway) {
                backedAway = true;
                action(cb -> controller.goTo(departure, cb), ignored -> {}); return;
            }
            complete((recipient == null ? "dropped " : "gave ") + given + " " + query
                + (recipient == null ? "" : " to " + recipient.getScoreboardName())); return;
        }
        if (!query.equals("all") && item == null) { complete("failed: unknown item " + query); return; }
        if (available == 0 || requested > 0 && available < requested) { complete("failed: not enough " + query + " in inventory"); return; }
        if (recipient != null) {
            if (recipient == bot || !recipient.isAlive() || recipient.isRemoved() || recipient.serverLevel() != bot.serverLevel()) {
                complete("failed: recipient unavailable"); return;
            }
            if (!approached) {
                controller.trace("give", "walking next to " + recipient.getScoreboardName() + " before tossing " + query);
                action(cb -> controller.approachRecipient(recipient, cb), message -> {
                    if (failed(message)) complete(message);
                    else { approached = true; settled = 0; }
                });
                return;
            }
            if (bot.position().subtract(recipient.position()).horizontalDistance() > 4) { approached = false; return; }
            if (!bot.hasLineOfSight(recipient)) { complete("failed: recipient is behind an obstacle"); return; }
            // For a player on a raised block, land on the platform rather than throwing over
            // their head during vanilla's pickup delay. Ground-level throws keep the normal arc.
            var aim = bot.position().subtract(recipient.position()).horizontalDistance() < 1.2
                ? bot.getEyePosition().add(0, -4, 0) : recipient.getEyePosition();
            if (!controller.aimForToss(aim)) return;
            if (!bot.onGround() || ++settled < 10) return;
        }
        int remaining = (requested > 0 ? requested : available) - given;
        if (remaining <= 0) {
            complete((recipient == null ? "dropped " : "gave ") + given + " " + query
                + (recipient == null ? "" : " to " + recipient.getScoreboardName()));
            return;
        }
        var inventory = bot.getInventory();
        int slot = -1;
        for (int i = 0; i < inventory.items.size(); i++) if (matches(inventory.getItem(i))) { slot = i; break; }
        if (slot < 0) { complete("failed: inventory changed; " + describe()); return; }
        if (slot < 9) inventory.selected = slot;
        else bot.inventoryMenu.clicked(slot, inventory.selected, ClickType.SWAP, bot);
        ItemStack selected = inventory.getSelected();
        int amount = Math.min(remaining, selected.getCount());
        // Keep the remainder in inventory, including when a mod refuses/cancels the toss.
        ItemStack remainder = selected.split(selected.getCount() - amount);
        controller.trace("give", "Q-key toss of " + amount + " " + BuiltInRegistries.ITEM.getKey(selected.getItem()));
        boolean dropped = bot.drop(true);
        if (!remainder.isEmpty()) inventory.add(remainder);
        bot.inventoryMenu.broadcastChanges();
        if (!dropped) { complete("failed: item toss refused; " + describe()); return; }
        given += amount;
    }
}
