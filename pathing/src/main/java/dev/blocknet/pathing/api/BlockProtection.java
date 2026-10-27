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

package dev.blocknet.pathing.api;

import net.minecraft.core.BlockPos;

/**
 * Blocks the pathfinder must not break or replace (for example what players built). The host mod
 * installs its implementation with {@link #install}; until then nothing is protected.
 */
@FunctionalInterface
public interface BlockProtection {
    BlockProtection NONE = (dimension, pos) -> false;

    /**
     * @param dimension the dimension id, like {@code minecraft:overworld}
     * @param pos       read synchronously; callers may reuse a mutable position
     */
    boolean isProtected(String dimension, BlockPos pos);

    static BlockProtection current() {
        return Holder.current;
    }

    static void install(BlockProtection protection) {
        Holder.current = protection == null ? NONE : protection;
    }

    final class Holder {
        private static volatile BlockProtection current = NONE;

        private Holder() {}
    }
}
