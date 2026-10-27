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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftExecuteGameTests {
    private static final String FIXTURE_NAMESPACE = "ae2";
    private static final BlockPos TABLE = new BlockPos(4, 1, 5);

    private CraftExecuteGameTests() {}

    private static BotPlayer bot(GameTestHelper helper, String name, ItemStack... items) {
        TestArena.floor(helper);
        BotPlayer bot = TestArena.spawnBot(helper, name, new Vec3(4.5, 1, 3.5));
        bot.getInventory().clearContent();
        for (ItemStack stack : items) {
            bot.getInventory().add(stack.copy());
        }
        return bot;
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static Map<ItemKey, Long> inventory(BotPlayer bot) {
        Map<ItemKey, Long> stock = new TreeMap<>();
        for (ItemStack stack : bot.getInventory().items) {
            if (!stack.isEmpty()) {
                stock.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return stock;
    }

    private static CraftPlan plan(BotPlayer bot, Item item, int count) {
        return new CraftingCalculation(bot.serverLevel(), PlannerCatalogs.get(bot.serverLevel()))
            .plan(new ItemStack(item), count, bot.getInventory().items, CraftStations.visible(bot).keySet());
    }

    private static void done(GameTestHelper helper, BotPlayer bot, List<String> reports, String prefix) {
        helper.assertTrue(reports.size() == 1 && reports.getFirst().startsWith(prefix), "one final report: " + reports);
        helper.assertTrue(bot.containerMenu == bot.inventoryMenu, "menu closed");
        Blocknet.LOGGER.info("Craft execution {} at tick {}: {}", bot.getGameProfile().getName(), helper.getTick(), reports.getFirst());
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void craftsToolAndRecoversItsTableWithExactLeftovers(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftTool", stack(Items.OAK_LOG, 3));
        var plan = plan(bot, Items.WOODEN_PICKAXE, 1);
        helper.assertTrue(plan.status() == CraftPlan.Status.OK, plan.text());
        var expected = new TreeMap<>(plan.leftovers());
        expected.put(new ItemKey(new ItemStack(Items.WOODEN_PICKAXE)), 1L);
        expected.put(new ItemKey(new ItemStack(Items.CRAFTING_TABLE)), 1L);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 1 wooden_pickaxe");
            helper.assertTrue(inventory(bot).equals(expected), "exact leftovers: " + inventory(bot) + " vs " + expected);
            for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 8, 2, 8)) {
                helper.assertTrue(!helper.getBlockState(pos).is(Blocks.CRAFTING_TABLE), "no table litter");
            }
            BotManager.despawn("BnCraftTool");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 250)
    public static void visibleTableSavesTheThirdLog(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftPlaced", stack(Items.OAK_LOG, 2));
        helper.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        var plan = plan(bot, Items.WOODEN_PICKAXE, 1);
        helper.assertTrue(plan.status() == CraftPlan.Status.OK && plan.steps().stream()
            .noneMatch(step -> step.outputs().containsKey(new ItemKey(new ItemStack(Items.CRAFTING_TABLE)))), plan.text());
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 1 wooden_pickaxe");
            helper.assertBlockPresent(Blocks.CRAFTING_TABLE, TABLE);
            helper.assertTrue(bot.getInventory().countItem(Items.CRAFTING_TABLE) == 0, "no new table");
            helper.assertTrue(bot.getInventory().countItem(Items.WOODEN_PICKAXE) == 1, "pickaxe produced");
            BotManager.despawn("BnCraftPlaced");
        });
    }

    @GameTest(template = "arena_tall", timeoutTicks = 60)
    public static void tableBehindWallDoesNotChangePlan(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftHidden", stack(Items.OAK_LOG, 2));
        var without = plan(bot, Items.WOODEN_PICKAXE, 1);
        for (int x = 0; x < 9; x++) {
            for (int y = 1; y < 6; y++) {
                helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(4, 1, 6), Blocks.CRAFTING_TABLE);
        var hidden = plan(bot, Items.WOODEN_PICKAXE, 1);
        helper.assertTrue(!CraftStations.visible(bot).containsKey("crafting_table"), "no view through wall");
        helper.assertTrue(hidden.status() == CraftPlan.Status.MISSING && hidden.text().equals(without.text()), hidden.text());
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        done(helper, bot, reports, "blocked: missing ");
        BotManager.despawn("BnCraftHidden");
        helper.succeed();
    }

    private static void smelt(GameTestHelper helper, String name, boolean pickaxe) {
        BotPlayer bot = bot(helper, name, stack(Items.RAW_IRON, 3), stack(Items.COAL, 1), stack(Items.COBBLESTONE, 8));
        // A furnace is a 3x3 recipe: these materials require access to a table.
        helper.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        if (pickaxe) {
            bot.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        }
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.IRON_INGOT), 3, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 3 iron_ingot");
            helper.assertTrue(helper.getTick() >= 600, "honest furnace time: " + helper.getTick());
            helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 3, "three ingots");
            helper.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 0 && bot.getInventory().countItem(Items.COAL) == 0,
                "inputs and fuel consumed");
            helper.assertTrue(bot.getInventory().countItem(Items.FURNACE) == (pickaxe ? 1 : 0), "furnace recovery");
            helper.assertTrue(reports.getFirst().contains("left a furnace at ") != pickaxe, "left station note");
            if (!pickaxe) {
                boolean found = false;
                for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 8, 1, 8)) {
                    if (helper.getBlockState(pos).is(Blocks.FURNACE)) {
                        found = true;
                        String where = helper.absolutePos(pos).getX() + " " + helper.absolutePos(pos).getY() + " " + helper.absolutePos(pos).getZ();
                        helper.assertTrue(reports.getFirst().contains(where), "actual furnace coordinates");
                    }
                }
                helper.assertTrue(found, "furnace left in world");
            }
            BotManager.despawn(name);
        });
    }

    @GameTest(template = "arena", timeoutTicks = 1500)
    public static void smeltsInRealTimeAndLeavesFurnaceWithoutTool(GameTestHelper helper) {
        smelt(helper, "BnCraftSmelt", false);
    }

    @GameTest(template = "arena", timeoutTicks = 1500)
    public static void smeltsAndRecoversFurnaceWithPickaxe(GameTestHelper helper) {
        smelt(helper, "BnCraftRecover", true);
    }

    @GameTest(template = "arena", timeoutTicks = 60)
    public static void missingMaterialsReportOnceWithoutMovingItems(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftMissing", stack(Items.OAK_LOG, 1));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.runAfterDelay(10, () -> {
            done(helper, bot, reports, "blocked: missing ");
            helper.assertTrue(inventory(bot).equals(before), "blocked inventory unchanged");
            BotManager.despawn("BnCraftMissing");
            helper.succeed();
        });
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void stopReturnsUnconsumedInventoryGridAndReportsOnce(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftStop", stack(Items.OAK_LOG, 3));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(bot.inventoryMenu.getSlot(1).hasItem(), "ingredient in 2x2 grid"))
            .thenExecute(() -> {
                bot.controller().stop();
                bot.controller().stop();
                done(helper, bot, reports, "failed: step 1 minecraft:oak_planks: cancelled");
                helper.assertTrue(inventory(bot).equals(before), "interrupted step lost no items");
                for (int slot = 1; slot <= 4; slot++) {
                    helper.assertTrue(!bot.inventoryMenu.getSlot(slot).hasItem(), "grid cleared");
                }
                BotManager.despawn("BnCraftStop");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 600)
    public static void executesForeignTableRecipeDiscoveredAtRuntime(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftForeign");
        helper.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        var holder = helper.getLevel().getRecipeManager().getRecipes().stream()
            .filter(candidate -> candidate.id().getNamespace().equals(FIXTURE_NAMESPACE)
                && candidate.value() instanceof CraftingRecipe && !candidate.value().isSpecial()
                && !candidate.value().canCraftInDimensions(2, 2) && candidate.value().canCraftInDimensions(3, 3)
                && !candidate.value().getResultItem(helper.getLevel().registryAccess()).isEmpty()
                && candidate.value().getIngredients().stream().filter(ingredient -> !ingredient.isEmpty())
                    .allMatch(ingredient -> ingredient.getItems().length > 0))
            .sorted(Comparator.comparing(candidate -> candidate.id().toString())).findFirst().orElseThrow();
        for (var ingredient : holder.value().getIngredients()) {
            if (!ingredient.isEmpty()) {
                bot.getInventory().add(ingredient.getItems()[0].copyWithCount(1));
            }
        }
        ItemStack output = holder.value().getResultItem(helper.getLevel().registryAccess());
        var plan = plan(bot, output.getItem(), output.getCount());
        helper.assertTrue(plan.status() == CraftPlan.Status.OK && plan.steps().stream()
            .anyMatch(step -> step.station().equals("crafting_table") && step.recipeId().getNamespace().equals(FIXTURE_NAMESPACE)), plan.text());
        List<String> reports = new ArrayList<>();
        bot.controller().craft(output, output.getCount(), reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted " + output.getCount() + " " + new ItemKey(output));
            helper.assertTrue(bot.getInventory().countItem(output.getItem()) == output.getCount(), "foreign output produced");
            BotManager.despawn("BnCraftForeign");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void craftCommandDeliversFinalReport(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftCommand", stack(Items.OAK_LOG, 1));
        List<String> reports = new ArrayList<>();
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
        var source = helper.getLevel().getServer().createCommandSourceStack().withSource(output)
            .withLevel(helper.getLevel()).withPosition(bot.position());
        helper.getLevel().getServer().getCommands().performPrefixedCommand(source, "blocknet craft BnCraftCommand minecraft:oak_planks 4");
        helper.succeedWhen(() -> {
            helper.assertTrue(reports.equals(List.of("[BnCraftCommand] crafted 4 oak_planks")), reports.toString());
            helper.assertTrue(bot.getInventory().countItem(Items.OAK_PLANKS) == 4, "command really crafted");
            Blocknet.LOGGER.info("Craft command report at tick {}: {}", helper.getTick(), reports);
            BotManager.despawn("BnCraftCommand");
        });
    }

    private static void fastCooking(GameTestHelper helper, String name, boolean smoker) {
        Item input = smoker ? Items.BEEF : Items.RAW_IRON;
        Item output = smoker ? Items.COOKED_BEEF : Items.IRON_INGOT;
        String station = smoker ? "smoker" : "blast_furnace";
        BotPlayer bot = bot(helper, name, stack(input, 3), stack(Items.COAL, 2));
        helper.setBlock(TABLE, smoker ? Blocks.SMOKER : Blocks.BLAST_FURNACE);
        var plan = plan(bot, output, 3);
        helper.assertTrue(plan.status() == CraftPlan.Status.OK
            && plan.steps().stream().allMatch(step -> step.station().equals(station)), plan.text());
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(output), 3, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 3 " + new ItemKey(new ItemStack(output)));
            helper.assertTrue(helper.getTick() >= 300, "honest fast cooking time");
            helper.assertTrue(bot.getInventory().countItem(output) == 3 && bot.getInventory().countItem(Items.COAL) == 1,
                "correct output and untouched spare fuel");
            helper.assertBlockPresent(smoker ? Blocks.SMOKER : Blocks.BLAST_FURNACE, TABLE);
            BotManager.despawn(name);
        });
    }

    @GameTest(template = "arena", timeoutTicks = 800)
    public static void blastFurnaceUsesSameMenuAndRealCookingTime(GameTestHelper helper) {
        fastCooking(helper, "BnCraftBlast", false);
    }

    @GameTest(template = "arena", timeoutTicks = 800)
    public static void smokerUsesSameMenuAndRealCookingTime(GameTestHelper helper) {
        fastCooking(helper, "BnCraftSmoke", true);
    }

    @GameTest(template = "arena", timeoutTicks = 800)
    public static void replansOnceWhenIngredientsChangeBetweenSteps(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftReplan", stack(Items.OAK_LOG, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(bot.controller().describe().equals("crafting step 2 step"), "first step finished"))
            .thenExecute(() -> {
                helper.assertTrue(bot.getInventory().countItem(Items.OAK_PLANKS) == 12, "planks crafted");
                for (int slot = 0; slot < bot.getInventory().items.size(); slot++) {
                    if (bot.getInventory().getItem(slot).is(Items.OAK_PLANKS)) {
                        bot.getInventory().removeItemNoUpdate(slot);
                    }
                }
                bot.getInventory().add(stack(Items.BIRCH_PLANKS, 12));
            })
            .thenWaitUntil(() -> helper.assertTrue(!reports.isEmpty(), "final report"))
            .thenExecute(() -> {
                done(helper, bot, reports, "crafted 1 wooden_pickaxe");
                helper.assertTrue(bot.getInventory().countItem(Items.BIRCH_PLANKS) == 3, "new plan's leftovers");
                BotManager.despawn("BnCraftReplan");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 100)
    public static void missingInputsAfterReplanStopWithOneFailure(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftChanged", stack(Items.OAK_LOG, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(bot.controller().describe().equals("crafting step 2 step"), "first step finished"))
            .thenExecute(() -> bot.getInventory().clearContent())
            .thenWaitUntil(() -> helper.assertTrue(!reports.isEmpty(), "final report"))
            .thenExecute(() -> {
                done(helper, bot, reports, "failed: step ");
                helper.assertTrue(reports.getFirst().contains("inventory changed; missing"), reports.toString());
                helper.assertTrue(inventory(bot).isEmpty(), "no hypothetical crafts executed");
                BotManager.despawn("BnCraftChanged");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void logoutClosesGridAndReturnsIngredientsBeforeSaving(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftLogout", stack(Items.OAK_LOG, 3));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(bot.inventoryMenu.getSlot(1).hasItem(), "grid filled"))
            .thenExecute(() -> {
                BotManager.despawn("BnCraftLogout");
                done(helper, bot, reports, "failed: step 1 minecraft:oak_planks: cancelled");
                helper.assertTrue(inventory(bot).equals(before), "ingredients returned on logout");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void craftingRemaindersReturnThroughPlayerMenu(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftCake", stack(Items.MILK_BUCKET, 3), stack(Items.SUGAR, 2),
            stack(Items.WHEAT, 3), stack(Items.EGG, 1));
        helper.setBlock(TABLE, Blocks.CRAFTING_TABLE);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.CAKE), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 1 cake");
            helper.assertTrue(inventory(bot).equals(Map.of(new ItemKey(new ItemStack(Items.CAKE)), 1L,
                new ItemKey(new ItemStack(Items.BUCKET)), 3L)), "cake and buckets: " + inventory(bot));
            BotManager.despawn("BnCraftCake");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 600)
    public static void walksToVisibleTableOutsideReach(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftWalk", stack(Items.OAK_LOG, 2));
        bot.teleportTo(helper.getLevel(), helper.absoluteVec(new Vec3(4.5, 1, 1.5)).x,
            bot.getY(), helper.absoluteVec(new Vec3(4.5, 1, 1.5)).z, 0, 0);
        BlockPos distant = new BlockPos(4, 1, 6);
        helper.setBlock(distant, Blocks.CRAFTING_TABLE);
        helper.assertTrue(!bot.canInteractWithBlock(helper.absolutePos(distant), -0.5)
            && CraftStations.visible(bot).containsKey("crafting_table"), "visible but outside reach");
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 1 wooden_pickaxe");
            helper.assertTrue(bot.getZ() > helper.absoluteVec(new Vec3(4.5, 1, 1.5)).z + 1, "walked toward station");
            helper.assertBlockPresent(Blocks.CRAFTING_TABLE, distant);
            BotManager.despawn("BnCraftWalk");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void replacingCraftTaskClosesGridAndReportsOnce(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftReplace", stack(Items.OAK_LOG, 3));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(bot.inventoryMenu.getSlot(1).hasItem(), "grid filled"))
            .thenExecute(() -> {
                bot.controller().goTo(bot.blockPosition(), message -> {});
                done(helper, bot, reports, "failed: step 1 minecraft:oak_planks: cancelled");
                helper.assertTrue(inventory(bot).equals(before), "ingredients returned before replacement task");
                BotManager.despawn("BnCraftReplace");
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void deathReturnsGridBeforeNormalInventoryDrops(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftDeath", stack(Items.OAK_LOG, 3));
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(bot.inventoryMenu.getSlot(1).hasItem(), "grid filled"))
            .thenExecute(() -> {
                bot.die(bot.damageSources().generic());
                done(helper, bot, reports, "failed: step 1 minecraft:oak_planks: cancelled");
                long logs = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    bot.getBoundingBox().inflate(3)).stream().map(net.minecraft.world.entity.item.ItemEntity::getItem)
                    .filter(stack -> stack.is(Items.OAK_LOG)).mapToLong(ItemStack::getCount).sum();
                helper.assertTrue(logs == 3, "all logs dropped through player death: " + logs);
            })
            .thenSucceed();
    }

    @GameTest(template = "arena", timeoutTicks = 80)
    public static void unsupportedStationCannotReplaceMissingRunnableRecipeInputs(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftCut", stack(Items.STONE, 1), stack(Items.STONECUTTER, 1));
        var before = inventory(bot);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.STONE_STAIRS), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "blocked: missing ");
            helper.assertTrue(inventory(bot).equals(before), "no unsupported output or ingredient movement");
            BotManager.despawn("BnCraftCut");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 16000)
    public static void cookingBatchLargerThanSlotRunsInMultipleLoads(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftBatch", stack(Items.RAW_IRON, 64), stack(Items.RAW_IRON, 1), stack(Items.COAL, 9));
        helper.setBlock(TABLE, Blocks.FURNACE);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.IRON_INGOT), 65, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 65 iron_ingot");
            helper.assertTrue(helper.getTick() >= 65 * 200, "all 65 items cooked in real time");
            helper.assertTrue(inventory(bot).equals(Map.of(new ItemKey(new ItemStack(Items.IRON_INGOT)), 65L)),
                "batched cooking balance: " + inventory(bot));
            BotManager.despawn("BnCraftBatch");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 600)
    public static void sameItemCanBeBothCookingInputAndFuel(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftWoodFuel", stack(Items.OAK_LOG, 2));
        helper.setBlock(TABLE, Blocks.FURNACE);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.CHARCOAL), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 1 charcoal");
            helper.assertTrue(inventory(bot).equals(Map.of(new ItemKey(new ItemStack(Items.CHARCOAL)), 1L)),
                "one log smelted, one burned: " + inventory(bot));
            BotManager.despawn("BnCraftWoodFuel");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void recipeBookUsesTheIngredientVariantChosenByPlan(GameTestHelper helper) {
        BotPlayer bot = bot(helper, "BnCraftVariant", stack(Items.OAK_PLANKS, 4), stack(Items.BIRCH_PLANKS, 4));
        var plan = plan(bot, Items.STICK, 8);
        var expected = new TreeMap<>(inventory(bot));
        plan.taken().forEach((key, value) -> expected.merge(key, -value, Long::sum));
        plan.leftovers().forEach((key, value) -> expected.merge(key, value, Long::sum));
        expected.merge(new ItemKey(new ItemStack(Items.STICK)), 8L, Long::sum);
        expected.values().removeIf(value -> value == 0);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.STICK), 8, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 8 stick");
            helper.assertTrue(inventory(bot).equals(expected), "actual ingredients match plan: " + inventory(bot) + " vs " + expected);
            BotManager.despawn("BnCraftVariant");
        });
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void namedIngredientsFollowTheSamePlacementPath(GameTestHelper helper) {
        ItemStack logs = stack(Items.OAK_LOG, 3);
        logs.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("wood"));
        BotPlayer bot = bot(helper, "BnCraftNamed", logs);
        List<String> reports = new ArrayList<>();
        bot.controller().craft(new ItemStack(Items.WOODEN_PICKAXE), 1, reports::add);
        helper.succeedWhen(() -> {
            done(helper, bot, reports, "crafted 1 wooden_pickaxe");
            helper.assertTrue(bot.getInventory().countItem(Items.WOODEN_PICKAXE) == 1
                && bot.getInventory().countItem(Items.CRAFTING_TABLE) == 1, "named logs really crafted");
            BotManager.despawn("BnCraftNamed");
        });
    }
}
