// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.*;
import dev.blocknet.bridge.BridgeTools;
import dev.blocknet.knowledge.Knowledge;
import dev.blocknet.vision.VoxelRay;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KnowledgeSkillGameTests {
    private static void call(BotPlayer bot, String tool, String item, int count, List<String> reports) {
        JsonObject args = new JsonObject(); args.addProperty("item", item);
        if (!tool.equals("knowledge")) args.addProperty("count", count);
        BridgeTools.run(bot, BridgeTools.get(tool), args, new HashMap<>(), reports::add);
    }
    private static void whenLit(GameTestHelper helper, BlockPos pos, Runnable action) {
        if (VoxelRay.visibleLight(helper.getLevel(), helper.absolutePos(pos)) >= 8) action.run();
        else helper.runAfterDelay(1, () -> whenLit(helper, pos, action));
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 100)
    public static void knowledgeReportsIronRangeAndHarvestTool(GameTestHelper helper) {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnKnowIron", new Vec3(4.5, 1, 4.5));
        var reports = new ArrayList<String>();
        call(bot, "knowledge", "iron_ore", 1, reports);
        var ore = Knowledge.get(bot.serverLevel()).ore(Items.IRON_ORE);
        helper.assertTrue(ore != null && ore.ranges().stream().anyMatch(r -> r.min() != null && r.min() <= 16 && r.max() >= 16), "iron includes y16");
        helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("needs stone_pickaxe") && reports.getFirst().contains("raw_iron"), reports.toString());
        Blocknet.LOGGER.info("KNOWLEDGE_SAMPLE {}", reports.getFirst());
        BotManager.despawn("BnKnowIron"); helper.succeed();
    }
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 2400)
    public static void obtainsWoodenPickaxeFromOnlyVisibleTree(GameTestHelper helper) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        for (int y = 1; y <= 5; y++) helper.setBlock(new BlockPos(24, y, 28), Blocks.BIRCH_LOG);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            helper.setBlock(new BlockPos(24+dx, 6, 28+dz), Blocks.BIRCH_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        var bot = TestArena.spawnBot(helper, "BnObtainWood", new Vec3(24.5, 1, 24.5));
        bot.getInventory().clearContent();
        var reports = new ArrayList<String>();
        whenLit(helper, new BlockPos(24, 2, 25), () -> call(bot, "obtain", "wooden_pickaxe", 1, reports));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("obtained 1 wooden_pickaxe"), "report: " + reports + " task=" + bot.controller().describe());
            helper.assertTrue(bot.getInventory().countItem(Items.WOODEN_PICKAXE) == 1 && helper.getTick() >= 150, "survival gathering and crafting");
            helper.assertTrue(reports.getFirst().contains("gathered") && reports.getFirst().contains("craft"), reports.toString());
            Blocknet.LOGGER.info("OBTAIN_SAMPLE ticks={} {}", helper.getTick(), reports.getFirst());
            BotManager.despawn("BnObtainWood");
        });
    }
    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 1400)
    public static void obtainsStonePickaxeByMiningUnderfoot(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) for (int y = 0; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        var bot = TestArena.spawnBot(helper, "BnObtainStone", new Vec3(4.5, 7, 1.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        // Recipe materials other than the three stone drops: no cobblestone supplied.
        bot.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 4));
        bot.getInventory().setItem(10, new ItemStack(Items.STICK, 2));
        var reports = new ArrayList<String>();
        whenLit(helper, new BlockPos(4, 8, 1), () -> call(bot, "obtain", "stone_pickaxe", 1, reports));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("obtained 1 stone_pickaxe"), "report: " + reports + " task=" + bot.controller().describe());
            helper.assertTrue(bot.getInventory().countItem(Items.STONE_PICKAXE) == 1 && helper.getTick() >= 70, "real stone mining");
            Blocknet.LOGGER.info("OBTAIN_SAMPLE ticks={} {}", helper.getTick(), reports.getFirst());
            BotManager.despawn("BnObtainStone");
        });
    }
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void unsupportedMobDropReturnsQuickly(GameTestHelper helper) {
        TestArena.floor(helper);
        var bot = TestArena.spawnBot(helper, "BnObtainMob", new Vec3(4.5, 1, 4.5));
        bot.getInventory().clearContent();
        var reports = new ArrayList<String>();
        call(bot, "obtain", "rotten_flesh", 1, reports);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("unsupported: mob drops:"), reports.toString());
            helper.assertTrue(helper.getTick() < 60, "unsupported returns promptly");
            BotManager.despawn("BnObtainMob");
        });
    }
    private static int underground(GameTestHelper helper) {
        int base = 16-helper.absolutePos(BlockPos.ZERO).getY();
        for (int x = 4; x <= 43; x++) for (int z = 3; z <= 43; z++) for (int y = base-2; y <= base+8; y++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        return base;
    }
    private static BotPlayer miner(GameTestHelper helper, String name, int x, int base, int height) {
        for (int y = base+height; y <= base+height+2; y++) helper.setBlock(new BlockPos(x, y, 7), Blocks.AIR);
        var bot = TestArena.spawnBot(helper, name, new Vec3(x+.5, base+height, 7.5));
        bot.getInventory().clearContent(); bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        return bot;
    }
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 2400)
    public static void minesOreRevealedOnlyAfterTunnelling(GameTestHelper helper) {
        int base = underground(helper);
        helper.setBlock(new BlockPos(10, base, 12), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(11, base, 12), Blocks.IRON_ORE);
        var bot = miner(helper, "BnMineOre", 10, base, 3);
        var reports = new ArrayList<String>();
        helper.runAfterDelay(30, () -> call(bot, "mine_ore", "iron_ore", 2, reports));
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1 && reports.getFirst().contains("mined 2 raw_iron at y=16"), "report: " + reports + " task=" + bot.controller().describe() + " pos=" + bot.blockPosition());
            helper.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) >= 2 && helper.getTick() >= 100, "real drops after survival tunnel");
            Blocknet.LOGGER.info("MINE_ORE_SAMPLE ticks={} {}", helper.getTick(), reports.getFirst());
            BotManager.despawn("BnMineOre");
        });
    }
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1600)
    public static void hiddenOreDoesNotChangeFirstTunnelBlocks(GameTestHelper helper) {
        int base = underground(helper);
        helper.setBlock(new BlockPos(10, base, 14), Blocks.IRON_ORE);
        var a = miner(helper, "BnOreLeakA", 10, base, 0);
        var b = miner(helper, "BnOreLeakB", 30, base, 0);
        var removedA = new ArrayList<BlockPos>(); var removedB = new ArrayList<BlockPos>();
        var reports = new ArrayList<String>();
        helper.runAfterDelay(30, () -> {
            call(a, "mine_ore", "raw_iron", 1, reports); call(b, "mine_ore", "raw_iron", 1, reports);
        });
        helper.succeedWhen(() -> {
            record(helper, 10, base, removedA); record(helper, 30, base, removedB);
            int n = 6;
            helper.assertTrue(removedA.size() >= n && removedB.size() >= n, "first six tunnel blocks opened: " + removedA + " / " + removedB + " reports=" + reports);
            helper.assertTrue(removedA.subList(0,n).equals(removedB.subList(0,n)), "identical tunnel sequence before disclosure: " + removedA + " / " + removedB);
            helper.assertTrue(a.getInventory().countItem(Items.RAW_IRON) == 0 && b.getInventory().countItem(Items.RAW_IRON) == 0, "ore still hidden when compared");
            Blocknet.LOGGER.info("ORE_LEAK_SAMPLE {} identical first blocks", n);
            BotManager.despawn("BnOreLeakA"); BotManager.despawn("BnOreLeakB");
        });
    }
    private static void record(GameTestHelper helper, int x, int base, List<BlockPos> removed) {
        for (int z = 8; z < 14; z++) for (int y = base; y <= base+5; y++) {
            BlockPos relative = new BlockPos(0, y-base, z-7);
            if (helper.getBlockState(new BlockPos(x,y,z)).isAir() && !removed.contains(relative)) removed.add(relative);
        }
    }
}
