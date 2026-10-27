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

package dev.blocknet.pathing.cache;

import dev.blocknet.pathing.api.cache.ICachedWorld;
import dev.blocknet.pathing.api.cache.IWaypointCollection;
import dev.blocknet.pathing.api.cache.IWorldData;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.HashSet;

public class WorldData implements IWorldData {
   private final WaypointCollection waypoints = new WaypointCollection();
   public final ResourceKey<Level> dimension;
   private final HashSet<Long> localChunkCache = new HashSet<>();

   WorldData(ResourceKey<Level> dimension) {
      this.dimension = dimension;
   }

   public void readFromNbt(HolderLookup.Provider levelRegistryAccess, CompoundTag tag) {
      this.waypoints.readFromNbt(tag.getCompound("waypoints"));
   }

   public void writeToNbt(HolderLookup.Provider levelRegistryAccess, CompoundTag tag) {
      tag.put("waypoints", this.waypoints.toNbt());
   }

   @Override
   public ICachedWorld getCachedWorld() {
      return new ICachedWorld() {
         @Override
         public boolean isCached(int blockX, int blockZ) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long key = ChunkPos.asLong(chunkX, chunkZ);
            return localChunkCache.contains(key);
         }

         @Override
         public ArrayList<BlockPos> getLocationsOf(String block, int maximum, int centerX, int centerZ,
               int maxRegionDistanceSq) {
            return new ArrayList<>();
         }
      };
   }

   @Override
   public IWaypointCollection getWaypoints() {
      return this.waypoints;
   }

   @Override
   public void addBlockPosToCache(int blockX, int blockZ) {
      int chunkX = blockX >> 4;
      int chunkZ = blockZ >> 4;
      addChunkPosToCache(chunkX, chunkZ);
   }

   public void addChunkPosToCache(int chunkX, int chunkZ) {
      long key = ChunkPos.asLong(chunkX, chunkZ);
      this.localChunkCache.add(key);
   }
}
