// SPDX-License-Identifier: MIT
package dev.blocknet.mixin;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Values the open menu normally synchronizes to a player's screen, never hidden machine data. */
@Mixin(AbstractContainerMenu.class)
public interface MenuDataAccessor {
    @Accessor("dataSlots") List<DataSlot> blocknet$dataSlots();
}
