/*
 * This file is part of Blocknet's pathing module, derived from Baritone/Automatone.
 *
 * It is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 */

package dev.blocknet.pathing.api.entity;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** {@link BotHands} backed directly by a server player's inventory. */
public final class PlayerHands implements BotHands {
    private final ServerPlayer player;

    public PlayerHands(ServerPlayer player) {
        this.player = player;
    }

    private Inventory inventory() {
        return player.getInventory();
    }

    @Override
    public NonNullList<ItemStack> main() {
        return inventory().items;
    }

    @Override
    public NonNullList<ItemStack> offHand() {
        return inventory().offhand;
    }

    @Override
    public int selectedSlot() {
        return inventory().selected;
    }

    @Override
    public void selectSlot(int slot) {
        if (BotHands.isValidHotbarIndex(slot)) {
            inventory().selected = slot;
        }
    }

    @Override
    public boolean insertStack(ItemStack stack) {
        return inventory().add(stack);
    }
}
