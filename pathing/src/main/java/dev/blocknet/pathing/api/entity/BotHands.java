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

import javax.annotation.Nullable;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The inventory the pathfinder sees and changes: which hotbar slot is held, what tools and blocks
 * are available. Replaces PlayerEngine's separate mob inventory; for a player it is the player's
 * own inventory, so items and the held slot are the real ones.
 */
public interface BotHands {
    int HOTBAR_SIZE = 9;

    /** All main slots (36 for a player); 0-8 are the hotbar. */
    NonNullList<ItemStack> main();

    /** One slot. */
    NonNullList<ItemStack> offHand();

    int selectedSlot();

    void selectSlot(int slot);

    default ItemStack getItem(int slot) {
        return main().get(slot);
    }

    default void setItem(int slot, ItemStack stack) {
        main().set(slot, stack);
    }

    /** Puts the stack into the inventory like picking it up; false if it did not fully fit. */
    boolean insertStack(ItemStack stack);

    static boolean isValidHotbarIndex(int slot) {
        return slot >= 0 && slot < HOTBAR_SIZE;
    }

    /** The hands of an entity the pathfinder can drive, or null if it has none. */
    @Nullable
    static BotHands of(@Nullable LivingEntity entity) {
        return entity instanceof ServerPlayer player ? new PlayerHands(player) : null;
    }
}
