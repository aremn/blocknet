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
            // Like a player, the bot may pick up a nearby extra drop; the report states the real count.
            var gathered = java.util.regex.Pattern.compile("gathered ([0-9]+) cobblestone").matcher(reports.getFirst());
            helper.assertTrue(gathered.matches() && Integer.parseInt(gathered.group(1)) >= 10, "report: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 3 + Integer.parseInt(gathered.group(1)),
                "inventory gained what the report says");
            helper.assertTrue(helper.getTick() >= 230 && helper.getTick() < 1500,
                "ten stone blocks take survival ticks: " + helper.getTick());
            BotManager.despawn("BnGatherStone");
        });
    }

    @GameTest(template = "arena", skyAccess = true, timeoutTicks = 400)
    public static void waitsForPerceptionBudgetBeforeGatheringGround(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) for (int y = 0; y < 4; y++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        var bot = TestArena.spawnBot(helper, "BnGatherBudget", new Vec3(4.5, 4, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        var reports = new ArrayList<String>();
        // Exhaust this tick with air rays, without giving the gatherer any floor observations.
        // This reproduces a slow ray/GC pause during obtain's preflight, independent of load.
        while (bot.vision().canObserve()) bot.vision().observe(bot.getEyePosition().add(0, .1, 0));
        bot.controller().gather(new ItemStack(Items.COBBLESTONE), 1, reports::add);
        bot.controller().tick();
        helper.assertTrue(reports.isEmpty(), "budget exhaustion must defer, not report no resource: " + reports);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.equals(java.util.List.of("gathered 1 cobblestone")), "report: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 1, "mined a real drop after observations resumed");
            BotManager.despawn("BnGatherBudget");
        });
    }

    @GameTest(template = "vision_field", skyAccess = true, timeoutTicks = 800)
    public static void gathersLocalGroundBeforeDistantVisibleLedge(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) for (int y = 0; y < 7; y++)
            helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        BlockPos ledge = new BlockPos(20, 7, 4);
        helper.setBlock(ledge, Blocks.STONE);
        var bot = TestArena.spawnBot(helper, "BnGatherLocal", new Vec3(4.5, 7, 4.5));
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        var reports = new ArrayList<String>();
        observeLedgeThenGather(helper, bot, ledge, reports);
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.equals(java.util.List.of("gathered 3 cobblestone")), "report: " + reports);
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 3, "three real local drops");
            helper.assertBlockPresent(Blocks.STONE, ledge);
            helper.assertTrue(bot.position().distanceTo(helper.absoluteVec(new Vec3(4.5, 7, 4.5))) < 6,
                "nearby ground wins over a visible but unreachable ledge");
            BotManager.despawn("BnGatherLocal");
        });
    }

    private static void observeLedgeThenGather(GameTestHelper helper, BotPlayer bot, BlockPos ledge,
            ArrayList<String> reports) {
        var hit = bot.vision().observe(Vec3.atCenterOf(helper.absolutePos(ledge)));
        if (hit.state() == null || !helper.absolutePos(ledge).equals(hit.pos())) {
            helper.runAfterDelay(1, () -> observeLedgeThenGather(helper, bot, ledge, reports));
            return;
        }
        bot.controller().gather(new ItemStack(Items.COBBLESTONE), 3, reports::add);
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
            helper.assertTrue(reports.getFirst().contains("none seen") && reports.getFirst().contains("try explore"),
                "resource failure includes hint: " + reports);
            helper.assertBlockPresent(Blocks.GLASS, new BlockPos(4, 0, 4));
            helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0, "no hidden resource mined");
            BotManager.despawn("BnGatherGlass");
        });
    }
}
