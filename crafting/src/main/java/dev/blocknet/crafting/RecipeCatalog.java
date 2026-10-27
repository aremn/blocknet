/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Immutable index. Rebuild on recipe reload; construction is separate from the bounded search. */
public final class RecipeCatalog {
    private final Map<ItemKey, List<Process>> processes = new HashMap<>();
    private final Map<ItemKey, Set<String>> unsupported = new HashMap<>();

    private record Dependency(int process, int gate, Process.Input input) {
    }

    private final Map<Item, List<Dependency>> dependents = new HashMap<>();
    private final List<Process> allProcesses = new ArrayList<>();
    private final List<Dependency> customInputs = new ArrayList<>();

    private final int[] requirements;
    private int gateCount;

    public RecipeCatalog(ServerLevel level) {
        this(level, level.getRecipeManager().getRecipes());
    }

    public RecipeCatalog(ServerLevel level, Collection<RecipeHolder<?>> recipes) {
        recipes.stream().sorted(Comparator.comparing(holder -> holder.id().toString())).forEach(holder -> {
            Recipe<?> recipe = holder.value();
            if (recipe instanceof CustomRecipe || recipe.isSpecial()) {
                return;
            }
            var result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) {
                return;
            }
            Process process = Process.from(holder, level);
            if (process != null) {
                processes.computeIfAbsent(process.output, key -> new ArrayList<>()).add(process);
            } else {
                unsupported.computeIfAbsent(new ItemKey(result), key -> new TreeSet<>())
                    .add(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
            }
        });
        // Index ingredient OR-gates once, outside the search budget. Each reachable item
        // visits only interested recipes; no repeated whole-catalog scans on the server tick.
        processes.values().forEach(allProcesses::addAll);
        allProcesses.sort(Comparator.comparing(process -> process.id().toString()));
        requirements = new int[allProcesses.size()];
        for (int processIndex = 0; processIndex < allProcesses.size(); processIndex++) {
            Process process = allProcesses.get(processIndex);
            for (var input : process.inputs) {
                if (!input.ingredient().isEmpty()) {
                    Dependency dependency = new Dependency(processIndex, gateCount++, input);
                    requirements[processIndex]++;
                    if (input.ingredient().isCustom()) {
                        customInputs.add(dependency);
                        continue;
                    }
                    Arrays.stream(input.ingredient().getItems())
                        .filter(stack -> !stack.isEmpty())
                        .map(ItemStack::getItem)
                        .distinct()
                        .forEach(item
                            -> dependents.computeIfAbsent(item, key -> new ArrayList<>()).add(dependency));
                }
            }
            if (process.stationItem != null) {
                requirements[processIndex]++;
                dependents.computeIfAbsent(process.stationItem, key -> new ArrayList<>())
                    .add(new Dependency(processIndex, gateCount++, null));
            }
        }
    }

    /** Quantity-free closure: an optimistic feasibility filter, never proof of sufficient stock. */
    Set<ItemKey> reachable(Set<ItemKey> supplied, Set<String> stations, Runnable checkpoint) {
        Set<ItemKey> reached = new HashSet<>(supplied);
        Deque<ItemKey> queue = new ArrayDeque<>(new TreeSet<>(supplied));
        boolean[] satisfied = new boolean[gateCount];
        int[] remaining = requirements.clone();
        for (var dependencies : dependents.values()) {
            for (var dependency : dependencies) {
                if (dependency.input == null && stations.contains(allProcesses.get(dependency.process).station)) {
                    satisfied[dependency.gate] = true;
                    if (--remaining[dependency.process] == 0) {
                        ItemKey output = allProcesses.get(dependency.process).output;
                        if (reached.add(output)) {
                            queue.addLast(output);
                        }
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            checkpoint.run();
            var stack = queue.removeFirst().stack();
            List<Dependency> interested =
                new ArrayList<>(dependents.getOrDefault(stack.getItem(), List.of()));
            interested.addAll(customInputs);
            for (var dependency : interested) {
                if (satisfied[dependency.gate]
                    || dependency.input != null && !dependency.input.ingredient().test(stack)) {
                    continue;
                }
                satisfied[dependency.gate] = true;
                if (--remaining[dependency.process] == 0) {
                    ItemKey output = allProcesses.get(dependency.process).output;
                    if (reached.add(output)) {
                        queue.addLast(output);
                    }
                }
            }
        }
        return reached;
    }

    public List<Process> processes(ItemKey key) {
        return List.copyOf(processes.getOrDefault(key, List.of()));
    }
    Set<String> hints(ItemKey key) {
        return unsupported.getOrDefault(key, Set.of());
    }
}
