package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Getting somewhere through the pathing module, in the 9x9 arena. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PathingGameTests {
    private PathingGameTests() {}

    /** An unbreakable wall with one gap at the far end: the bot has to walk around it. */
    @GameTest(template = "arena", timeoutTicks = 600)
    public static void walksAroundWall(GameTestHelper helper) {
        TestArena.floor(helper);
        wall(helper, Blocks.BEDROCK, 0, 7);
        BotPlayer bot = TestArena.spawnBot(helper, "BnAround", new Vec3(1.5, 1, 1.5));
        BlockPos goal = helper.absolutePos(new BlockPos(7, 1, 1));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().equals(goal), "bot reached " + goal.toShortString() + ", is at " + bot.blockPosition().toShortString());
            for (int z = 0; z <= 7; z++) {
                helper.assertBlockPresent(Blocks.BEDROCK, new BlockPos(4, 1, z));
            }
            BotManager.despawn("BnAround");
        });
    }

    /** A goal one block up on a ledge: the bot jumps up. */
    @GameTest(template = "arena", timeoutTicks = 300)
    public static void climbsOntoLedge(GameTestHelper helper) {
        TestArena.floor(helper);
        for (int x = 5; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BotPlayer bot = TestArena.spawnBot(helper, "BnLedge", new Vec3(1.5, 1, 4.5));
        BlockPos goal = helper.absolutePos(new BlockPos(7, 2, 4));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().equals(goal), "bot reached the ledge, is at " + bot.blockPosition().toShortString());
            BotManager.despawn("BnLedge");
        });
    }

    /** No way around and nothing to build with: the bot digs through a dirt wall, at survival speed. */
    @GameTest(template = "arena", timeoutTicks = 800)
    public static void digsThroughWallWhenNoWayAround(GameTestHelper helper) {
        TestArena.floor(helper);
        wall(helper, Blocks.DIRT, 0, 8);
        BotPlayer bot = TestArena.spawnBot(helper, "BnDigThrough", new Vec3(1.5, 1, 4.5));
        BlockPos goal = helper.absolutePos(new BlockPos(7, 1, 4));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().equals(goal), "bot got through the wall, is at " + bot.blockPosition().toShortString());
            // Two dirt blocks by hand take at least 2 x 15 ticks.
            helper.assertTrue(helper.getTick() >= 30, "dug through too fast: tick " + helper.getTick());
            BotManager.despawn("BnDigThrough");
        });
    }

    /** A wall across x = 4, three blocks high, from z = fromZ to toZ. */
    private static void wall(GameTestHelper helper, Block block, int fromZ, int toZ) {
        for (int z = fromZ; z <= toZ; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(4, y, z), block);
            }
        }
    }
}
