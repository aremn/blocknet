/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;

/** Server-thread-only recipe index cache. RecipeManager replaces its recipe collection on reload. */
public final class PlannerCatalogs {
    private record Cached(Object recipes, RecipeCatalog catalog) {
    }

    private static final Map<RecipeManager, Cached> CACHE = new WeakHashMap<>();

    private PlannerCatalogs() {
    }

    public static RecipeCatalog get(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("server thread required");
        }
        var recipeManager = level.getRecipeManager();
        // A reload replaces the immutable byName map; recipe holder equality detects replacement.
        var recipes = List.copyOf(recipeManager.getRecipes());
        Cached cached = CACHE.get(recipeManager);
        if (cached == null || !cached.recipes.equals(recipes)) {
            cached = new Cached(recipes, new RecipeCatalog(level));
            CACHE.put(recipeManager, cached);
        }
        return cached.catalog;
    }
}
