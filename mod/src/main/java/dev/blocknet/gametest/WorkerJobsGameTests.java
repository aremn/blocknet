// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The worker's own jobs (ADR-0016): one order, real player actions, one honest report. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkerJobsGameTests {
    private WorkerJobsGameTests() {}

    private static BotPlayer bot(GameTestHelper h, String name, Vec3 at, ItemStack... items) {
        TestArena.floor(h);
        BotPlayer bot = TestArena.spawnBot(h, name, at);
        bot.getInventory().clearContent();
        for (ItemStack item : items) bot.getInventory().add(item.copy());
        return bot;
    }

    /**
     * "Переплавь руду": 30 raw iron is shared between three furnaces (one carried, two crafted from
     * cobblestone), which are taken back afterwards; the 3 raw copper goes through one furnace, the
     * stack dropped into it with one click. One furnace would have needed 300 s for the iron alone.
     */
    @GameTest(template = "arena", timeoutTicks = 6000)
    public static void smeltsTheRawOreInTheBag(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnJobSmelt", new Vec3(4.5, 1, 4.5), new ItemStack(Items.RAW_IRON, 30), new ItemStack(Items.RAW_COPPER, 3),
            new ItemStack(Items.COAL, 8), new ItemStack(Items.FURNACE), new ItemStack(Items.COBBLESTONE, 16),
            new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.STONE_PICKAXE));
        List<String> reports = new ArrayList<>();
        bot.controller().smelt(null, reports::add);
        // The most ore that went into one furnace's input slot in one tick: a player drops the stack at once.
        java.util.Map<BlockPos, Integer> lastInput = new java.util.HashMap<>();
        int[] biggestLoad = {0}, mostFurnaces = {0};
        h.onEachTick(() -> {
            int furnaces = 0;
            for (BlockPos pos : BlockPos.betweenClosed(h.absolutePos(new BlockPos(0, 0, 0)), h.absolutePos(new BlockPos(8, 3, 8)))) {
                if (h.getLevel().getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
                    furnaces++;
                    int input = furnace.getItem(0).getCount();
                    biggestLoad[0] = Math.max(biggestLoad[0], input - lastInput.getOrDefault(pos.immutable(), 0));
                    lastInput.put(pos.immutable(), input);
                }
            }
            mostFurnaces[0] = Math.max(mostFurnaces[0], furnaces);
        });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1, "one report: " + reports);
            h.assertTrue(reports.getFirst().equals("smelted 30 raw_iron into iron_ingot in 3 furnaces, 3 raw_copper into copper_ingot"), "report: " + reports);
            h.assertTrue(mostFurnaces[0] == 3, "three furnaces side by side: " + mostFurnaces[0]);
            h.assertTrue(biggestLoad[0] >= 3, "the copper went in with one click, not one by one: " + biggestLoad[0]);
            h.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 30 && bot.getInventory().countItem(Items.COPPER_INGOT) == 3, "ingots made");
            h.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 0 && bot.getInventory().countItem(Items.RAW_COPPER) == 0, "ore used up");
            h.assertTrue(bot.getInventory().countItem(Items.FURNACE) >= 3, "took its furnaces back");
            h.assertTrue(h.getTick() < 30 * 200, "faster than one furnace: " + h.getTick() + " ticks");
            BotManager.despawn("BnJobSmelt");
        });
    }

    /** A dark stone room: walls, floor and roof, nothing lit. */
    private static void darkRoom(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) for (int y = 0; y <= 3; y++) {
            boolean shell = y == 0 || y == 3 || x == 0 || z == 0 || x == 8 || z == 8;
            h.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE : Blocks.AIR);
        }
    }

    /** Down in the dark for ore, a player sticks a torch beside them; the bot does it from its bag. */
    @GameTest(template = "arena", timeoutTicks = 1200)
    public static void lightsTheMineWithATorch(GameTestHelper h) {
        darkRoom(h);
        h.setBlock(new BlockPos(6, 1, 6), Blocks.COAL_ORE);
        BotPlayer bot = TestArena.spawnBot(h, "BnJobTorch", new Vec3(2.5, 1, 2.5));
        bot.fullPerception(true);
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        bot.getInventory().add(new ItemStack(Items.TORCH, 3));
        List<String> reports = new ArrayList<>();
        h.runAfterDelay(10, () -> bot.controller().obtain(Items.COAL, 1, true, reports::add));
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().contains("obtained 1 coal"), "report: " + reports);
            int torches = 0;
            for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(1, 1, 1), new BlockPos(7, 2, 7))) if (h.getBlockState(pos).is(Blocks.TORCH)) torches++;
            h.assertTrue(torches >= 1 && bot.getInventory().countItem(Items.TORCH) == 3 - torches, "lit the dark room with its own torch: " + torches);
            BotManager.despawn("BnJobTorch");
        });
    }

    /**
     * Ankle-deep water on a stone floor is waded: the bot walks through it and breaks the wall from
     * the ground. Swimming there (holding jump) kept it afloat for ten minutes in a live mine.
     */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void wadesThroughShallowWaterAndDigs(GameTestHelper h) {
        TestArena.floor(h);
        for (int x = 0; x <= 5; x++) for (int z = 0; z <= 8; z++) for (int y = 1; y <= 2; y++) {
            boolean wall = x == 0 || x == 5 || z == 0 || z == 8;
            h.setBlock(new BlockPos(x, y, z), wall ? Blocks.STONE : y == 1 ? Blocks.WATER : Blocks.AIR);
        }
        BotPlayer bot = TestArena.spawnBot(h, "BnJobWade", new Vec3(1.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.STONE_PICKAXE));
        List<String> reports = new ArrayList<>();
        int[] afloat = {0};
        h.runAfterDelay(10, () -> bot.controller().mine(h.absolutePos(new BlockPos(5, 1, 4)), reports::add));
        h.onEachTick(() -> { if (h.getTick() > 10 && reports.isEmpty() && bot.isInWater() && !bot.onGround()) afloat[0]++; });
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("mined block"), "report: " + reports);
            h.assertTrue(afloat[0] < 15, "walked and dug on the floor, not afloat: " + afloat[0] + " ticks off the ground in water");
            BotManager.despawn("BnJobWade");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 40)
    public static void smeltWithNoOreSaysSo(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnJobNoOre", new Vec3(4.5, 1, 2.5), new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.COAL, 2));
        List<String> reports = new ArrayList<>();
        bot.controller().smelt(null, reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.equals(List.of("failed: nothing to smelt in the inventory")), "honest report: " + reports);
            BotManager.despawn("BnJobNoOre");
        });
    }

    /**
     * "Сложи в сундук" from far away: the walk there takes longer than the 10 s menu timeout, which
     * live sent "stuck in open" from the bottom of a mine. Opening now includes the walk.
     */
    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 1600)
    public static void storesIntoAChestFarAway(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos chest = new BlockPos(45, 1, 45);
        h.setBlock(chest, Blocks.CHEST);
        BotPlayer bot = TestArena.spawnBot(h, "BnJobStore", new Vec3(2.5, 1, 2.5));
        bot.getInventory().clearContent();
        bot.getInventory().add(new ItemStack(Items.COAL, 13));
        List<String> reports = new ArrayList<>();
        bot.controller().deposit(h.absolutePos(chest), "minecraft:coal", 0, reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("deposited 13 coal"), "report: " + reports);
            h.assertTrue(h.getTick() > 200, "the walk alone took longer than the old open timeout");
            h.assertTrue(bot.getInventory().countItem(Items.COAL) == 0, "coal is in the chest");
            BotManager.despawn("BnJobStore");
        });
    }

    /**
     * "Расчисти площадку": a tree, soil and a hole in a 5x5 square around the player's spot are taken
     * to the floor height; the planks and chest of a build inside it and a log outside it stay.
     */
    @GameTest(template = "arena", timeoutTicks = 2400)
    public static void clearsTheSquareToTheFloorAndLeavesBuildsAlone(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnJobClear", new Vec3(4.5, 2, 0.5), new ItemStack(Items.STONE_AXE));
        // Floor at y=1 on a stone base, so the hole below it has something to fill onto.
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        h.setBlock(new BlockPos(6, 1, 6), Blocks.AIR);
        for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(5, y, 5), Blocks.OAK_LOG);
        var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (BlockPos leaf : List.of(new BlockPos(4, 4, 5), new BlockPos(6, 4, 5), new BlockPos(5, 4, 4), new BlockPos(5, 4, 6))) h.setBlock(leaf, leaves);
        h.setBlock(new BlockPos(3, 2, 5), Blocks.DIRT);
        h.setBlock(new BlockPos(2, 2, 3), Blocks.GRASS_BLOCK);
        h.setBlock(new BlockPos(3, 2, 3), Blocks.OAK_PLANKS);
        h.setBlock(new BlockPos(2, 2, 6), Blocks.CHEST);
        h.setBlock(new BlockPos(8, 2, 4), Blocks.OAK_LOG);
        List<String> reports = new ArrayList<>();
        // The player stands at (4, 2, 4): the floor is the stone under their feet.
        bot.controller().clear(h.absolutePos(new BlockPos(4, 2, 4)), 2, reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1, "one report: " + reports);
            String report = reports.getFirst();
            h.assertTrue(report.startsWith("cleared 5x5") && report.contains("to floor y=" + h.absolutePos(new BlockPos(0, 1, 0)).getY()), "report: " + report);
            h.assertTrue(report.contains("filled 1 hole") && report.contains("left 2 blocks of builds alone"), "report: " + report);
            for (int x = 2; x <= 6; x++) for (int z = 2; z <= 6; z++) {
                BlockPos floor = new BlockPos(x, 1, z);
                h.assertTrue(!h.getBlockState(floor).isAir(), "floor whole at " + floor);
                for (int y = 2; y <= 4; y++) {
                    BlockPos above = new BlockPos(x, y, z);
                    var state = h.getBlockState(above);
                    h.assertTrue(state.isAir() || above.equals(new BlockPos(3, 2, 3)) || above.equals(new BlockPos(2, 2, 6)), "cleared " + above + ": " + state);
                }
            }
            h.assertTrue(h.getBlockState(new BlockPos(6, 1, 6)).is(Blocks.DIRT), "hole filled with dirt the bot dug");
            h.assertBlockPresent(Blocks.OAK_PLANKS, new BlockPos(3, 2, 3));
            h.assertBlockPresent(Blocks.CHEST, new BlockPos(2, 2, 6));
            h.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(8, 2, 4));
            h.assertTrue(bot.getInventory().countItem(Items.OAK_LOG) >= 3, "kept the wood it chopped");
            BotManager.despawn("BnJobClear");
        });
    }
}
