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

package dev.blocknet.pathing.utils;

import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.entity.BotHands;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class ToolSet {
   // Blocknet: snapshot tools and player modifiers on the server thread. A* must never
   // change the live held slot to estimate a tool, or read changing potion/physics state.
   private final Map<BlockState, Double> breakStrengthCache = new HashMap<>();
   private final ItemStack[] tools;
   private final double[] efficiency;
   private final double[] speedMultiplier;
   private final int selectedSlot;
   private final LivingEntity player;
   private final IBaritone baritone;

   public ToolSet(LivingEntity player) {
      this.player = player;
      this.baritone = IBaritone.KEY.get(player);
      BotHands hands = BotHands.of(player);
      this.selectedSlot = hands.selectedSlot();
      this.tools = hands.main().stream().map(ItemStack::copy).toArray(ItemStack[]::new);
      this.efficiency = new double[this.tools.length];
      this.speedMultiplier = new double[this.tools.length];
      // Water and airborne slowdowns depend on where each block is mined, not where the bot
      // stands when the search starts, so the movements apply them (the 5x multipliers there).
      double effects = this.potionAmplifier();
      for (int slot = 0; slot < this.tools.length; slot++) {
         this.efficiency[slot] = attributeWithTool(Attributes.MINING_EFFICIENCY, tools[slot]);
         this.speedMultiplier[slot] = effects * attributeWithTool(Attributes.BLOCK_BREAK_SPEED, tools[slot]);
      }
   }

   // Blocknet: use the actual state and position for hardness, not the block's default state.
   public double getStrVsBlock(BlockState state, Level world, BlockPos pos) {
      float hardness = state.getDestroySpeed(world, pos);
      if (hardness < 0) return 0.0;
      return this.breakStrengthCache.computeIfAbsent(state, s ->
         speedVsBlock(getBestSlot(s, false, true), s) * avoidanceMultiplier(s.getBlock())) / hardness;
   }

   private double attributeWithTool(Holder<Attribute> attribute, ItemStack tool) {
      AttributeInstance snapshot = new AttributeInstance(attribute, ignored -> {});
      snapshot.replaceFrom(this.player.getAttribute(attribute));
      this.player.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
         if (type.equals(attribute)) snapshot.removeModifier(modifier);
      });
      tool.forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
         if (type.equals(attribute)) snapshot.addOrUpdateTransientModifier(modifier);
      });
      return snapshot.getValue();
   }

   private double speedVsBlock(int slot, BlockState state) {
      // Blocknet: Player.getDigSpeed / BlockBehaviour.getDestroyProgress (1.21.1):
      // tool + mining efficiency, haste/conduit, fatigue, break-speed attribute, then the
      // correct-tool divisor 30/100. Water and airborne slowdowns are left to the movements.
      ItemStack tool = tools[slot];
      double speed = tool.getDestroySpeed(state);
      if (speed > 1.0) speed += efficiency[slot];
      speed *= speedMultiplier[slot];
      return speed / (!state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state) ? 30.0 : 100.0);
   }

   private int getMaterialCost(ItemStack itemStack) {
      return itemStack.getItem() instanceof TieredItem ? 1 : -1;
   }

   public boolean hasSilkTouch(ItemStack stack) {
      return EnchantmentUtils.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH)!=0;
   }

   public int getBestSlot(Block b, boolean preferSilkTouch) {
      return this.getBestSlot(b, preferSilkTouch, false);
   }

   public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {
      return getBestSlot(b.defaultBlockState(), preferSilkTouch, pathingCalculation);
   }

   // Blocknet: calculation and execution choose the same tool, including the main inventory.
   public int getBestSlot(BlockState blockState, boolean preferSilkTouch, boolean pathingCalculation) {
      if (blockState.getBlock().defaultDestroyTime() == 0.0F) {
         return selectedSlot;
      } else if (this.baritone.settings().disableAutoTool.get() && pathingCalculation) {
         return selectedSlot;
      } else {
         int best = 0;
         double highestSpeed = Double.NEGATIVE_INFINITY;
         int lowestCost = Integer.MIN_VALUE;
         boolean bestSilkTouch = false;
         int slots = this.baritone.settings().allowInventory.get() ? tools.length : 9;
         for (int i = 0; i < slots; i++) {
            ItemStack itemStack = tools[i];
            if ((this.baritone.settings().useSwordToMine.get() || !(itemStack.getItem() instanceof SwordItem))
               && (!this.baritone.settings().itemSaver.get() || itemStack.getDamageValue() < itemStack.getMaxDamage() || itemStack.getMaxDamage() <= 1)) {
               double speed = speedVsBlock(i, blockState);
               boolean silkTouch = this.hasSilkTouch(itemStack);
               if (speed > highestSpeed) {
                  highestSpeed = speed;
                  best = i;
                  lowestCost = this.getMaterialCost(itemStack);
                  bestSilkTouch = silkTouch;
               } else if (speed == highestSpeed) {
                  int cost = this.getMaterialCost(itemStack);
                  if (cost < lowestCost && (silkTouch || !bestSilkTouch) || preferSilkTouch && !bestSilkTouch && silkTouch) {
                     highestSpeed = speed;
                     best = i;
                     lowestCost = cost;
                     bestSilkTouch = silkTouch;
                  }
               }
            }
         }

         return best;
      }
   }

   private double avoidanceMultiplier(Block b) {
      return this.baritone.settings().blocksToAvoidBreaking.get().contains(b.builtInRegistryHolder().value()) ? 0.1 : 1.0;
   }

   public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
      float hardness = state.getDestroySpeed(null, null);
      if (hardness < 0.0F) {
         return -1.0;
      } else {
         float speed = item.getDestroySpeed(state);
         if (speed > 1.0F) {
            int effLevel = EnchantmentUtils.getEnchantmentLevel(item, Enchantments.EFFICIENCY);
            if (effLevel > 0 && !item.isEmpty()) {
               speed += effLevel * effLevel + 1;
            }
         }

         speed /= hardness;
         return state.requiresCorrectToolForDrops() && (item.isEmpty() || !item.isCorrectToolForDrops(state)) ? speed / 100.0F : speed / 30.0F;
      }
   }

   private double potionAmplifier() {
      double speed = 1.0;
      if (MobEffectUtil.hasDigSpeed(this.player)) {
         speed *= 1.0 + (MobEffectUtil.getDigSpeedAmplification(this.player) + 1) * 0.2;
      }

      MobEffectInstance fatigueEffect = this.player.getEffect(MobEffects.DIG_SLOWDOWN);
      if (fatigueEffect != null) {
         switch (fatigueEffect.getAmplifier()) {
            case 0:
               speed *= 0.3;
               break;
            case 1:
               speed *= 0.09;
               break;
            case 2:
               speed *= 0.0027;
               break;
            default:
               speed *= 8.1E-4;
         }
      }

      return speed;
   }
}
