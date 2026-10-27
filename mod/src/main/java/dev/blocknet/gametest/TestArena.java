package dev.blocknet.gametest;

import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Shared setup for tests in the empty 9x5x9 "arena" template. */
final class TestArena {
    private TestArena() {}

    /** Lays a stone floor at y = 0 so bots stand at y = 1. */
    static void floor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    static BotPlayer spawnBot(GameTestHelper helper, String name, Vec3 relativePos) {
        return BotManager.spawn(helper.getLevel().getServer(), name, helper.getLevel(), helper.absoluteVec(relativePos), 0.0F);
    }

    /** A block from another mod, if that mod is loaded. */
    static Optional<Block> modBlock(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
    }
}
