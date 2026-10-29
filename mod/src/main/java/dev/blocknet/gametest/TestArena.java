package dev.blocknet.gametest;

import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.memory.WorldMemory;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
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
        // GameTestServer's bare PlayerList leaves this at zero, unlike a real server.
        if (helper.getLevel().getServer().getPlayerList().getViewDistance() == 0) {
            helper.getLevel().getServer().getPlayerList().setViewDistance(4);
        }
        BotPlayer bot = BotManager.spawn(helper.getLevel().getServer(), name, helper.getLevel(), helper.absoluteVec(relativePos), 0.0F);
        // Player data survives runs: a timed-out fixture can have starved while other tests finish.
        // Every fresh fixture starts healthy; tests of danger/starvation set their own state below.
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5);
        // Test worlds reuse SavedData files; world observations belong to this fixture only.
        bot.getServer().overworld().getDataStorage().set("blocknet_world_" + bot.getUUID(), new WorldMemory());
        // Fixtures were written for sight-only ores (hidden-ore pairs); full-perception tests opt in.
        bot.fullPerception(false);
        return bot;
    }

    /** Seed an existing fixture through an actual eye ray, without running a look task. */
    static void see(GameTestHelper helper, BotPlayer bot, BlockPos pos) {
        Vec3 point = Vec3.atCenterOf(helper.absolutePos(pos));
        Vec3 delta = point.subtract(bot.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance()));
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setXRot(pitch);
        WorldMemory.clip(bot, point, ClipContext.Block.VISUAL);
    }

    /** A block from another mod, if that mod is loaded. */
    static Optional<Block> modBlock(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
    }
}
