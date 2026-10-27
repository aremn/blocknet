package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPacketListener;
import dev.blocknet.body.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the bot body: run with {@code ./gradlew :mod:runGameTestServer}.
 *
 * <p>The arena template is empty air; each test lays a stone floor at y = 0 and stands the bot on it.
 */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BotGameTests {
    private BotGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void joinsAsRealPlayer(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnJoiner", new Vec3(4.5, 1, 4.5));
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(bot.getUUID()) == bot, "bot is in the player list");
            helper.assertTrue(bot.connection instanceof BotPacketListener, "bot uses its own packet listener");
            helper.assertTrue(bot.onGround(), "bot stands on the floor");
            BotManager.despawn("BnJoiner");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void walksToTarget(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, "BnWalker", new Vec3(1.5, 1, 1.5));
        BlockPos goal = helper.absolutePos(new BlockPos(7, 1, 7));
        bot.controller().goTo(goal, message -> {});
        helper.succeedWhen(() -> {
            helper.assertTrue(bot.blockPosition().closerThan(goal, 1.5), "bot reached " + goal.toShortString() + ", is at " + bot.blockPosition().toShortString());
            BotManager.despawn("BnWalker");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void minesDirtByHandAndPicksItUp(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos dirt = new BlockPos(4, 1, 4);
        helper.setBlock(dirt, Blocks.DIRT);
        BotPlayer bot = TestArena.spawnBot(helper, "BnDigger", new Vec3(1.5, 1, 4.5));
        bot.controller().mine(helper.absolutePos(dirt), message -> {});
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, dirt);
            helper.assertTrue(bot.getInventory().countItem(Items.DIRT) >= 1, "bot picked up the dirt");
            // Dirt by hand: hardness 0.5 -> 15 ticks of digging.
            helper.assertTrue(helper.getTick() >= 15, "dirt broke too fast: tick " + helper.getTick());
            BotManager.despawn("BnDigger");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void picksPickaxeForStone(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos stone = new BlockPos(4, 1, 4);
        helper.setBlock(stone, Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnMiner", new Vec3(1.5, 1, 4.5));
        bot.getInventory().setItem(5, new ItemStack(Items.WOODEN_PICKAXE));
        bot.controller().mine(helper.absolutePos(stone), message -> {});
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, stone);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) >= 1, "bot got cobblestone");
            // Stone with a wooden pickaxe: 23 ticks of digging.
            helper.assertTrue(helper.getTick() >= 23, "stone broke too fast: tick " + helper.getTick());
            BotManager.despawn("BnMiner");
        });
    }

    /** Honest rules: stone by hand takes ~7.5 s and drops nothing. */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void stoneByHandIsSlowAndDropsNothing(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos stone = new BlockPos(4, 1, 4);
        helper.setBlock(stone, Blocks.STONE);
        BotPlayer bot = TestArena.spawnBot(helper, "BnPuncher", new Vec3(1.5, 1, 4.5));
        // The high-level mine task now rejects missing tools. Keep proving the underlying
        // player code path's honest hand-breaking speed independently of that precondition.
        bot.getInventory().clearContent();
        var pos = helper.absolutePos(stone);
        bot.gameMode.handleBlockBreakAction(pos,
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
            net.minecraft.core.Direction.UP, helper.getLevel().getMaxBuildHeight(), 0);
        helper.runAfterDelay(150, () -> bot.gameMode.handleBlockBreakAction(pos,
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
            net.minecraft.core.Direction.UP, helper.getLevel().getMaxBuildHeight(), 1));
        helper.runAfterDelay(100, () -> helper.assertBlockPresent(Blocks.STONE, stone));
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, stone);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0, "no cobblestone without a pickaxe");
            // Stone by hand: 150 ticks of digging.
            helper.assertTrue(helper.getTick() >= 150, "stone broke too fast: tick " + helper.getTick());
            BotManager.despawn("BnPuncher");
        });
    }

    /** Leaves between the bot and its target: it breaks through them instead of giving up. */
    @GameTest(template = "arena", timeoutTicks = 300)
    public static void breaksLeavesInTheWay(GameTestHelper helper) {
        TestArena.floor(helper);
        BlockPos dirt = new BlockPos(4, 1, 4);
        helper.setBlock(dirt, Blocks.DIRT);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int z = 3; z <= 5; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(3, y, z), leaves);
            }
        }
        helper.setBlock(new BlockPos(4, 2, 4), leaves);
        BotPlayer bot = TestArena.spawnBot(helper, "BnLeaves", new Vec3(1.5, 1, 4.5));
        bot.controller().mine(helper.absolutePos(dirt), message -> {});
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.AIR, dirt);
            helper.assertTrue(bot.getInventory().countItem(Items.DIRT) >= 1, "bot picked up the dirt behind the leaves");
            BotManager.despawn("BnLeaves");
        });
    }
}
