// SPDX-License-Identifier: MIT
package dev.blocknet.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * GameTest scene setup only, loaded by name when the fixture mod is present: a player would encode
 * this pattern at a terminal; the test scene hands it to the provider directly.
 */
public final class Ae2TestFixtures {
    private Ae2TestFixtures() {}

    /** Puts an encoded "oak log -> 4 oak planks" crafting pattern into the pattern provider at {@code pos}. */
    public static void installPlanksPattern(ServerLevel level, BlockPos pos) {
        var recipe = level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
            .filter(r -> r.value().getResultItem(level.registryAccess()).is(Items.OAK_PLANKS))
            .filter(r -> r.value().getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Items.OAK_LOG))))
            .findFirst().orElseThrow();
        var in = new ItemStack[9];
        java.util.Arrays.fill(in, ItemStack.EMPTY);
        in[0] = new ItemStack(Items.OAK_LOG);
        var pattern = PatternDetailsHelper.encodeCraftingPattern(recipe, in, new ItemStack(Items.OAK_PLANKS, 4), false, false);
        var provider = (PatternProviderBlockEntity) level.getBlockEntity(pos);
        provider.getLogic().getPatternInv().addItems(pattern);
    }
}
