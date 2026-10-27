package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.crafting.CraftPlan;
import dev.blocknet.crafting.CraftingCalculation;
import dev.blocknet.crafting.ItemKey;
import dev.blocknet.crafting.PlannerCatalogs;
import dev.blocknet.crafting.RecipeCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftPlanGameTests {
    private static final String FIXTURE_NAMESPACE = "ae2";

    private CraftPlanGameTests() {
    }

    private static ItemStack stack(Item item, int count) {
        return new ItemStack(item, count);
    }

    private static CraftPlan plan(GameTestHelper helper, Item target, int count, ItemStack... inventory) {
        var catalog = PlannerCatalogs.get(helper.getLevel());
        var copies = Arrays.stream(inventory).map(ItemStack::copy).toList();
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(new ItemStack(target), count, List.of(inventory));
        for (int index = 0; index < inventory.length; index++) {
            helper.assertTrue(
                ItemStack.matches(inventory[index], copies.get(index)), "planning mutated inventory");
        }
        Blocknet.LOGGER.info("Craft plan sample:\n{}", plan.text());
        return plan;
    }

    private static long amount(Map<ItemKey, Long> map, Item item) {
        return map.getOrDefault(new ItemKey(new ItemStack(item)), 0L);
    }

    private static void ok(GameTestHelper helper, CraftPlan plan) {
        helper.assertTrue(plan.status() == CraftPlan.Status.OK, plan.text());
    }

    @GameTest(template = "arena")
    public static void woodenToolIncludesStationAndExactMaterialBalance(GameTestHelper helper) {
        // Vanilla requires 9 planks, so two logs without a table cannot possibly suffice.
        var plan = plan(helper, Items.WOODEN_PICKAXE, 1, stack(Items.OAK_LOG, 3));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 4, plan.text());
        var recipeIds = plan.steps().stream().map(step -> step.recipeId().getPath()).toList();
        helper.assertTrue(recipeIds.equals(List.of("oak_planks", "stick", "crafting_table", "wooden_pickaxe")),
            plan.text());
        helper.assertTrue(
            plan.steps().stream().map(CraftPlan.Step::times).toList().equals(List.of(3L, 1L, 1L, 1L)),
            plan.text());
        helper.assertTrue(amount(plan.taken(), Items.OAK_LOG) == 3 && plan.taken().size() == 1, plan.text());
        helper.assertTrue(amount(plan.leftovers(), Items.OAK_PLANKS) == 3
                && amount(plan.leftovers(), Items.STICK) == 2 && plan.leftovers().size() == 2,
            plan.text());
        helper.assertTrue(plan.neededStations().equals(Set.of("crafting_table")), plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void twoLogsWorkWhenTableAlreadyOwned(GameTestHelper helper) {
        var plan =
            plan(helper, Items.WOODEN_PICKAXE, 1, stack(Items.OAK_LOG, 2), stack(Items.CRAFTING_TABLE, 1));
        ok(helper, plan);
        helper.assertTrue(
            amount(plan.leftovers(), Items.OAK_PLANKS) == 3 && amount(plan.leftovers(), Items.STICK) == 2,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void missingWoodIsCountedAndTagged(GameTestHelper helper) {
        var plan = plan(helper, Items.WOODEN_PICKAXE, 1, stack(Items.OAK_LOG, 1));
        helper.assertTrue(plan.status() == CraftPlan.Status.MISSING
                && plan.missing().values().stream().mapToLong(Long::longValue).sum() == 2,
            plan.text());
        helper.assertTrue(plan.missing().keySet().stream()
            .anyMatch(label -> label.startsWith("#") && label.contains("logs")), plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void twoLogsCannotPayForToolAndTable(GameTestHelper helper) {
        var plan = plan(helper, Items.WOODEN_PICKAXE, 1, stack(Items.OAK_LOG, 2));
        helper.assertTrue(plan.status() == CraftPlan.Status.MISSING
                && plan.missing().values().stream().mapToLong(Long::longValue).sum() == 1,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void substitutesWoodThroughTags(GameTestHelper helper) {
        var plan = plan(helper, Items.WOODEN_PICKAXE, 1, stack(Items.BIRCH_LOG, 3));
        ok(helper, plan);
        helper.assertTrue(
            amount(plan.taken(), Items.BIRCH_LOG) == 3 && amount(plan.taken(), Items.OAK_LOG) == 0,
            plan.text());
        helper.assertTrue(plan.steps().getFirst().recipeId().getPath().equals("birch_planks"), plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void smeltingPlansFuelAndMissingFurnaceMaterials(GameTestHelper helper) {
        var missing = plan(helper, Items.IRON_PICKAXE, 1, stack(Items.RAW_IRON, 3), stack(Items.COAL, 1),
            stack(Items.OAK_LOG, 2));
        helper.assertTrue(missing.status() == CraftPlan.Status.MISSING
                && missing.text().contains("cobblestone")
                && missing.neededStations().equals(Set.of("furnace", "crafting_table")),
            missing.text());
        var plan = plan(helper, Items.IRON_PICKAXE, 1, stack(Items.RAW_IRON, 3), stack(Items.COAL, 1),
            stack(Items.OAK_LOG, 2), stack(Items.COBBLESTONE, 8));
        ok(helper, plan);
        helper.assertTrue(amount(plan.taken(), Items.RAW_IRON) == 3 && amount(plan.taken(), Items.COAL) == 1
                && amount(plan.taken(), Items.COBBLESTONE) == 8,
            plan.text());
        helper.assertTrue(
            plan.neededStations().containsAll(Set.of("furnace", "crafting_table")), plan.text());
        long smeltingOperations = plan.steps().stream().filter(step -> step.station().equals("furnace"))
            .mapToLong(CraftPlan.Step::times).sum();
        helper.assertTrue(smeltingOperations == 3, plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void compressionCycleUsesNuggetsAndTerminates(GameTestHelper helper) {
        var plan = plan(helper, Items.IRON_BLOCK, 1, stack(Items.IRON_NUGGET, 64),
            stack(Items.IRON_NUGGET, 17), stack(Items.CRAFTING_TABLE, 1));
        ok(helper, plan);
        helper.assertTrue(
            amount(plan.taken(), Items.IRON_NUGGET) == 81 && plan.leftovers().isEmpty(), plan.text());
        helper.assertTrue(plan.steps().size() == 2 && plan.steps().getFirst().times() == 9, plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void recipeRemaindersReturnBuckets(GameTestHelper helper) {
        var plan = plan(helper, Items.CAKE, 1, stack(Items.MILK_BUCKET, 3), stack(Items.SUGAR, 2),
            stack(Items.WHEAT, 3), stack(Items.EGG, 1), stack(Items.CRAFTING_TABLE, 1));
        ok(helper, plan);
        helper.assertTrue(amount(plan.leftovers(), Items.BUCKET) == 3, plan.text());
        helper.assertTrue(
            plan.steps().stream().anyMatch(step -> amount(step.outputs(), Items.BUCKET) == 3), plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void batchReturnsNonReusableContainersAllAtOnce(GameTestHelper helper) {
        var plan = plan(helper, Items.CAKE, 3, stack(Items.MILK_BUCKET, 9), stack(Items.SUGAR, 6),
            stack(Items.WHEAT, 9), stack(Items.EGG, 3), stack(Items.CRAFTING_TABLE, 1));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 1 && plan.steps().getFirst().times() == 3
                && amount(plan.steps().getFirst().outputs(), Items.BUCKET) == 9
                && amount(plan.leftovers(), Items.BUCKET) == 9,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void discoversForeignCraftingRecipeAtRuntime(GameTestHelper helper) {
        var holder = helper.getLevel().getRecipeManager().getRecipes().stream()
            .filter(candidate -> candidate.id().getNamespace().equals(FIXTURE_NAMESPACE)
                && candidate.value() instanceof CraftingRecipe && !candidate.value().isSpecial()
                && !candidate.value().getIngredients().isEmpty()
                && candidate.value().getIngredients().stream().filter(ingredient -> !ingredient.isEmpty())
                    .allMatch(ingredient -> ingredient.getItems().length > 0)
                && !candidate.value().getResultItem(helper.getLevel().registryAccess()).isEmpty())
            .sorted(Comparator.comparing(candidate -> candidate.id().toString())).findFirst().orElseThrow();
        List<ItemStack> inventory = new ArrayList<>();
        inventory.add(stack(Items.CRAFTING_TABLE, 1));
        for (var ingredient : holder.value().getIngredients()) {
            if (!ingredient.isEmpty()) {
                inventory.add(ingredient.getItems()[0].copyWithCount(1));
            }
        }
        var result = holder.value().getResultItem(helper.getLevel().registryAccess());
        var plan = new CraftingCalculation(helper.getLevel(), PlannerCatalogs.get(helper.getLevel()))
            .plan(result, result.getCount(), inventory);
        ok(helper, plan);
        helper.assertTrue(
            plan.steps().stream().anyMatch(step -> step.recipeId().getNamespace().equals(FIXTURE_NAMESPACE)),
            plan.text());
        Blocknet.LOGGER.info("Foreign recipe plan:\n{}", plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void deepRequestBatchesMissingAndAvailableMaterials(GameTestHelper helper) {
        var catalog = PlannerCatalogs.get(helper.getLevel());
        long start = System.nanoTime();
        var missing =
            new CraftingCalculation(helper.getLevel(), catalog)
                .plan(stack(Items.PISTON, 1), 64,
                    List.of(stack(Items.OAK_LOG, 64), stack(Items.RAW_IRON, 64), stack(Items.COAL, 8),
                        stack(Items.REDSTONE, 64), stack(Items.COBBLESTONE, 64)));
        long missingNanos = System.nanoTime() - start;
        Blocknet.LOGGER.info("Piston missing: {} ms\n{}", missingNanos / 1_000_000.0, missing.text());
        helper.assertTrue(missing.status() == CraftPlan.Status.MISSING, missing.text());
        helper.assertTrue(missing.missing().getOrDefault("#c:cobblestones/normal", 0L) == 192
                && missing.missing().getOrDefault("#minecraft:stone_crafting_materials", 0L) == 8
                && missing.missing().size() == 2,
            missing.text());
        helper.assertTrue(missing.missing().values().stream().mapToLong(Long::longValue).sum() == 200,
            "192 for pistons plus 8 for the furnace: " + missing.text());
        helper.assertTrue(missingNanos < 25_000_000L, "piston missing took " + missingNanos + " ns");

        start = System.nanoTime();
        var available =
            new CraftingCalculation(helper.getLevel(), catalog)
                .plan(stack(Items.PISTON, 1), 64,
                    List.of(stack(Items.OAK_LOG, 192), stack(Items.RAW_IRON, 64), stack(Items.COAL, 8),
                        stack(Items.REDSTONE, 64), stack(Items.COBBLESTONE, 264)));
        long availableNanos = System.nanoTime() - start;
        Blocknet.LOGGER.info("Piston ok: {} ms\n{}", availableNanos / 1_000_000.0, available.text());
        ok(helper, available);
        helper.assertTrue(availableNanos < 25_000_000L, "piston ok took " + availableNanos + " ns");
        Map<String, Long> operations = new TreeMap<>();
        for (var step : available.steps()) {
            operations.merge(step.recipeId().getPath(), step.times(), Long::sum);
        }
        helper.assertTrue(operations.equals(Map.of("oak_planks", 49L, "crafting_table", 1L, "furnace", 1L,
                "iron_ingot_from_smelting_raw_iron", 64L, "piston", 64L)),
            available.text());
        helper.assertTrue(amount(available.taken(), Items.OAK_LOG) == 49
                && amount(available.taken(), Items.RAW_IRON) == 64
                && amount(available.taken(), Items.COAL) == 8
                && amount(available.taken(), Items.REDSTONE) == 64
                && amount(available.taken(), Items.COBBLESTONE) == 264 && available.taken().size() == 5
                && available.leftovers().isEmpty(),
            available.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void tinySearchLimitsReturnNoPartialPlan(GameTestHelper helper) {
        var catalog = PlannerCatalogs.get(helper.getLevel());
        var limited =
            new CraftingCalculation(helper.getLevel(), catalog, new CraftingCalculation.Limits(2, 1, 1, 1))
                .plan(stack(Items.PISTON, 1), 64, List.of());
        helper.assertTrue(limited.status() == CraftPlan.Status.LIMIT
                && limited.text().contains("search limit") && limited.steps().isEmpty()
                && limited.taken().isEmpty() && limited.missing().isEmpty() && limited.leftovers().isEmpty()
                && limited.neededStations().isEmpty(),
            limited.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void keepsDataComponentsDistinct(GameTestHelper helper) {
        var named = stack(Items.STICK, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("kept"));
        var plan = new CraftingCalculation(helper.getLevel(), PlannerCatalogs.get(helper.getLevel()))
            .plan(stack(Items.STICK, 1), 2, List.of(named, stack(Items.STICK, 2)), Set.of(), List.of(),
                CraftPlan.Mode.OBTAIN);
        ok(helper, plan);
        helper.assertTrue(
            plan.steps().isEmpty() && plan.taken().size() == 1 && amount(plan.taken(), Items.STICK) == 2,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void craftKeepsOriginalTargetSeparateFromNewByproducts(GameTestHelper helper) {
        var ingredients = NonNullList.<Ingredient>create();
        ingredients.add(Ingredient.of(Items.MILK_BUCKET));
        var intermediate = new ShapelessRecipe("", CraftingBookCategory.MISC, stack(Items.STONE, 1), ingredients) {
            @Override
            public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
                return NonNullList.withSize(input.size(), stack(Items.DIAMOND, 1));
            }
        };
        var holder = new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Blocknet.MOD_ID, "target_byproduct"), intermediate);
        var catalog = new RecipeCatalog(helper.getLevel(), List.of(holder,
            fixture("target_order", Items.DIAMOND, Ingredient.of(Items.STONE))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 3, List.of(stack(Items.DIAMOND, 5), stack(Items.MILK_BUCKET, 3)));
        ok(helper, plan);
        helper.assertTrue(amount(plan.taken(), Items.DIAMOND) == 0
            && amount(plan.taken(), Items.MILK_BUCKET) == 3 && amount(plan.leftovers(), Items.DIAMOND) == 3,
            "three recipe results plus three new byproducts, keeping five old diamonds: " + plan.text());
        helper.assertTrue(plan.steps().stream().mapToLong(step -> amount(step.outputs(), Items.DIAMOND)).sum() == 6,
            plan.text());
        helper.succeed();
    }

    private static RecipeHolder<?> fixture(String id, Item result, Ingredient... ingredients) {
        var list = NonNullList.<Ingredient>create();
        list.addAll(List.of(ingredients));
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Blocknet.MOD_ID, id),
            new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(result), list));
    }

    @GameTest(template = "arena")
    public static void failedRecipeRollsBackInventoryAndSteps(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(),
            List.of(fixture("a_fails", Items.DIAMOND, Ingredient.of(Items.OAK_LOG),
                        Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.IRON_INGOT)),
                fixture("b_succeeds", Items.DIAMOND, Ingredient.of(Items.OAK_LOG),
                    Ingredient.of(Items.COBBLESTONE))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 1,
                List.of(stack(Items.OAK_LOG, 1), stack(Items.COBBLESTONE, 1),
                    stack(Items.IRON_INGOT, 1)));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 1
                && plan.steps().getFirst().recipeId().getPath().equals("b_succeeds")
                && plan.missing().isEmpty() && amount(plan.taken(), Items.OAK_LOG) == 1
                && amount(plan.taken(), Items.IRON_INGOT) == 0,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void ingredientChoiceChecksLaterSlots(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(),
            List.of(fixture("substitutes", Items.DIAMOND, Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS),
                Ingredient.of(Items.BIRCH_PLANKS))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 1,
                List.of(stack(Items.BIRCH_PLANKS, 1), stack(Items.OAK_PLANKS, 1)));
        ok(helper, plan);
        helper.assertTrue(plan.taken().size() == 2 && plan.steps().size() == 1, plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void failedBatchRollsBackBeforeTryingAnotherRecipe(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(),
            List.of(fixture("a_batch_fails", Items.DIAMOND, Ingredient.of(Items.OAK_LOG),
                        Ingredient.of(Items.IRON_INGOT), Ingredient.of(Items.IRON_INGOT)),
                fixture("b_batch_succeeds", Items.DIAMOND, Ingredient.of(Items.OAK_LOG),
                    Ingredient.of(Items.COBBLESTONE))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 3,
                List.of(stack(Items.OAK_LOG, 3), stack(Items.COBBLESTONE, 3),
                    stack(Items.IRON_INGOT, 3)));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 1 && plan.steps().getFirst().times() == 3
                && plan.steps().getFirst().recipeId().getPath().equals("b_batch_succeeds")
                && plan.missing().isEmpty() && amount(plan.taken(), Items.OAK_LOG) == 3
                && amount(plan.taken(), Items.COBBLESTONE) == 3
                && amount(plan.taken(), Items.IRON_INGOT) == 0,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void batchedIngredientChoiceChecksLaterSlots(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(),
            List.of(fixture("batch_substitutes", Items.DIAMOND,
                Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Ingredient.of(Items.BIRCH_PLANKS))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 3,
                List.of(stack(Items.BIRCH_PLANKS, 3), stack(Items.OAK_PLANKS, 3)));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 1 && plan.steps().getFirst().times() == 3
                && amount(plan.taken(), Items.BIRCH_PLANKS) == 3
                && amount(plan.taken(), Items.OAK_PLANKS) == 3,
            plan.text());
        helper.succeed();
    }

    private static RecipeHolder<?> reusableContainerRecipe(int containerSlots) {
        var ingredients = NonNullList.<Ingredient>create();
        for (int slot = 0; slot < containerSlots; slot++) {
            ingredients.add(Ingredient.of(Items.MILK_BUCKET));
        }
        ingredients.add(Ingredient.of(Items.SUGAR));
        var recipe =
            new ShapelessRecipe("", CraftingBookCategory.MISC, stack(Items.DIAMOND, 1), ingredients) {
                @Override
                public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
                    var remainders = NonNullList.withSize(input.size(), ItemStack.EMPTY);
                    for (int slot = 0; slot < input.size(); slot++) {
                        if (input.getItem(slot).is(Items.MILK_BUCKET)) {
                            remainders.set(slot, input.getItem(slot).copyWithCount(1));
                        }
                    }
                    return remainders;
                }
            };
        return new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Blocknet.MOD_ID, "reusable"), recipe);
    }

    @GameTest(template = "arena")
    public static void returnedContainerFeedsLaterOperations(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(), List.of(reusableContainerRecipe(1)));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 3,
                List.of(stack(Items.MILK_BUCKET, 1), stack(Items.SUGAR, 3)));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 3
                && plan.steps().stream().allMatch(step -> step.times() == 1)
                && amount(plan.taken(), Items.MILK_BUCKET) == 1 && amount(plan.taken(), Items.SUGAR) == 3
                && amount(plan.leftovers(), Items.MILK_BUCKET) == 1 && plan.leftovers().size() == 1,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void returnedContainerCannotFeedAnotherSlotInSameOperation(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(), List.of(reusableContainerRecipe(2)));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 3,
                List.of(stack(Items.MILK_BUCKET, 1), stack(Items.SUGAR, 3)));
        helper.assertTrue(plan.status() == CraftPlan.Status.MISSING
                && plan.missing().equals(Map.of("milk_bucket", 1L))
                && plan.steps().stream().mapToLong(CraftPlan.Step::times).sum() == 3
                && amount(plan.leftovers(), Items.MILK_BUCKET) == 2,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void missingBatchStillChecksLaterSlotRestrictions(GameTestHelper helper) {
        var catalog = new RecipeCatalog(helper.getLevel(),
            List.of(fixture("missing_substitutes", Items.DIAMOND, Ingredient.of(Items.IRON_INGOT),
                Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS), Ingredient.of(Items.BIRCH_PLANKS))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog)
            .plan(stack(Items.DIAMOND, 1), 3,
                List.of(stack(Items.BIRCH_PLANKS, 3), stack(Items.OAK_PLANKS, 3)));
        helper.assertTrue(plan.status() == CraftPlan.Status.MISSING
                && plan.missing().equals(Map.of("iron_ingot", 3L)) && plan.steps().size() == 1
                && plan.steps().getFirst().times() == 3 && amount(plan.taken(), Items.BIRCH_PLANKS) == 3
                && amount(plan.taken(), Items.OAK_PLANKS) == 3,
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void earlierInputCraftCanSupplyComponentDistinctLaterInput(GameTestHelper helper) {
        var ingredients = NonNullList.<Ingredient>create();
        ingredients.add(Ingredient.of(Items.MILK_BUCKET));
        var namedStick = stack(Items.STICK, 1);
        namedStick.set(DataComponents.CUSTOM_NAME, Component.literal("byproduct"));
        var intermediate = new ShapelessRecipe("", CraftingBookCategory.MISC, stack(Items.STONE, 1), ingredients) {
            @Override
            public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
                return NonNullList.withSize(input.size(), namedStick.copy());
            }
        };
        var catalog = new RecipeCatalog(helper.getLevel(), List.of(
            new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Blocknet.MOD_ID, "byproduct"), intermediate),
            fixture("uses_byproduct", Items.DIAMOND, Ingredient.of(Items.STONE), Ingredient.of(Items.STICK))));
        var plan = new CraftingCalculation(helper.getLevel(), catalog).plan(stack(Items.DIAMOND, 1), 2,
            List.of(stack(Items.MILK_BUCKET, 2), stack(Items.STICK, 1)));
        ok(helper, plan);
        helper.assertTrue(plan.steps().size() == 2 && amount(plan.taken(), Items.MILK_BUCKET) == 2
            && amount(plan.taken(), Items.STICK) == 0
            && plan.steps().getLast().inputs().getOrDefault(new ItemKey(namedStick), 0L) == 2
            && plan.leftovers().isEmpty(), plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void acceptsNamedIngredientAndOwnedStation(GameTestHelper helper) {
        var logs = stack(Items.OAK_LOG, 2);
        logs.set(DataComponents.CUSTOM_NAME, Component.literal("wood"));
        var table = stack(Items.CRAFTING_TABLE, 1);
        table.set(DataComponents.CUSTOM_NAME, Component.literal("station"));
        var plan = plan(helper, Items.WOODEN_PICKAXE, 1, logs, table);
        ok(helper, plan);
        helper.assertTrue(plan.taken().keySet().stream().anyMatch(key -> key.equals(new ItemKey(logs)))
                && plan.taken().keySet().stream().anyMatch(key -> key.equals(new ItemKey(table))),
            plan.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void plansCuttingSmithingAndOtherCookingStations(GameTestHelper helper) {
        var cut = plan(helper, Items.STONE_STAIRS, 1, stack(Items.STONE, 1), stack(Items.STONECUTTER, 1));
        ok(helper, cut);
        helper.assertTrue(cut.steps().getFirst().station().equals("stonecutter"), cut.text());
        var smith = plan(helper, Items.NETHERITE_PICKAXE, 1, stack(Items.DIAMOND_PICKAXE, 1),
            stack(Items.NETHERITE_INGOT, 1), stack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1),
            stack(Items.SMITHING_TABLE, 1));
        ok(helper, smith);
        helper.assertTrue(smith.steps().getFirst().station().equals("smithing_table"), smith.text());
        var blast = plan(helper, Items.IRON_INGOT, 9, stack(Items.RAW_IRON, 9), stack(Items.BLAST_FURNACE, 1),
            stack(Items.COAL, 2));
        ok(helper, blast);
        helper.assertTrue(amount(blast.taken(), Items.COAL) == 2
                && blast.steps().stream().allMatch(step -> step.station().equals("blast_furnace")),
            blast.text());
        var smoke = plan(
            helper, Items.COOKED_BEEF, 9, stack(Items.BEEF, 9), stack(Items.SMOKER, 1), stack(Items.COAL, 2));
        ok(helper, smoke);
        helper.assertTrue(amount(smoke.taken(), Items.COAL) == 2
                && smoke.steps().stream().allMatch(step -> step.station().equals("smoker")),
            smoke.text());
        var camp = plan(helper, Items.COOKED_BEEF, 1, stack(Items.BEEF, 1), stack(Items.CAMPFIRE, 1));
        ok(helper, camp);
        helper.assertTrue(
            camp.steps().getFirst().station().equals("campfire") && camp.taken().size() == 2, camp.text());
        var lava = plan(helper, Items.IRON_INGOT, 1, stack(Items.RAW_IRON, 1), stack(Items.FURNACE, 1),
            stack(Items.LAVA_BUCKET, 1));
        ok(helper, lava);
        helper.assertTrue(amount(lava.leftovers(), Items.BUCKET) == 1
                && amount(lava.steps().getFirst().outputs(), Items.BUCKET) == 1,
            lava.text());
        helper.succeed();
    }

    @GameTest(template = "arena")
    public static void unsupportedProcessHasTypeHint(GameTestHelper helper) {
        var type = BuiltInRegistries.RECIPE_TYPE.entrySet()
            .stream()
            .filter(entry -> entry.getKey().location().getNamespace().equals(FIXTURE_NAMESPACE))
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .findFirst()
            .orElseThrow();
        // A fixed-output fixture on a real registered foreign recipe type. Some fixture mods
        // expose only dynamic machine recipes with an empty preview result; those are skipped.
        Recipe<SingleRecipeInput> recipe = new Recipe<>() {
            @Override
            public boolean matches(SingleRecipeInput input, Level level) {
                return input.item().is(Items.STONE);
            }

            @Override
            public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider access) {
                return stack(Items.DIAMOND, 1);
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return true;
            }

            @Override
            public ItemStack getResultItem(HolderLookup.Provider access) {
                return stack(Items.DIAMOND, 1);
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                return RecipeSerializer.SHAPELESS_RECIPE;
            }

            @Override
            public RecipeType<?> getType() {
                return type.getValue();
            }

            @Override
            public NonNullList<Ingredient> getIngredients() {
                var inputs = NonNullList.<Ingredient>create();
                inputs.add(Ingredient.of(Items.STONE));
                return inputs;
            }
        };
        var holder =
            new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath(Blocknet.MOD_ID, "unsupported"), recipe);
        var catalog = new RecipeCatalog(helper.getLevel(), List.of(holder));
        var plan =
            new CraftingCalculation(helper.getLevel(), catalog).plan(stack(Items.DIAMOND, 1), 1, List.of());
        helper.assertTrue(plan.status() == CraftPlan.Status.NO_RECIPE
                && plan.hints().stream().anyMatch(
                    hint -> hint.contains("can be made in " + type.getKey().location())),
            plan.text());
        Blocknet.LOGGER.info("Unsupported process hint: {}", plan.text());
        helper.succeed();
    }
}
