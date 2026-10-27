package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GatherGameTests {
    private GatherGameTests() {}

    @GameTest(template = "arena", timeoutTicks = 1600)
    public static void gathersTenCobblestoneFromGround(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                for (int y = 0; y <= 8; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            }
        }
        BotPlayer bot = TestArena.spawnBot(helper, "BnGatherStone", new Vec3(4.5, 9, 1.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(5, new ItemStack(Items.WOODEN_PICKAXE));
        bot.getInventory().setItem(6, new ItemStack(Items.COBBLESTONE, 3));
        var reports = new ArrayList<String>();
        // The controller is shared by /blocknet and bridge gather; count means additional items.
        bot.controller().gather(new ItemStack(Items.COBBLESTONE), 10, reports::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "one terminal report: " + reports);
            helper.assertTrue(reports.getFirst().equals("gathered 10 cobblestone"), "report: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 13, "inventory gained ten");
            helper.assertTrue(helper.getTick() >= 230 && helper.getTick() < 1500,
                "ten stone blocks take survival ticks: " + helper.getTick());
            BotManager.despawn("BnGatherStone");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void failsWithHintWhenResourceAndGroundAreUnavailable(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.GLASS);
        }
        BotPlayer bot = TestArena.spawnBot(helper, "BnGatherGlass", new Vec3(4.5, 1, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        var reports = new ArrayList<String>();
        bot.controller().gather(new ItemStack(Items.COBBLESTONE), 10, reports::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "one terminal report: " + reports);
            helper.assertTrue(reports.getFirst().equals("failed: no cobblestone in sight; look around or go elsewhere"),
                "resource failure includes hint: " + reports);
            helper.assertBlockPresent(Blocks.GLASS, new BlockPos(4, 0, 4));
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0, "no hidden resource mined");
            BotManager.despawn("BnGatherGlass");
        });
    }
}
