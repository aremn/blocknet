// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReflexGameTests {
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void hungryBotEatsOnItsOwnAndKeepsItsTool(GameTestHelper h) {
        TestArena.floor(h);
        var bot = TestArena.spawnBot(h, "BnHungry", new Vec3(4.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().setItem(5, new ItemStack(Items.ROTTEN_FLESH, 4));
        bot.getInventory().setItem(6, new ItemStack(Items.BREAD, 3));
        bot.getInventory().selected = 0;
        bot.getFoodData().setFoodLevel(8);
        h.succeedWhen(() -> {
            h.assertTrue(bot.getInventory().countItem(Items.BREAD) < 3, "ate bread without being asked");
            h.assertTrue(bot.getInventory().countItem(Items.ROTTEN_FLESH) == 4, "never eats harmful food by reflex");
            h.assertTrue(!bot.isUsingItem() && bot.getMainHandItem().is(Items.IRON_PICKAXE), "tool back in hand after the meal");
            h.assertTrue(bot.getFoodData().getFoodLevel() > 8, "food went up");
            BotManager.despawn("BnHungry");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 300)
    public static void drowningBotSwimsUpToAir(GameTestHelper h) {
        TestArena.floor(h);
        // A 3x3 well, four blocks of water deep, walled in stone, open to the sky.
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) for (int y = 1; y <= 4; y++) {
            boolean wall = x == 3 || x == 7 || z == 3 || z == 7;
            h.setBlock(new net.minecraft.core.BlockPos(x, y, z), wall ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.WATER);
        }
        var bot = TestArena.spawnBot(h, "BnDrowning", new Vec3(5.5, 1, 5.5));
        bot.setAirSupply(60);
        String[] seen = {null};
        h.onEachTick(() -> { if (bot.controller().escaping() != null) seen[0] = bot.controller().escaping(); });
        h.succeedWhen(() -> {
            h.assertTrue("air".equals(seen[0]), "the air reflex took over: " + seen[0]);
            h.assertTrue(!bot.isUnderWater(), "head above water");
            h.assertTrue(bot.isAlive() && bot.getHealth() > 10, "came up before drowning: health " + bot.getHealth());
            BotManager.despawn("BnDrowning");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void botWalksOutOfLava(GameTestHelper h) {
        TestArena.floor(h);
        for (int x = 4; x <= 5; x++) for (int z = 4; z <= 5; z++) h.setBlock(new net.minecraft.core.BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.LAVA);
        var bot = TestArena.spawnBot(h, "BnLava", new Vec3(4.5, 0.2, 4.5));
        String[] seen = {null};
        h.onEachTick(() -> { if (bot.controller().escaping() != null) seen[0] = bot.controller().escaping(); });
        h.succeedWhen(() -> {
            h.assertTrue("lava".equals(seen[0]), "the lava reflex took over: " + seen[0]);
            h.assertTrue(!bot.isInLava(), "out of the lava");
            h.assertTrue(bot.isAlive(), "alive");
            BotManager.despawn("BnLava");
        });
    }

    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 1200)
    public static void gatheringThisTreeLeavesTheNextOneStanding(GameTestHelper h) {
        TestArena.floor(h);
        for (int y = 1; y <= 3; y++) {
            h.setBlock(new net.minecraft.core.BlockPos(3, y, 6), net.minecraft.world.level.block.Blocks.OAK_LOG);
            h.setBlock(new net.minecraft.core.BlockPos(7, y, 6), net.minecraft.world.level.block.Blocks.OAK_LOG);
        }
        var bot = TestArena.spawnBot(h, "BnOneTree", new Vec3(4.5, 1, 3.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE));
        for (int y = 1; y <= 3; y++) {
            TestArena.see(h, bot, new net.minecraft.core.BlockPos(3, y, 6));
            TestArena.see(h, bot, new net.minecraft.core.BlockPos(7, y, 6));
        }
        var first = Vec3.atCenterOf(h.absolutePos(new net.minecraft.core.BlockPos(3, 2, 6)));
        String ref = bot.vision().objects().stream().filter(o -> o.category().equals("tree") && o.bounds().inflate(0.5).contains(first))
            .findFirst().orElseThrow().ref();
        var reports = new java.util.ArrayList<String>();
        var args = new com.google.gson.JsonObject();
        args.addProperty("ref", ref);
        dev.blocknet.bridge.BridgeTools.run(bot, dev.blocknet.bridge.BridgeTools.get("gather"), args, new java.util.HashMap<>(), reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1, "gather finished: " + reports);
            h.assertTrue(reports.getFirst().contains("gathered 3") && reports.getFirst().contains("the whole"), "one whole tree: " + reports);
            for (int y = 1; y <= 3; y++) h.assertBlockPresent(net.minecraft.world.level.block.Blocks.OAK_LOG, new net.minecraft.core.BlockPos(7, y, 6));
            BotManager.despawn("BnOneTree");
        });
    }
}
