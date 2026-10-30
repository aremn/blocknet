/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/** Server-thread-only recipe index cache. RecipeManager replaces its recipe collection on reload. */
public final class PlannerCatalogs {
    private record Cached(Object recipes, RecipeCatalog catalog) {
    }

    private static final Map<RecipeManager, Cached> CACHE = new WeakHashMap<>();
    /** Catalogs being built off the server thread since server start or a recipe reload. */
    private static final Map<RecipeManager, Pending> PENDING = new WeakHashMap<>();

    private record Pending(List<RecipeHolder<?>> recipes, CompletableFuture<RecipeCatalog> catalog) {
    }

    private PlannerCatalogs() {
    }

    /**
     * Starts building the index on its own thread (server thread call). In All the Mods 10 it took
     * 45 s, and building it on the first order froze the whole world that long. The recipes and the
     * registries it reads are immutable until the next reload; get() joins it if asked earlier.
     */
    public static void warm(ServerLevel level) {
        var recipeManager = level.getRecipeManager();
        List<RecipeHolder<?>> recipes = List.copyOf(recipeManager.getRecipes());
        PENDING.put(recipeManager, new Pending(recipes, CompletableFuture.supplyAsync(() -> new RecipeCatalog(level, recipes), task -> {
            Thread thread = new Thread(task, "blocknet-recipe-index");
            thread.setDaemon(true);
            thread.start();
        })));
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
            Pending pending = PENDING.remove(recipeManager);
            RecipeCatalog catalog = null;
            if (pending != null && pending.recipes().equals(recipes)) {
                try {
                    catalog = pending.catalog().join();
                } catch (RuntimeException failed) {
                    catalog = null; // built again below, on this thread
                }
            }
            cached = new Cached(recipes, catalog != null ? catalog : new RecipeCatalog(level));
            CACHE.put(recipeManager, cached);
        }
        return cached.catalog;
    }
}
