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

package dev.blocknet.pathing;

import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.Settings;
import dev.blocknet.pathing.api.cache.IWorldProvider;
import dev.blocknet.pathing.api.event.listener.IEventBus;
import dev.blocknet.pathing.api.utils.IEntityContext;
import dev.blocknet.pathing.behavior.Behavior;
import dev.blocknet.pathing.behavior.InventoryBehavior;
import dev.blocknet.pathing.behavior.LookBehavior;
import dev.blocknet.pathing.behavior.MemoryBehavior;
import dev.blocknet.pathing.behavior.PathingBehavior;
import dev.blocknet.pathing.cache.WorldProvider;
import dev.blocknet.pathing.event.GameEventHandler;
import dev.blocknet.pathing.process.CustomGoalProcess;
import dev.blocknet.pathing.process.FollowProcess;
import dev.blocknet.pathing.utils.BlockStateInterface;
import dev.blocknet.pathing.utils.InputOverrideHandler;
import dev.blocknet.pathing.utils.PathingControlManager;
import dev.blocknet.pathing.utils.player.EntityContext;
import net.minecraft.world.entity.LivingEntity;

public class Baritone implements IBaritone {
   private final Settings settings;
   private final GameEventHandler gameEventHandler;
   private final PathingBehavior pathingBehavior;
   private final LookBehavior lookBehavior;
   private final MemoryBehavior memoryBehavior;
   private final InventoryBehavior inventoryBehavior;
   private final InputOverrideHandler inputOverrideHandler;
   private final FollowProcess followProcess;
   private final CustomGoalProcess customGoalProcess;
   private final PathingControlManager pathingControlManager;
   private final IEntityContext playerContext;
   public BlockStateInterface bsi;
   public AdditionalBaritoneSettings additionalBaritoneSettings = new AdditionalBaritoneSettings();

   public Baritone(LivingEntity player) {
      this.settings = new Settings();
      this.gameEventHandler = new GameEventHandler(this);
      this.playerContext = new EntityContext(player);
      this.pathingBehavior = new PathingBehavior(this);
      this.lookBehavior = new LookBehavior(this);
      this.memoryBehavior = new MemoryBehavior(this);
      this.inventoryBehavior = new InventoryBehavior(this);
      this.inputOverrideHandler = new InputOverrideHandler(this);
      this.pathingControlManager = new PathingControlManager(this);
      this.pathingControlManager.registerProcess(this.followProcess = new FollowProcess(this));
      this.pathingControlManager.registerProcess(this.customGoalProcess = new CustomGoalProcess(this));
   }

   public PathingControlManager getPathingControlManager() {
      return this.pathingControlManager;
   }

   public void registerBehavior(Behavior behavior) {
      this.gameEventHandler.registerEventListener(behavior);
   }

   public InputOverrideHandler getInputOverrideHandler() {
      return this.inputOverrideHandler;
   }

   public CustomGoalProcess getCustomGoalProcess() {
      return this.customGoalProcess;
   }

   @Override
   public IEntityContext getEntityContext() {
      return this.playerContext;
   }

   public MemoryBehavior getMemoryBehavior() {
      return this.memoryBehavior;
   }

   public FollowProcess getFollowProcess() {
      return this.followProcess;
   }

   public InventoryBehavior getInventoryBehavior() {
      return this.inventoryBehavior;
   }

   public LookBehavior getLookBehavior() {
      return this.lookBehavior;
   }

   public PathingBehavior getPathingBehavior() {
      return this.pathingBehavior;
   }

   public WorldProvider getWorldProvider() {
      return (WorldProvider)IWorldProvider.KEY.get(this.getEntityContext().world());
   }

   @Override
   public IEventBus getGameEventHandler() {
      return this.gameEventHandler;
   }

   @Override
   public boolean isActive() {
      return this.pathingControlManager.isActive();
   }

   @Override
   public Settings settings() {
      return this.settings;
   }

   public AdditionalBaritoneSettings getExtraBaritoneSettings() {
      return this.additionalBaritoneSettings;
   }

   @Override
   public void logDebug(String message) {
      Pathing.LOGGER.debug(message);
      Pathing.trace(this.playerContext.entity(), message);
   }

   @Override
   public void serverTick() {
      this.getGameEventHandler().onTickServer();
   }

}
