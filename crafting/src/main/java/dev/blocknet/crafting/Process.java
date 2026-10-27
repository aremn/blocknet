/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

/** Minecraft recipe adapter; no network, machine grid, or mod-specific knowledge. */
public final class Process {
    public record Input(Ingredient ingredient, String label) {
        boolean test(ItemKey key) {
            return ingredient.test(key.stack());
        }

        static Input of(Ingredient ingredient) {
            String label = "";
            if (!ingredient.isCustom()) {
                label = Arrays.stream(ingredient.getValues())
                    .filter(value -> value instanceof Ingredient.TagValue)
                    .map(value -> "#" + ((Ingredient.TagValue) value).tag().location())
                    .sorted()
                    .findFirst()
                    .orElse("");
            }
            if (label.isEmpty()) {
                label = Arrays.stream(ingredient.getItems())
                    .filter(stack -> !stack.isEmpty())
                    .map(ItemKey::new)
                    .sorted()
                    .map(ItemKey::text)
                    .findFirst()
                    .orElse("ingredient");
            }
            return new Input(ingredient, label);
        }
    }

    final RecipeHolder<?> holder;
    final ItemKey output;
    final int outputCount;
    final String station;
    final Item stationItem;
    final List<Input> inputs;
    final int width;
    final int height;
    final int cookTicks;

    private Process(RecipeHolder<?> holder, ItemStack result, String station, Item stationItem,
        List<Input> inputs, int width, int height, int cookTicks) {
        this.holder = holder;
        output = new ItemKey(result);
        outputCount = result.getCount();
        this.station = station;
        this.stationItem = stationItem;
        this.inputs = List.copyOf(inputs);
        this.width = width;
        this.height = height;
        this.cookTicks = cookTicks;
    }

    public ResourceLocation id() {
        return holder.id();
    }

    public String station() {
        return station;
    }

    public ItemKey output() {
        return output;
    }

    public List<Input> inputs() {
        return inputs;
    }

    static Process from(RecipeHolder<?> holder, ServerLevel level) {
        Recipe<?> recipe = holder.value();
        if (recipe instanceof CustomRecipe || recipe.isSpecial()) {
            return null;
        }
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) {
            return null;
        }
        String station;
        Item stationItem;
        int width = 0;
        int height = 0;
        int cookTicks = 0;
        List<Ingredient> ingredients = new ArrayList<>(recipe.getIngredients());
        if (recipe instanceof CraftingRecipe) {
            // No supported player grid for larger recipes.
            if (!recipe.canCraftInDimensions(3, 3)) {
                return null;
            }
            boolean fitsInventory = recipe.canCraftInDimensions(2, 2);
            station = fitsInventory ? "inventory" : "crafting_table";
            stationItem = fitsInventory ? null : Items.CRAFTING_TABLE;
            if (recipe instanceof ShapedRecipe shaped) {
                width = shaped.getWidth();
                height = shaped.getHeight();
            } else {
                int bestArea = 10;
                for (int gridWidth = 1; gridWidth <= 3; gridWidth++) {
                    for (int gridHeight = 1; gridHeight <= 3; gridHeight++) {
                        if (gridWidth * gridHeight >= ingredients.size() && gridWidth * gridHeight < bestArea
                            && recipe.canCraftInDimensions(gridWidth, gridHeight)) {
                            width = gridWidth;
                            height = gridHeight;
                            bestArea = gridWidth * gridHeight;
                        }
                    }
                }
                if (width == 0) {
                    return null;
                }
            }
        } else if (recipe instanceof AbstractCookingRecipe cooking) {
            if (recipe.getType() == RecipeType.SMELTING) {
                station = "furnace";
                stationItem = Items.FURNACE;
            } else if (recipe.getType() == RecipeType.BLASTING) {
                station = "blast_furnace";
                stationItem = Items.BLAST_FURNACE;
            } else if (recipe.getType() == RecipeType.SMOKING) {
                station = "smoker";
                stationItem = Items.SMOKER;
            } else if (recipe.getType() == RecipeType.CAMPFIRE_COOKING) {
                station = "campfire";
                stationItem = Items.CAMPFIRE;
            } else {
                return null;
            }
            if (recipe.getType() != RecipeType.CAMPFIRE_COOKING) {
                cookTicks = cooking.getCookingTime();
            }
        } else if (recipe instanceof StonecutterRecipe) {
            station = "stonecutter";
            stationItem = Items.STONECUTTER;
        } else if (recipe instanceof SmithingTransformRecipe smith) {
            station = "smithing_table";
            stationItem = Items.SMITHING_TABLE;
            ingredients = List.of(matching(smith::isTemplateIngredient), matching(smith::isBaseIngredient),
                matching(smith::isAdditionIngredient));
        } else {
            return null;
        }
        if (ingredients.isEmpty() || ingredients.stream().allMatch(Ingredient::isEmpty)) {
            return null;
        }
        if (ingredients.stream()
            .filter(ingredient -> !ingredient.isEmpty())
            .anyMatch(ingredient -> ingredient.getItems().length == 0)) {
            return null;
        }
        return new Process(holder, result, station, stationItem, ingredients.stream().map(Input::of).toList(),
            width, height, cookTicks);
    }

    // Smithing exposes slot predicates rather than getIngredients in 1.21.1.
    private static Ingredient matching(Predicate<ItemStack> predicate) {
        return Ingredient.of(BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(predicate));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    Map<ItemKey, Long> outputs(List<ItemStack> selected, ServerLevel level) {
        Recipe recipe = holder.value();
        RecipeInput input;
        if (recipe instanceof CraftingRecipe) {
            List<ItemStack> grid = new ArrayList<>(selected);
            while (grid.size() < width * height) {
                grid.add(ItemStack.EMPTY);
            }
            input = CraftingInput.of(width, height, grid);
        } else if (recipe instanceof SmithingTransformRecipe) {
            input = new SmithingRecipeInput(selected.get(0), selected.get(1), selected.get(2));
        } else {
            input = new SingleRecipeInput(selected.getFirst());
        }
        if (!recipe.matches(input, level)) {
            return Map.of();
        }
        ItemStack result = recipe.assemble(input, level.registryAccess());
        if (result.isEmpty()) {
            return Map.of();
        }
        Map<ItemKey, Long> outputs = new TreeMap<>();
        outputs.put(new ItemKey(result), (long) result.getCount());
        for (ItemStack stack : (List<ItemStack>) recipe.getRemainingItems(input)) {
            if (!stack.isEmpty()) {
                outputs.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return outputs;
    }
}
