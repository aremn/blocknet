/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.blocknet.pathing.api.utils;

import dev.blocknet.pathing.api.component.EntityComponentKey;
import dev.blocknet.pathing.utils.player.PlayerInteractionController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

public interface IInteractionController {
   EntityComponentKey<IInteractionController> KEY = new EntityComponentKey<>(entity -> {
      if (entity instanceof ServerPlayer player) {
         return new PlayerInteractionController(player);
      }
      throw new IllegalArgumentException("Blocknet pathing drives server players only: " + entity);
   });

   boolean hasBrokenBlock();

   boolean onPlayerDamageBlock(BlockPos var1, Direction var2);

   void resetBlockRemoving();

   GameType getGameType();

   InteractionResult processRightClickBlock(LivingEntity var1, Level var2, InteractionHand var3, BlockHitResult var4);

   InteractionResult processRightClick(LivingEntity var1, Level var2, InteractionHand var3);

   boolean clickBlock(BlockPos var1, Direction var2);

   void setHittingBlock(boolean var1);

   double getBlockReachDistance();
}
