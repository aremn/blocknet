package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.CraftPlan;
import dev.blocknet.crafting.CraftingCalculation;
import dev.blocknet.crafting.ItemKey;
import dev.blocknet.crafting.PlannerCatalogs;
import dev.blocknet.control.CraftStations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftObtainGameTests {
    private static final BlockPos CHEST = new BlockPos(4, 1, 5);
    private static final BlockPos TABLE = new BlockPos(3, 1, 4);

    private CraftObtainGameTests() {}

    private static BotPlayer bot(GameTestHelper helper, String name, ItemStack... items) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, name, new Vec3(4.5, 1, 2.5));
        bot.getInventory().clearContent();
        for (var entry : bot.containerMemory().entries()) {
            if (entry.location().dimension().equals(bot.level().dimension().location().toString())) {
                bot.containerMemory().forget(bot, BlockPos.of(entry.location().position()));
            }
        }
        for (ItemStack item : items) {
            bot.getInventory().add(item.copy());
        }
        return bot;
    }

    private static Map<ItemKey, Long> inventory(BotPlayer bot) {
        var stock = new TreeMap<ItemKey, Long>();
        bot.getInventory().items.stream().filter(stack -> !stack.isEmpty())
            .forEach(stack -> stock.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum));
        return stock;
    }

    private static ChestBlockEntity chest(GameTestHelper helper, int count) {
        helper.setBlock(CHEST, Blocks.CHEST);
        var chest = (ChestBlockEntity) helper.getBlockEntity(CHEST);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, count));
        return chest;
    }

    private static String chestDescription(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(CHEST);
        return "chest " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void report(GameTestHelper helper, BotPlayer bot, List<String> reports, String expected) {
        helper.assertTrue(reports.equals(List.of(expected)), "one exact report: " + reports);
        helper.assertTrue(bot.containerMenu == bot.inventoryMenu, "menu closed");
        Blocknet.LOGGER.info("Craft/get report: {}", reports);
    }

    private static CommandSourceStack source(GameTestHelper helper, BotPlayer bot, List<String> reports) {
        CommandSource output = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                reports.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() { return true; }

            @Override
            public boolean acceptsFailure() { return true; }

            @Override
            public boolean shouldInformAdmins() { return false; }
        };
        return helper.getLevel().getServer().createCommandSourceStack().withSource(output)
            .withLevel(helper.getLevel()).withPosition(bot.position());
    }

    @GameTest(template = "arena", timeoutTicks = 1500)
    public static void craftSmeltsThreeInAdditionToFiveOwnedIngots(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftNew", new ItemStack(Items.IRON_INGOT, 5),
            new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL),
            new ItemStack(Items.COBBLESTONE, 8), new ItemStack(Items.IRON_PICKAXE));
        helper.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        List<String> reports = new ArrayList<>();
        var source = source(helper, bot, reports);
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source,
            "blocknet craft BnCraftNew iron_ingot 3");
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "[BnCraftNew] crafted 3 iron_ingot");
            helper.assertTrue(helper.getTick() >= 600, "three real smelts");
            helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 8, "five old plus three new");
            helper.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 0
                && bot.getInventory().countItem(Items.COAL) == 0, "smelting consumed ingredients and fuel");
            BotManager.despawn("BnCraftNew");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 1500)
    public static void craftDoesNotWithdrawTargetFromRememberedChest(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftNoTake", new ItemStack(Items.RAW_IRON, 3),
            new ItemStack(Items.COAL), new ItemStack(Items.FURNACE), new ItemStack(Items.IRON_PICKAXE));
        var chest = chest(helper, 5);
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(reports.size() == 1, "observed chest"))
            .thenExecute(() -> {
                var plan = new CraftingCalculation(bot.serverLevel(), PlannerCatalogs.get(bot.serverLevel()))
                    .plan(new ItemStack(Items.IRON_INGOT), 3, bot.getInventory().items,
                        CraftStations.visible(bot).keySet(), bot.containerMemory().sources(bot));
                helper.assertTrue(plan.status() == CraftPlan.Status.OK && plan.withdrawals().isEmpty(), plan.text());
                reports.clear();
                bot.controller().craft(new ItemStack(Items.IRON_INGOT), 3, reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "crafted 3 iron_ingot");
                helper.assertTrue(chest.countItem(Items.IRON_INGOT) == 5, "target chest untouched");
                helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 3, "new ingots");
                helper.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 0, "actually smelted");
            }).thenExecute(() -> BotManager.despawn("BnCraftNoTake")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 60)
    public static void craftWithoutRecipeIsBlockedEvenWhenTargetIsOwned(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftNoRecipe", new ItemStack(Items.BEDROCK, 5));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.BEDROCK), 3, reports::add);
        helper.runAfterDelay(10, () -> {
            report(helper, bot, reports, "blocked: no recipe for bedrock");
            helper.assertTrue(inventory(bot).equals(before), "no items moved");
            BotManager.despawn("BnCraftNoRecipe");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 60)
    public static void craftWithOnlyUnsupportedRecipeReportsNoRecipeAndTypeHint(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnNoRunRecipe", new ItemStack(Items.DIAMOND_PICKAXE),
            new ItemStack(Items.NETHERITE_INGOT), new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
            new ItemStack(Items.SMITHING_TABLE));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.NETHERITE_PICKAXE), 1, reports::add);
        helper.runAfterDelay(10, () -> {
            report(helper, bot, reports, "blocked: no recipe for netherite_pickaxe; netherite_pickaxe: can be made in minecraft:smithing");
            helper.assertTrue(inventory(bot).equals(before), "unsupported recipe moved nothing");
            BotManager.despawn("BnNoRunRecipe");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void craftReportsActualBatchOutputInAdditionToOwnedTarget(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftBatchNew", new ItemStack(Items.STICK, 5), new ItemStack(Items.OAK_PLANKS, 2));
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.STICK), 1, reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "crafted 4 stick");
            helper.assertTrue(bot.getInventory().countItem(Items.STICK) == 9, "batch output added to old stock");
            BotManager.despawn("BnCraftBatchNew");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 60)
    public static void getAlreadyHeldReportsOnceWithoutMovingAnything(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnGetOwned", new ItemStack(Items.IRON_INGOT, 5));
        var chest = chest(helper, 3);
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        var source = source(helper, bot, reports);
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source, "blocknet get iron_ingot 3");
        helper.runAfterDelay(10, () -> {
            report(helper, bot, reports, "[BnGetOwned] already have 5 iron_ingot");
            helper.assertTrue(inventory(bot).equals(before) && chest.countItem(Items.IRON_INGOT) == 3, "nothing moved");
            BotManager.despawn("BnGetOwned");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 250)
    public static void getWithdrawsOnlyShortfallFromNearestChestWithoutCrafting(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnGetChest", new ItemStack(Items.IRON_INGOT, 2),
            new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL));
        var chest = chest(helper, 3);
        BlockPos farPos = new BlockPos(6, 1, 6);
        helper.setBlock(farPos, Blocks.BARREL);
        var far = (net.minecraft.world.Container) helper.getBlockEntity(farPos);
        far.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(farPos), reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(reports.size() == 1, "observed far barrel"))
            .thenExecute(() -> {
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> helper.assertTrue(reports.size() == 1, "observed near chest"))
            .thenExecute(() -> {
                reports.clear();
                var source = source(helper, bot, reports);
                helper.getLevel().getServer().getCommands().performPrefixedCommand(source,
                    "blocknet get BnGetChest iron_ingot 5");
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "[BnGetChest] got 5 iron_ingot: had 2, took 3 from " + chestDescription(helper));
                helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 5
                    && chest.countItem(Items.IRON_INGOT) == 0 && far.countItem(Items.IRON_INGOT) == 3,
                    "only shortfall withdrawn, nearest first");
                helper.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 3
                    && bot.getInventory().countItem(Items.COAL) == 1, "no crafting");
            }).thenExecute(() -> BotManager.despawn("BnGetChest")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 150)
    public static void getCraftsSticksFromPlanks(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnGetSticks", new ItemStack(Items.OAK_PLANKS, 2));
        List<String> reports = new ArrayList<>();
        bot.controller().get(new ItemStack(Items.STICK), 4, reports::add);
        helper.succeedWhen(() -> {
            report(helper, bot, reports, "got 4 stick: crafted 4");
            helper.assertTrue(bot.getInventory().countItem(Items.STICK) == 4
                && bot.getInventory().countItem(Items.OAK_PLANKS) == 0, "real crafting");
            BotManager.despawn("BnGetSticks");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 1500)
    public static void getCombinesOwnedWithdrawnAndCraftedItems(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnGetMixed", new ItemStack(Items.IRON_INGOT, 2),
            new ItemStack(Items.RAW_IRON), new ItemStack(Items.COAL),
            new ItemStack(Items.FURNACE), new ItemStack(Items.IRON_PICKAXE));
        var chest = chest(helper, 2);
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(reports.size() == 1, "observed chest"))
            .thenExecute(() -> {
                reports.clear();
                bot.controller().get(new ItemStack(Items.IRON_INGOT), 5, reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "got 5 iron_ingot: had 2, took 2 from " + chestDescription(helper) + ", crafted 1");
                helper.assertTrue(chest.countItem(Items.IRON_INGOT) == 0
                    && bot.getInventory().countItem(Items.IRON_INGOT) == 5
                    && bot.getInventory().countItem(Items.RAW_IRON) == 0, "all three sources used");
            }).thenExecute(() -> BotManager.despawn("BnGetMixed")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 1500)
    public static void getReplanKeepsActualWithdrawalsAndCraftsOnlyRemainingShortfall(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnGetReplan", new ItemStack(Items.IRON_INGOT, 2),
            new ItemStack(Items.RAW_IRON), new ItemStack(Items.COAL),
            new ItemStack(Items.FURNACE), new ItemStack(Items.IRON_PICKAXE));
        var chest = chest(helper, 2);
        BlockPos farPos = new BlockPos(6, 1, 6);
        helper.setBlock(farPos, Blocks.BARREL);
        var far = (net.minecraft.world.Container) helper.getBlockEntity(farPos);
        far.setItem(0, new ItemStack(Items.IRON_INGOT));
        List<String> reports = new ArrayList<>();
        bot.controller().lookIn(helper.absolutePos(farPos), reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(reports.size() == 1, "observed far barrel"))
            .thenExecute(() -> {
                reports.clear();
                bot.controller().lookIn(helper.absolutePos(CHEST), reports::add);
            }).thenWaitUntil(() -> helper.assertTrue(reports.size() == 1, "observed near chest"))
            .thenExecute(() -> {
                far.clearContent();
                reports.clear();
                bot.controller().get(new ItemStack(Items.IRON_INGOT), 5, reports::add);
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "got 5 iron_ingot: had 2, took 2 from " + chestDescription(helper) + ", crafted 1");
                helper.assertTrue(chest.countItem(Items.IRON_INGOT) == 0
                    && bot.getInventory().countItem(Items.IRON_INGOT) == 5
                    && bot.getInventory().countItem(Items.RAW_IRON) == 0,
                    "earlier withdrawal survives replanning, stale source never claimed");
            }).thenExecute(() -> BotManager.despawn("BnGetReplan")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 250)
    public static void craftReplanStillAddsToOriginalTargetStock(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftNewRetry", new ItemStack(Items.STICK, 5), new ItemStack(Items.OAK_LOG));
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.STICK), 4, reports::add);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                bot.controller().describe().equals("crafting step 2 step"), "planks crafted"))
            .thenExecute(() -> {
                for (int slot = 0; slot < bot.getInventory().items.size(); slot++) {
                    if (bot.getInventory().getItem(slot).is(Items.OAK_PLANKS)) {
                        bot.getInventory().removeItemNoUpdate(slot);
                    }
                }
                bot.getInventory().add(new ItemStack(Items.BIRCH_PLANKS, 4));
            }).thenWaitUntil(() -> {
                report(helper, bot, reports, "crafted 4 stick");
                helper.assertTrue(bot.getInventory().countItem(Items.STICK) == 9
                    && bot.getInventory().countItem(Items.BIRCH_PLANKS) == 2,
                    "replan crafted the full new amount instead of using old sticks");
            }).thenExecute(() -> BotManager.despawn("BnCraftNewRetry")).thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void planCommandsDistinguishCraftAndGetWithoutMovingItems(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnPlanModes", new ItemStack(Items.IRON_INGOT, 5));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        var source = source(helper, bot, reports);
        var commands = helper.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(source, "blocknet plan get BnPlanModes iron_ingot 3");
        helper.assertTrue(reports.equals(List.of("plan get 3 iron_ingot: ok", "use: 3 iron_ingot")), reports.toString());
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet plan craft iron_ingot 3");
        helper.assertTrue(reports.getFirst().equals("plan craft 3 iron_ingot: missing"), reports.toString());
        var explicitCraft = List.copyOf(reports);
        reports.clear();
        commands.performPrefixedCommand(source, "blocknet plan BnPlanModes iron_ingot 3");
        helper.assertTrue(reports.equals(explicitCraft) && inventory(bot).equals(before), "default plan is craft, no movement");
        BotManager.despawn("BnPlanModes");
        helper.succeed();
    }
}
