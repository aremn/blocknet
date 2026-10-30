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

    /** "Переплавь руду": both raw ores in the bag go through a furnace the bot places itself. */
    @GameTest(template = "arena", timeoutTicks = 3000)
    public static void smeltsTheRawOreInTheBag(GameTestHelper h) {
        BotPlayer bot = bot(h, "BnJobSmelt", new Vec3(4.5, 1, 2.5), new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.RAW_COPPER, 2),
            new ItemStack(Items.COAL, 2), new ItemStack(Items.FURNACE), new ItemStack(Items.STONE_PICKAXE));
        List<String> reports = new ArrayList<>();
        bot.controller().smelt(null, reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1, "one report: " + reports);
            h.assertTrue(reports.getFirst().equals("smelted 3 raw_iron into iron_ingot, 2 raw_copper into copper_ingot"), "report: " + reports);
            h.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 3 && bot.getInventory().countItem(Items.COPPER_INGOT) == 2, "ingots made");
            h.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 0 && bot.getInventory().countItem(Items.RAW_COPPER) == 0, "ore used up");
            h.assertTrue(h.getTick() >= 5 * 200, "five real furnace smelts");
            BotManager.despawn("BnJobSmelt");
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
