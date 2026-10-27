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

package dev.blocknet.pathing.pathing.path;

import dev.blocknet.pathing.api.pathing.calc.IPath;
import dev.blocknet.pathing.api.pathing.goals.Goal;
import dev.blocknet.pathing.api.pathing.movement.IMovement;
import dev.blocknet.pathing.api.utils.BetterBlockPos;
import dev.blocknet.pathing.utils.pathing.PathBase;
import java.util.Collections;
import java.util.List;

public class CutoffPath extends PathBase {
   private final List<BetterBlockPos> path;
   private final List<IMovement> movements;
   private final int numNodes;
   private final Goal goal;

   public CutoffPath(IPath prev, int firstPositionToInclude, int lastPositionToInclude) {
      this.path = prev.positions().subList(firstPositionToInclude, lastPositionToInclude + 1);
      this.movements = prev.movements().subList(firstPositionToInclude, lastPositionToInclude);
      this.numNodes = prev.getNumNodesConsidered();
      this.goal = prev.getGoal();
      this.sanityCheck();
   }

   public CutoffPath(IPath prev, int lastPositionToInclude) {
      this(prev, 0, lastPositionToInclude);
   }

   @Override
   public Goal getGoal() {
      return this.goal;
   }

   @Override
   public List<IMovement> movements() {
      return Collections.unmodifiableList(this.movements);
   }

   @Override
   public List<BetterBlockPos> positions() {
      return Collections.unmodifiableList(this.path);
   }

   @Override
   public int getNumNodesConsidered() {
      return this.numNodes;
   }
}
