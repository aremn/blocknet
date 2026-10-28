// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** 32 x 48 x 32 column; local y=26 represents iron's low worldgen peak, y=10 sea-level zero. */
final class NaturalTerrain {
    static final int SURFACE = 45, PEAK = 26;
    private NaturalTerrain() {}

    static void build(GameTestHelper h, int offset, boolean iron) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 0; y < 48; y++) {
            var block = y >= SURFACE ? Blocks.AIR : y == SURFACE-1 ? Blocks.GRASS_BLOCK
                : y >= SURFACE-5 ? Blocks.DIRT : y < 10 ? Blocks.DEEPSLATE : Blocks.STONE;
            h.setBlock(new BlockPos(offset+x, y, z), block);
        }
        h.setBlock(new BlockPos(offset+16, SURFACE, 5), Blocks.SHORT_GRASS);
        h.setBlock(new BlockPos(offset+17, 39, 7), Blocks.COAL_ORE);
        h.setBlock(new BlockPos(offset+17, 38, 7), Blocks.COAL_ORE);
        // A sealed pocket intersects the south-facing staircase; it is initially invisible.
        for (int x = 15; x <= 17; x++) for (int y = 36; y <= 38; y++) for (int z = 10; z <= 11; z++)
            h.setBlock(new BlockPos(offset+x, y, z), Blocks.WATER);
        // An air cave below the likely bypass. Its floor must be observed before entering.
        for (int x = 10; x <= 15; x++) for (int y = 30; y <= 32; y++) for (int z = 13; z <= 15; z++)
            h.setBlock(new BlockPos(offset+x, y, z), Blocks.AIR);
        if (iron) {
            // Buried veins at the scaled peak, off the main tunnel, reached by side branches.
            for (int x = 3; x <= 28; x++) for (int z = 19; z <= 27; z++)
                if (x >= 6 && x <= 8 || x >= 23 && x <= 25)
                    h.setBlock(new BlockPos(offset+x, PEAK, z), Blocks.IRON_ORE);
        }
    }
}
