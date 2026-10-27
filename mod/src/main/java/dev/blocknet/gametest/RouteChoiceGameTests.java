package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.pathing.movement.CalculationContext;
import dev.blocknet.pathing.pathing.movement.MovementHelper;
import dev.blocknet.pathing.utils.ToolSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Time-based choices between walking, mining and building, through real player actions. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RouteChoiceGameTests {
    private RouteChoiceGameTests() {}

    // Parent 4ec3372: 196 ticks, breaking trunk logs at y=2,3. Shared search: 48-49 ticks,
    // including settling on the canopy (arrival report at 44), with all four logs intact.
    // 120 ticks leaves scheduling headroom but cannot fit the old digging route.
    @GameTest(template = "arena_tall", timeoutTicks = 120)
    public static void buildsToTreeInsteadOfDiggingTrunk(GameTestHelper helper) {
        tree(helper);
        BotPlayer leader = spawn(helper, "BnRouteLead", new Vec3(6.5, 5, 4.5));
        BotPlayer follower = spawn(helper, "BnRouteTree", new Vec3(1.5, 1, 4.5));
        follower.getInventory().setItem(20, new ItemStack(Blocks.OAK_LOG, 16));
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(leader.onGround(), "leader stands steadily on the canopy");
            follower.controller().come(leader.position(), leader.onGround(), reports::add);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.size() == 1, "one report: " + reports);
            helper.assertTrue(reports.getFirst().startsWith("arrived at"), reports.toString());
            helper.assertTrue(follower.blockPosition().getY() == helper.absolutePos(new BlockPos(6, 5, 4)).getY(), "reached treetop height");
            helper.assertTrue(follower.onGround(), "standing steadily at the goal");
            helper.assertTrue(follower.getInventory().countItem(Blocks.OAK_LOG.asItem()) < 16, "built with inventory logs");
            for (int y = 0; y <= 3; y++) helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(6, y, 4));
            assertFinished(helper, follower, reports);
            Blocknet.LOGGER.info("RouteChoice tree arrived and grounded at tick {}", helper.getTick());
            BotManager.despawn("BnRouteTree");
            BotManager.despawn("BnRouteLead");
            helper.assertTrue(reports.size() == 1, "cleanup must not send another report");
        });
    }

    @GameTest(template = "arena_tall", timeoutTicks = 700)
    public static void treeWithoutBlocksFinishesInsteadOfHanging(GameTestHelper helper) {
        tree(helper);
        BotPlayer leader = spawn(helper, "BnNoBlockLead", new Vec3(6.5, 5, 4.5));
        BotPlayer follower = spawn(helper, "BnNoBlockTree", new Vec3(1.5, 1, 4.5));
        List<String> reports = new ArrayList<>();
        helper.runAfterDelay(5, () -> follower.controller().come(leader.position(), leader.onGround(), reports::add));
        helper.succeedWhen(() -> {
            assertFinished(helper, follower, reports);
            String report = reports.getFirst();
            helper.assertTrue(report.startsWith("arrived at") || report.startsWith("failed: got as close as I could"), reports.toString());
            if (report.startsWith("arrived at")) {
                helper.assertTrue(follower.blockPosition().getY() == helper.absolutePos(new BlockPos(6, 5, 4)).getY(), "no false arrival below canopy");
            }
            BotManager.despawn("BnNoBlockTree");
            BotManager.despawn("BnNoBlockLead");
            helper.assertTrue(reports.size() == 1, "cleanup must not send another report");
        });
    }

    @GameTest(template = "arena_tall", timeoutTicks = 400)
    public static void walksThroughGapRatherThanMiningStoneWall(GameTestHelper helper) {
        TestArena.floor(helper);
        for (int z = 0; z <= 7; z++) {
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
        }
        BotPlayer bot = spawn(helper, "BnRouteWall", new Vec3(2.5, 1, 5.5));
        bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos goal = helper.absolutePos(new BlockPos(6, 1, 5));
        BlockPos gap = helper.absolutePos(new BlockPos(4, 1, 8));
        AtomicBoolean usedGap = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (bot.blockPosition().equals(gap)) usedGap.set(true);
        });
        List<String> reports = new ArrayList<>();
        bot.controller().goTo(goal, reports::add);
        helper.succeedWhen(() -> {
            assertFinished(helper, bot, reports);
            helper.assertTrue(reports.getFirst().startsWith("arrived at") && bot.blockPosition().equals(goal), reports.toString());
            helper.assertTrue(usedGap.get(), "walked through the gap");
            for (int z = 0; z <= 7; z++) {
                for (int y = 1; y <= 3; y++) helper.assertBlockPresent(Blocks.STONE, new BlockPos(4, y, z));
            }
            BotManager.despawn("BnRouteWall");
        });
    }

    /**
     * The A* estimate equals survival progress on dry ground, with effects. Water and airborne
     * slowdowns belong to the movements (where the block is mined), so where the bot happens to be
     * when the search starts must not change the estimate.
     */
    @GameTest(template = "arena_tall", timeoutTicks = 80)
    public static void miningEstimateMatchesPlayerAndIgnoresStartPosition(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = spawn(helper, "BnRouteSpeed", new Vec3(1.5, 1, 4.5));
        bot.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        bot.getInventory().selected = 0;
        BlockPos stone = helper.absolutePos(new BlockPos(3, 1, 4));
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.STONE);
        double[] dryGrounded = new double[1];
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                helper.assertTrue(bot.onGround(), "grounded before mining estimate");
                assertMiningEstimate(helper, bot, stone);
                dryGrounded[0] = plannedStrength(helper, bot, stone);
                bot.setOnGround(false);
                helper.assertTrue(plannedStrength(helper, bot, stone) == dryGrounded[0], "planning mid-jump does not slow every block");
                bot.setOnGround(true);
                bot.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 200, 1));
                assertMiningEstimate(helper, bot, stone);
                bot.removeEffect(MobEffects.DIG_SPEED);
                bot.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 200, 1));
                assertMiningEstimate(helper, bot, stone);
                bot.removeEffect(MobEffects.DIG_SLOWDOWN);
                helper.setBlock(new BlockPos(1, 1, 4), Blocks.WATER);
                helper.setBlock(new BlockPos(1, 2, 4), Blocks.WATER);
            })
            .thenExecuteAfter(3, () -> {
                helper.assertTrue(bot.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), "eyes submerged");
                helper.assertTrue(plannedStrength(helper, bot, stone) == dryGrounded[0], "planning underwater does not slow every block");
                BotManager.despawn("BnRouteSpeed");
            })
            .thenSucceed();
    }

    private static double plannedStrength(GameTestHelper helper, BotPlayer bot, BlockPos pos) {
        return new ToolSet(bot).getStrVsBlock(helper.getLevel().getBlockState(pos), helper.getLevel(), pos);
    }

    @GameTest(template = "arena_tall", timeoutTicks = 80)
    public static void choosesAndEquipsBestToolFromMainInventory(GameTestHelper helper) {
        TestArena.floor(helper);
        BotPlayer bot = spawn(helper, "BnRouteTool", new Vec3(1.5, 1, 4.5));
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        axe.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.EFFICIENCY), 3);
        bot.getInventory().setItem(20, axe);
        BlockPos log = helper.absolutePos(new BlockPos(3, 1, 4));
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.OAK_LOG);
        double[] plannedStrength = new double[1];
        helper.startSequence()
            .thenExecuteAfter(5, () -> {
                ToolSet tools = new ToolSet(bot);
                var state = helper.getLevel().getBlockState(log);
                helper.assertTrue(tools.getBestSlot(state, false, true) == 20, "planner finds inventory axe");
                plannedStrength[0] = tools.getStrVsBlock(state, helper.getLevel(), log);
                helper.assertTrue(bot.getMainHandItem().isEmpty(), "planning leaves the real hand alone");
                MovementHelper.switchToBestToolFor(IBaritone.KEY.get(bot).getEntityContext(), state, tools, false);
                helper.assertTrue(bot.getMainHandItem().is(Items.DIAMOND_AXE), "executor equips the same axe");
            })
            .thenExecuteAfter(2, () -> {
                assertMiningEstimate(helper, bot, log);
                double realStrength = helper.getLevel().getBlockState(log).getDestroyProgress(bot, helper.getLevel(), log);
                helper.assertTrue(Math.abs(plannedStrength[0] - realStrength) < realStrength * 0.00001,
                    "unequipped Efficiency III tool estimate agrees with its equipped survival speed");
                BotManager.despawn("BnRouteTool");
            })
            .thenSucceed();
    }

    private static void assertMiningEstimate(GameTestHelper helper, BotPlayer bot, BlockPos pos) {
        var state = helper.getLevel().getBlockState(pos);
        double expected = state.getDestroyProgress(bot, helper.getLevel(), pos);
        double actual = new ToolSet(bot).getStrVsBlock(state, helper.getLevel(), pos);
        helper.assertTrue(Math.abs(expected - actual) < expected * 0.00001,
            "mining progress matches player: expected " + expected + ", got " + actual);
        CalculationContext context = new CalculationContext(IBaritone.KEY.get(bot));
        double duration = MovementHelper.getMiningDurationTicks(context, pos.getX(), pos.getY(), pos.getZ(), state, false);
        double expectedDuration = Math.max(1.0, Math.ceil(1.0 / actual)) + context.breakBlockAdditionalCost;
        helper.assertTrue(duration == expectedDuration, "movement adds the break preference once to survival ticks");
    }

    private static void assertFinished(GameTestHelper helper, BotPlayer bot, List<String> reports) {
        helper.assertTrue(reports.size() == 1, "one final report: " + reports);
        helper.assertTrue(bot.controller().describe().startsWith("idle"), "task finished");
        var settings = IBaritone.KEY.get(bot).settings();
        helper.assertTrue(!settings.allowPlace.get() && settings.maxFallHeightNoWater.get() == 3, "attempt settings restored");
    }

    private static void tree(GameTestHelper helper) {
        TestArena.floor(helper);
        // Four log blocks, with the bottom log flush with the arena floor (y=0).
        // This gives the old planner a valid staircase through the two upper logs.
        for (int y = 0; y <= 3; y++) {
            helper.setBlock(new BlockPos(6, y, 4), Blocks.OAK_LOG);
        }
        var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int y = 2; y <= 3; y++) {
            for (int x = 5; x <= 7; x++) {
                for (int z = 3; z <= 5; z++) {
                    if (x != 6 || z != 4) helper.setBlock(new BlockPos(x, y, z), leaves);
                }
            }
        }
        for (int x = 5; x <= 7; x++) helper.setBlock(new BlockPos(x, 4, 4), leaves);
    }

    private static BotPlayer spawn(GameTestHelper helper, String name, Vec3 pos) {
        BotPlayer bot = TestArena.spawnBot(helper, name, pos);
        bot.getInventory().clearContent();
        bot.removeAllEffects();
        bot.setHealth(bot.getMaxHealth());
        return bot;
    }
}
