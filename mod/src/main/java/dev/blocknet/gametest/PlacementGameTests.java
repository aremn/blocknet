// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlacementGameTests {
    private static final String CABLE = "ae2:light_gray_smart_cable";
    private static final String TERMINAL = "ae2:crafting_terminal";
    private static final BlockPos SPOT = new BlockPos(4, 1, 4);

    private static BotPlayer setup(GameTestHelper h, String name) {
        TestArena.floor(h);
        var bot = TestArena.spawnBot(h, name, new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        return bot;
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void placesOrdinaryBlockAndConsumesOne(GameTestHelper h) {
        var bot = setup(h, "BnPlaceBlock");
        bot.getInventory().setItem(0, new ItemStack(Items.DIRT, 2));
        bot.getInventory().setItem(1, new ItemStack(Items.STICK));
        List<String> reports = new ArrayList<>();
        bot.controller().place(new ItemStack(Items.DIRT), h.absolutePos(SPOT), Direction.UP, reports::add);
        // Like navigation selecting its own tool while approaching the placement face.
        bot.getInventory().selected = 1;
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.DIRT, SPOT);
            h.assertTrue(bot.getInventory().countItem(Items.DIRT) == 1, "one block consumed");
            h.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("placed dirt"), "one report: " + reports);
            BotManager.despawn("BnPlaceBlock");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 300)
    public static void placesNonBlockCableThenAttachesUsableTerminal(GameTestHelper h) {
        var cable = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(CABLE));
        var terminal = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(TERMINAL));
        // Fixture mods are optional in production; the dev runtime supplies both items.
        if (cable.isEmpty() || terminal.isEmpty()) { h.succeed(); return; }
        h.assertTrue(!(cable.get() instanceof BlockItem) && !(terminal.get() instanceof BlockItem), "fixture exercises item placement");
        var bot = setup(h, "BnPlaceParts");
        bot.getInventory().setItem(0, new ItemStack(cable.get(), 2));
        bot.getInventory().setItem(1, new ItemStack(terminal.get(), 2));
        List<String> reports = new ArrayList<>();
        int[] stage = {0};
        bot.controller().place(new ItemStack(cable.get()), h.absolutePos(SPOT), Direction.UP, reports::add);
        h.onEachTick(() -> {
            if (stage[0] == 0 && reports.size() == 1) {
                h.assertTrue(reports.getFirst().startsWith("placed "), "cable placed: " + reports);
                h.assertTrue(!h.getBlockState(SPOT).isAir() && bot.getInventory().countItem(cable.get()) == 1, "real cable consumed");
                stage[0] = 1;
                bot.controller().place(new ItemStack(terminal.get()), h.absolutePos(SPOT), Direction.NORTH, reports::add);
            } else if (stage[0] == 1 && reports.size() == 2) {
                h.assertTrue(reports.getLast().startsWith("attached ") && reports.getLast().contains("north face"), "attachment: " + reports);
                h.assertTrue(bot.getInventory().countItem(terminal.get()) == 1, "one terminal consumed");
                stage[0] = 2;
                bot.controller().use(h.absolutePos(SPOT), reports::add);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 2 && reports.size() == 3, "three completed actions: " + reports);
            h.assertTrue(bot.containerMenu != bot.inventoryMenu, "attached terminal opens a real player menu");
            bot.closeContainer();
            BotManager.despawn("BnPlaceParts");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void rejectedItemNeverReportsPlacement(GameTestHelper h) {
        var bot = setup(h, "BnPlaceReject");
        bot.getInventory().setItem(0, new ItemStack(Items.STICK, 2));
        List<String> reports = new ArrayList<>();
        bot.controller().place(new ItemStack(Items.STICK), h.absolutePos(SPOT), Direction.UP, reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("failed:"), "rejected click is not success: " + reports);
            h.assertTrue(h.getBlockState(SPOT).isAir() && bot.getInventory().countItem(Items.STICK) == 2, "no mutation");
            BotManager.despawn("BnPlaceReject");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void cannotPlaceThroughWall(GameTestHelper h) {
        var bot = setup(h, "BnPlaceWall");
        bot.getInventory().setItem(0, new ItemStack(Items.DIRT, 2));
        // Surround a reachable target with unbreakable blocks: neither rays nor navigation may cheat.
        for (int x = 3; x <= 5; x++) for (int z = 3; z <= 5; z++) for (int y = 1; y <= 3; y++)
            if (x != 4 || z != 4) h.setBlock(new BlockPos(x, y, z), Blocks.BEDROCK);
        h.setBlock(SPOT.above(2), Blocks.BEDROCK);
        List<String> reports = new ArrayList<>();
        bot.controller().place(new ItemStack(Items.DIRT), h.absolutePos(SPOT), Direction.UP, reports::add);
        h.succeedWhen(() -> {
            h.assertTrue(reports.size() == 1 && reports.getFirst().startsWith("failed:"), "blocked placement: " + reports);
            h.assertTrue(h.getBlockState(SPOT).isAir() && bot.getInventory().countItem(Items.DIRT) == 2, "wall prevents placement");
            BotManager.despawn("BnPlaceWall");
        });
    }
}
