package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.Settings;
import dev.blocknet.pathing.behavior.InventoryBehavior;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Navigation commands, their increasingly costly retries and their single final report. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ComeGameTests {
    private ComeGameTests() {}

    @GameTest(template = "arena_tall", timeoutTicks = 600)
    public static void comesToGroundBelowFlyingPlayer(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = spawn(helper, "BnComeFly", new Vec3(1.5, 1, 4.5));
        List<String> reports = command(helper, "blocknet come", new Vec3(7.5, 4, 4.5));
        BlockPos groundSpot = helper.absolutePos(new BlockPos(7, 1, 4));
        helper.succeedWhen(() -> {
            assertFinished(helper, bot, reports, false);
            BlockPos feet = bot.blockPosition();
            double dx = feet.getX() - groundSpot.getX();
            double dz = feet.getZ() - groundSpot.getZ();
            helper.assertTrue(feet.getY() == groundSpot.getY() && dx * dx + dz * dz <= 4,
                "came near the ground spot, is at " + feet);
            BotManager.despawn("BnComeFly");
        });
    }

    /** The player stands in short grass, as on most of the overworld: the grass is room, not ground. */
    @GameTest(template = "arena_tall", timeoutTicks = 600)
    public static void comesToPlayerStandingInGrass(GameTestHelper helper) {
        // Grass needs dirt under it, or it pops off as soon as its neighbours update.
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.SHORT_GRASS);
            }
        }
        helper.assertBlockPresent(Blocks.SHORT_GRASS, new BlockPos(7, 1, 4));
        BotPlayer bot = spawn(helper, "BnComeGrass", new Vec3(1.5, 1, 4.5));
        List<String> reports = command(helper, "blocknet come", new Vec3(7.5, 1, 4.5));
        BlockPos groundSpot = helper.absolutePos(new BlockPos(7, 1, 4));
        helper.succeedWhen(() -> {
            assertFinished(helper, bot, reports, false);
            BlockPos feet = bot.blockPosition();
            double dx = feet.getX() - groundSpot.getX();
            double dz = feet.getZ() - groundSpot.getZ();
            helper.assertTrue(feet.getY() == groundSpot.getY() && dx * dx + dz * dz <= 4,
                "came near the player in the grass, is at " + feet + ", said " + reports);
            BotManager.despawn("BnComeGrass");
        });
    }

    @GameTest(template = "arena_tall", timeoutTicks = 1200)
    public static void pillarsToPlatformWithInventoryBlocks(GameTestHelper helper) {
        platform(helper);
        BotPlayer bot = spawn(helper, "BnComeBuild", new Vec3(1.5, 1, 4.5));
        // Main inventory, not the hotbar: calculation and actual placement must agree.
        bot.getInventory().setItem(20, new ItemStack(Blocks.DIRT, 16));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 4, 4));
        List<String> reports = goTo(helper, goal, "BnComeBuild");
        helper.succeedWhen(() -> {
            assertFinished(helper, bot, reports, false);
            helper.assertTrue(bot.blockPosition().equals(goal), "arrived on top of the platform: " + bot.blockPosition());
            helper.assertTrue(bot.getInventory().countItem(Blocks.DIRT.asItem()) < 16, "placed inventory blocks to climb");
            helper.assertTrue(bot.onGround(), "standing on the platform");
            BotManager.despawn("BnComeBuild");
        });
    }

    @GameTest(template = "arena_tall", timeoutTicks = 1200)
    public static void getsCloseToPlatformWithoutBlocks(GameTestHelper helper) {
        platform(helper);
        BotPlayer bot = spawn(helper, "BnComeClose", new Vec3(1.5, 1, 4.5));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 4, 4));
        List<String> reports = goTo(helper, goal, "BnComeClose");
        helper.succeedWhen(() -> {
            assertFinished(helper, bot, reports, true);
            helper.assertTrue(reports.getFirst().contains("got as close as I could"), reports.toString());
            BlockPos feet = bot.blockPosition();
            BlockPos nextToPlatform = helper.absolutePos(new BlockPos(4, 1, 4));
            helper.assertTrue(feet.equals(nextToPlatform), "walked next to the platform, is at " + feet);
            BotManager.despawn("BnComeClose");
        });
    }

    @GameTest(template = "arena_tall", timeoutTicks = 800)
    public static void healthyBotDropsOffFourHighLogWall(GameTestHelper helper) {
        TestArena.floor(helper);
        for (int z = 0; z < 9; z++) {
            for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(4, y, z), Blocks.OAK_LOG);
            }
        }
        BotPlayer bot = spawn(helper, "BnComeDrop", new Vec3(4.5, 5, 4.5));
        // Isolate the fall strategy: neither dig down through the wall nor leave via its ends.
        IBaritone.KEY.get(bot).settings().allowBreak.set(false);
        AtomicBoolean usedHigherFall = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (IBaritone.KEY.get(bot).settings().maxFallHeightNoWater.get() == 5) {
                usedHigherFall.set(true);
            }
        });
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 4));
        List<String> reports = goTo(helper, goal, "BnComeDrop");
        helper.succeedWhen(() -> {
            assertFinished(helper, bot, reports, false);
            helper.assertTrue(bot.blockPosition().equals(goal), "dropped to the floor and arrived: " + bot.blockPosition());
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(4, 4, 4));
            helper.assertTrue(usedHigherFall.get(), "retried with a higher fall limit");
            BotManager.despawn("BnComeDrop");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 40)
    public static void buildingBlocksUseTagsAndExcludeUnsafeBlocks(GameTestHelper helper) {
        Settings settings = new Settings();
        helper.assertTrue(!settings.acceptableThrowawayItems.get().contains(Blocks.COBBLESTONE.asItem()), "cobblestone exercises the tag path");
        for (var block : List.of(Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.SANDSTONE, Blocks.DIRT)) {
            helper.assertTrue(InventoryBehavior.isAcceptableThrowaway(new ItemStack(block), settings), "tagged building block: " + block);
        }
        // Explicitly whitelist unsafe blocks too: exclusions must win over the old list.
        settings.acceptableThrowawayItems.get().addAll(List.of(Blocks.SAND.asItem(), Blocks.GRAVEL.asItem(), Blocks.CHEST.asItem()));
        for (var block : List.of(Blocks.SAND, Blocks.GRAVEL, Blocks.CHEST)) {
            helper.assertTrue(!InventoryBehavior.isAcceptableThrowaway(new ItemStack(block), settings), "unsafe building block: " + block);
        }
        helper.succeed();
    }

    private static BotPlayer spawn(GameTestHelper helper, String name, Vec3 pos) {
        BotPlayer bot = TestArena.spawnBot(helper, name, pos);
        // Player data persists between runs; every navigation test starts with the stated resources.
        bot.getInventory().clearContent();
        bot.setHealth(bot.getMaxHealth());
        return bot;
    }

    private static void platform(GameTestHelper helper) {
        TestArena.floor(helper);
        // Unbreakable so the no-blocks case cannot dig a staircase instead.
        for (int x = 5; x <= 7; x++) {
            for (int z = 3; z <= 5; z++) {
                for (int y = 1; y <= 3; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.BEDROCK);
                }
            }
        }
    }

    private static List<String> goTo(GameTestHelper helper, BlockPos goal, String name) {
        return command(helper, "blocknet goto " + name + " " + goal.getX() + " " + goal.getY() + " " + goal.getZ(), new Vec3(1.5, 1, 4.5));
    }

    private static List<String> command(GameTestHelper helper, String command, Vec3 sourcePos) {
        List<String> reports = new ArrayList<>();
        CommandSource output = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { reports.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
            .withSource(output).withLevel(helper.getLevel()).withPosition(helper.absoluteVec(sourcePos));
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
        return reports;
    }

    private static void assertFinished(GameTestHelper helper, BotPlayer bot, List<String> reports, boolean failed) {
        helper.assertTrue(reports.size() == 1, "one final report: " + reports);
        helper.assertTrue(reports.getFirst().contains(failed ? "failed:" : "arrived at"), reports.toString());
        helper.assertTrue(bot.controller().describe().startsWith("idle"), "task finished");
        Settings settings = IBaritone.KEY.get(bot).settings();
        helper.assertTrue(!settings.allowPlace.get() && settings.maxFallHeightNoWater.get() == 3, "attempt settings restored");
    }
}
