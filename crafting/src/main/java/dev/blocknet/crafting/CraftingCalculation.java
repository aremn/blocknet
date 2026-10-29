/*
 * Derived from Applied Energistics 2 (https://github.com/AppliedEnergistics/Applied-Energistics-2)
 * Modified for Blocknet.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package dev.blocknet.crafting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Adapted AE2 CraftingCalculation + CraftingTreeNode + CraftingTreeProcess.
 * Inventory-first nodes, transactional pattern branches, fuzzy ingredient candidates,
 * ancestor recursion guard, process remainders/byproducts, then a missing-items pass.
 * Synchronous and read-only; the deadline is shared by all branch attempts and both passes.
 */
public final class CraftingCalculation {
    public record Limits(int depth, int variants, int operations, long nanos) {
        public static final Limits DEFAULT = new Limits(48, 32, 20_000, 50_000_000L);

        public Limits {
            if (depth < 1 || variants < 1 || operations < 1 || nanos < 1) {
                throw new IllegalArgumentException("limits");
            }
        }
    }

    private record FailedRequest(ItemKey key, long count, Map<ItemKey, Long> original,
        Map<ItemKey, Long> generated, Set<ItemKey> ancestors, Set<String> stations, Map<String, Long> heat) {
    }

    private static class BranchFailure extends RuntimeException {
        BranchFailure() {
            super(null, null, false, false);
        }
    }

    private static final class SearchLimit extends RuntimeException {
        SearchLimit() {
            super(null, null, false, false);
        }
    }

    // Skip choices in later slots when an earlier input cannot be supplied.
    private static final class InputFailure extends BranchFailure {
        final int slot;

        InputFailure(int slot) {
            this.slot = slot;
        }
    }

    private static final class SequentialProcess extends RuntimeException {
        SequentialProcess() {
            super(null, null, false, false);
        }
    }

    private final ServerLevel level;
    private final RecipeCatalog catalog;
    private final Limits limits;
    private final Predicate<Process> supported;
    private final Map<ItemKey, Integer> costs = new HashMap<>();
    private final Set<FailedRequest> failed = new HashSet<>();
    private long deadline;
    private int operations;
    private Set<ItemKey> supplied;
    private Map<ItemKey, Long> carried;
    private List<RememberedSource> sources;
    private Set<ItemKey> reachable;
    private boolean truncated;
    private Set<String> availableStations = Set.of();

    public CraftingCalculation(ServerLevel level, RecipeCatalog catalog) {
        this(level, catalog, Limits.DEFAULT);
    }

    public CraftingCalculation(ServerLevel level, RecipeCatalog catalog, Limits limits) {
        this(level, catalog, limits, process -> true);
    }

    public CraftingCalculation(ServerLevel level, RecipeCatalog catalog, Limits limits, Predicate<Process> supported) {
        this.level = level;
        this.catalog = catalog;
        this.limits = limits;
        this.supported = supported;
    }

    private List<Process> processes(ItemKey key) {
        return catalog.processes(key).stream().filter(supported).toList();
    }

    public CraftPlan plan(ItemStack output, long count, Iterable<ItemStack> inventory) {
        return plan(output, count, inventory, Set.of());
    }

    /** Stations observed by the caller from the player's eyes; never discovers world blocks. */
    public CraftPlan plan(ItemStack output, long count, Iterable<ItemStack> inventory, Set<String> availableStations) {
        return plan(output, count, inventory, availableStations, List.of());
    }

    /** Craft a new batch, preserving the caller's existing target stock. */
    public CraftPlan plan(ItemStack output, long count, Iterable<ItemStack> inventory,
        Set<String> availableStations, List<RememberedSource> sources) {
        return plan(output, count, inventory, availableStations, sources, CraftPlan.Mode.CRAFT);
    }

    public CraftPlan plan(ItemStack output, long count, Iterable<ItemStack> inventory,
        Set<String> availableStations, List<RememberedSource> sources, CraftPlan.Mode mode) {
        this.sources = List.copyOf(sources);
        this.availableStations = Set.copyOf(availableStations);
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("planner requires server thread");
        }
        if (count < 1 || count > 1_000_000) {
            throw new IllegalArgumentException("count must be 1..1000000");
        }
        ItemKey target = new ItemKey(output);
        var initial = new CraftingSimulationState(inventory);
        carried = Map.copyOf(initial.original);
        for (RememberedSource source : sources) {
            source.contents().forEach((key, amount) -> initial.original.merge(key, amount, Long::sum));
        }
        if (mode == CraftPlan.Mode.CRAFT) {
            // Reserve all existing target stock, including remembered stock. It cannot pay for
            // the order, even indirectly through a compression cycle or recipe remainder.
            initial.original.remove(target);
        }
        supplied = initial.keys();
        costs.clear();
        failed.clear();
        operations = 0;
        truncated = false;
        deadline = System.nanoTime() + limits.nanos;
        var state = new CraftingSimulationState(initial);
        CraftPlan.Status status;
        try {
            reachable = catalog.reachable(supplied, availableStations, () -> {
                if (System.nanoTime() >= deadline) {
                    throw new SearchLimit();
                }
            });
            try {
                request(target, count, state, new LinkedHashSet<>(), false, null);
                status = CraftPlan.Status.OK;
            } catch (BranchFailure failure) {
                state = new CraftingSimulationState(initial);
                request(target, count, state, new LinkedHashSet<>(), true, null);
                status = truncated ? CraftPlan.Status.LIMIT : CraftPlan.Status.MISSING;
            }
        } catch (SearchLimit limit) {
            // A partial branch is not a valid material balance. Return no misleading partial crafts.
            state = new CraftingSimulationState(initial);
            status = CraftPlan.Status.LIMIT;
        }
        if (status == CraftPlan.Status.LIMIT) {
            state = new CraftingSimulationState(initial);
        }
        if (status == CraftPlan.Status.MISSING && processes(target).isEmpty()
            && state.missing.containsKey(target.text())) {
            status = CraftPlan.Status.NO_RECIPE;
        }
        List<CraftPlan.Step> steps = compact(state.steps, initial);
        var remaining = new TreeMap<>(state.taken);
        var inventoryTaken = new TreeMap<ItemKey, Long>();
        carried.forEach((key, amount) -> {
            long used = Math.min(amount, remaining.getOrDefault(key, 0L));
            if (used > 0) {
                inventoryTaken.put(key, used);
                remaining.merge(key, -used, Long::sum);
            }
        });
        var withdrawals = new ArrayList<CraftPlan.Withdrawal>();
        for (RememberedSource source : sources) {
            source.contents().forEach((key, amount) -> {
                long used = Math.min(amount, remaining.getOrDefault(key, 0L));
                if (used > 0) {
                    withdrawals.add(new CraftPlan.Withdrawal(source, key, used));
                    remaining.merge(key, -used, Long::sum);
                }
            });
        }
        return new CraftPlan(status, mode, target, count, steps, inventoryTaken, state.missing, state.generated,
            state.stations, List.copyOf(state.hints), withdrawals);
    }

    private void check(int depth) {
        if (depth > limits.depth || ++operations > limits.operations || System.nanoTime() >= deadline) {
            throw new SearchLimit();
        }
    }

    private void request(ItemKey key, long count, CraftingSimulationState state,
        LinkedHashSet<ItemKey> ancestors, boolean simulate, String missingLabel) {
        check(ancestors.size());
        count -= state.extract(key, count);
        if (count == 0) {
            return;
        }
        if (!simulate && !reachable.contains(key)) {
            throw new BranchFailure();
        }
        if (ancestors.contains(key)) {
            missing(key, count, state, simulate, missingLabel);
            return;
        }
        var path = new LinkedHashSet<>(ancestors);
        path.add(key);
        var patterns = new ArrayList<>(processes(key));
        var scores = new HashMap<Process, Integer>();
        for (var process : patterns) {
            scores.put(process, processCost(process, new HashSet<>(path)));
        }
        patterns.sort(Comparator.comparingInt((Process process) -> scores.get(process))
            .thenComparing(process -> process.id().toString()));
        var failureKey =
            new FailedRequest(key, count, Map.copyOf(state.original), Map.copyOf(state.generated),
                Set.copyOf(path), Set.copyOf(state.stations), Map.copyOf(state.heat));
        if (!simulate && failed.contains(failureKey)) {
            throw new BranchFailure();
        }
        int tested = 0;
        for (Process process : patterns) {
            check(path.size());
            if (++tested > limits.variants) {
                truncated = true;
                break;
            }
            var child = new CraftingSimulationState(state);
            try {
                produce(process, key, count, child, path, false);
                state.apply(child);
                return;
            } catch (BranchFailure failure) {
                // Discard inventory, fuel, stations and steps together.
            }
        }
        if (!simulate) {
            failed.add(failureKey);
            throw new BranchFailure();
        }
        for (Process process : patterns.subList(0, Math.min(patterns.size(), limits.variants))) {
            // Inventory-backed unpacking was already tried by the strict pass. Without the
            // packed item, asking for it would inflate the raw-material deficit through a cycle.
            if (process.outputCount > process.inputs.size() && process.inputs.size() == 1
                && Arrays.stream(process.inputs.getFirst().ingredient().getItems()).filter(s -> !s.isEmpty())
                    .anyMatch(s -> processes(new ItemKey(s)).stream().anyMatch(reverse -> reverse.inputs.stream().anyMatch(i -> i.test(key)))))
                continue;
            // The strict pass tried all viable branches. As in AE2 simulation mode, now expose
            // missing raw inputs on the preferred pattern instead of pretending crafts ran.
            var child = new CraftingSimulationState(state);
            try {
                produce(process, key, count, child, path, true);
                // A compression/decompression cycle cannot acquire its own missing ingredient.
                // Report the original deficit (one raw iron), not a hypothetical nine-item block.
                if (path.stream().anyMatch(ancestor -> child.missing.getOrDefault(ancestor.text(), 0L)
                        > state.missing.getOrDefault(ancestor.text(), 0L))) continue;
                state.apply(child);
                return;
            } catch (BranchFailure failure) {
                // A dynamic result did not match the requested item.
            }
        }
        missing(key, count, state, true, missingLabel);
    }

    private void missing(
        ItemKey key, long count, CraftingSimulationState state, boolean simulate, String label) {
        if (!simulate) {
            throw new BranchFailure();
        }
        state.missing.merge(label == null ? key.text() : label, count, Long::sum);
        for (String type : catalog.hints(key)) {
            state.hints.add(key.text() + ": can be made in " + type);
        }
        for (Process process : catalog.processes(key)) {
            if (!supported.test(process)) {
                state.hints.add(key.text() + ": can be made in " + process.type());
            }
        }
    }

    private void produce(Process process, ItemKey key, long count, CraftingSimulationState state,
        LinkedHashSet<ItemKey> path, boolean simulate) {
        long times = (count + process.outputCount - 1) / process.outputCount;
        try {
            selectBatch(process, times, state, path, simulate);
        } catch (SequentialProcess sequential) {
            // Outputs were inspected before any batch inputs were removed. Reusable containers
            // and byproducts must become available between operations, never between slots.
            for (long operation = 0; operation < times; operation++) {
                selectBatch(process, 1, state, path, simulate);
            }
        }
        if (state.extract(key, count) != count) {
            throw new BranchFailure();
        }
    }

    private void selectBatch(Process process, long times, CraftingSimulationState state,
        LinkedHashSet<ItemKey> path, boolean simulate) {
        try {
            select(process, 0, new ArrayList<>(), times, state, path);
        } catch (BranchFailure failure) {
            // Crafting an earlier input may introduce component-distinct candidates for a
            // later slot. Discover those against the evolving stock before giving up.
            var child = new CraftingSimulationState(state);
            selectInputs(process, 0, new ArrayList<>(), new TreeMap<>(), times, child, path, simulate);
            state.apply(child);
        }
    }

    private void select(Process process, int slot, List<ItemStack> selected, long times,
        CraftingSimulationState state, LinkedHashSet<ItemKey> path) {
        check(path.size());
        if (slot == process.inputs.size()) {
            execute(process, selected, times, state, path, false);
            return;
        }
        var input = process.inputs.get(slot);
        if (input.ingredient().isEmpty()) {
            selected.add(ItemStack.EMPTY);
            try {
                select(process, slot + 1, selected, times, state, path);
            } finally {
                selected.removeLast();
            }
            return;
        }
        var ordered = candidates(input, state);
        Map<ItemKey, Long> reserved = new TreeMap<>();
        for (ItemStack stack : selected) {
            if (!stack.isEmpty()) {
                reserved.merge(new ItemKey(stack), times, Long::sum);
            }
        }
        ordered.sort(Comparator.comparingInt((ItemKey candidate) -> stockPreference(candidate, state,
            reserved.getOrDefault(candidate, 0L))));
        int tested = 0;
        for (ItemKey candidate : ordered) {
            if (state.available(candidate) == 0 && !reachable.contains(candidate)) {
                continue;
            }
            if (++tested > limits.variants) {
                truncated = true;
                break;
            }
            selected.add(candidate.stack());
            try {
                // All later slots remain inside this choice's transaction. A restrictive slot
                // can reject an early variant without keeping its inventory, steps or fuel.
                select(process, slot + 1, selected, times, state, path);
                return;
            } catch (InputFailure failure) {
                if (failure.slot < slot) {
                    throw failure;
                }
            } catch (BranchFailure failure) {
                // Try the next variant.
            } finally {
                selected.removeLast();
            }
        }
        throw new InputFailure(slot);
    }

    // Retain slot-by-slot transactions for newly generated variants and the missing pass.
    // A missing early input must not prevent real alternatives in restrictive later slots.
    private void selectInputs(Process process, int slot, List<ItemStack> selected, Map<ItemKey, Long> inputs,
        long times, CraftingSimulationState state, LinkedHashSet<ItemKey> path, boolean simulate) {
        check(path.size());
        if (slot == process.inputs.size()) {
            var outputs = process.outputs(selected, level);
            if (outputs.getOrDefault(process.output, 0L) < process.outputCount) {
                throw new BranchFailure();
            }
            if (times > 1 && reusesOutput(process, outputs, state)) {
                throw new SequentialProcess();
            }
            outputs.replaceAll((key, amount) -> amount * times);
            finish(process, times, inputs, outputs, state, path, simulate);
            return;
        }
        var input = process.inputs.get(slot);
        if (input.ingredient().isEmpty()) {
            var next = new ArrayList<>(selected);
            next.add(ItemStack.EMPTY);
            selectInputs(process, slot + 1, next, inputs, times, state, path, simulate);
            return;
        }
        var candidates = candidates(input, state);
        int tested = 0;
        for (ItemKey candidate : candidates) {
            if (state.available(candidate) == 0 && !reachable.contains(candidate)) {
                continue;
            }
            if (++tested > limits.variants) {
                truncated = true;
                break;
            }
            var child = new CraftingSimulationState(state);
            try {
                request(candidate, times, child, path, false, input.label());
                var next = new ArrayList<>(selected);
                next.add(candidate.stack());
                var nextInputs = new TreeMap<>(inputs);
                nextInputs.merge(candidate, times, Long::sum);
                selectInputs(process, slot + 1, next, nextInputs, times, child, path, false);
                state.apply(child);
                return;
            } catch (BranchFailure failure) {
                // Discard every effect of this variant before trying the next.
            }
        }
        if (!simulate || candidates.isEmpty()) {
            throw new BranchFailure();
        }
        ItemKey candidate = candidates.getFirst();
        request(candidate, times, state, path, true, input.label());
        var next = new ArrayList<>(selected);
        next.add(candidate.stack());
        var nextInputs = new TreeMap<>(inputs);
        nextInputs.merge(candidate, times, Long::sum);
        selectInputs(process, slot + 1, next, nextInputs, times, state, path, true);
    }

    private List<ItemKey> candidates(Process.Input input, CraftingSimulationState state) {
        var candidates = new LinkedHashSet<ItemKey>();
        for (ItemKey key : state.keys()) {
            if (input.test(key)) {
                candidates.add(key);
            }
        }
        for (ItemStack stack : input.ingredient().getItems()) {
            if (!stack.isEmpty()) {
                candidates.add(new ItemKey(stack));
            }
        }
        var ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt((ItemKey key) -> stockPreference(key, state))
            .thenComparingInt(key -> state.available(key) > 0 ? 0 : cost(key, new HashSet<>(), 0)));
        return ordered;
    }

    private int stockPreference(ItemKey key, CraftingSimulationState state) {
        return stockPreference(key, state, 0);
    }

    private int stockPreference(ItemKey key, CraftingSimulationState state, long reserved) {
        long generated = state.generated.getOrDefault(key, 0L);
        long inventory = Math.max(0, carried.getOrDefault(key, 0L) - state.taken.getOrDefault(key, 0L));
        if (generated + inventory > reserved) {
            return 0;
        }
        if (state.available(key) > reserved) {
            long consumed = Math.max(0, state.taken.getOrDefault(key, 0L) - carried.getOrDefault(key, 0L))
                + Math.max(0, reserved - generated - inventory);
            for (int i = 0; i < sources.size(); i++) {
                long amount = sources.get(i).contents().getOrDefault(key, 0L);
                if (amount > consumed) {
                    return i + 1;
                }
                consumed -= amount;
            }
        }
        return sources.size() + 1;
    }

    private void execute(Process process, List<ItemStack> selected, long times, CraftingSimulationState state,
        LinkedHashSet<ItemKey> path, boolean simulate) {
        var outputs = process.outputs(selected, level);
        if (outputs.getOrDefault(process.output, 0L) < process.outputCount) {
            throw new BranchFailure();
        }
        if (times > 1 && reusesOutput(process, outputs, state)) {
            throw new SequentialProcess();
        }
        outputs.replaceAll((key, amount) -> amount * times);
        // One child per complete selection, rather than one full snapshot per input unit.
        var child = new CraftingSimulationState(state);
        Map<ItemKey, Long> inputs = new TreeMap<>();
        for (int slot = 0; slot < selected.size(); slot++) {
            if (selected.get(slot).isEmpty()) {
                continue;
            }
            ItemKey candidate = new ItemKey(selected.get(slot));
            try {
                request(candidate, times, child, path, simulate, process.inputs.get(slot).label());
            } catch (BranchFailure failure) {
                throw new InputFailure(slot);
            }
            inputs.merge(candidate, times, Long::sum);
        }
        finish(process, times, inputs, outputs, child, path, simulate);
        state.apply(child);
    }

    private void finish(Process process, long times, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs,
        CraftingSimulationState state, LinkedHashSet<ItemKey> path, boolean simulate) {
        ensureStation(process, state, path, simulate);
        fuel(process, times, state, inputs, outputs, simulate);
        outputs.forEach(state::insert);
        state.steps.add(new CraftPlan.Step(process.id(), process.station, times, inputs, outputs));
    }

    private boolean reusesOutput(Process process, Map<ItemKey, Long> outputs, CraftingSimulationState state) {
        for (ItemKey output : outputs.keySet()) {
            if (process.inputs.stream().anyMatch(input -> input.test(output))) {
                return true;
            }
            if (process.cookTicks > 0 && burnTicks(process, output) > 0) {
                return true;
            }
        }
        if (process.cookTicks > 0) {
            for (ItemKey candidate : state.keys()) {
                if (burnTicks(process, candidate) <= 0) {
                    continue;
                }
                var remainder = candidate.stack().getCraftingRemainingItem();
                if (remainder.isEmpty()) {
                    continue;
                }
                ItemKey returned = new ItemKey(remainder);
                if (process.inputs.stream().anyMatch(input -> input.test(returned))
                    || burnTicks(process, returned) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureStation(
        Process process, CraftingSimulationState state, LinkedHashSet<ItemKey> path, boolean simulate) {
        if (process.stationItem == null || availableStations.contains(process.station) || state.stations.contains(process.station)) {
            return;
        }

        var owned = state.keys()
            .stream()
            .filter(key -> key.stack().is(process.stationItem) && state.available(key) > 0)
            .findFirst();
        if (owned.isPresent()) {
            state.extract(owned.get(), 1);
        } else {
            request(new ItemKey(new ItemStack(process.stationItem)), 1, state, path, simulate, null);
        }
        state.stations.add(process.station); // Reserved for all subsequent operations, never consumed twice.
    }

    private void fuel(Process process, long times, CraftingSimulationState state, Map<ItemKey, Long> inputs,
        Map<ItemKey, Long> outputs, boolean simulate) {
        if (process.cookTicks == 0) {
            return;
        }
        long requiredHeat = (long) process.cookTicks * times;
        String station = process.station;
        long heat = state.heat.getOrDefault(station, 0L);
        while (heat < requiredHeat) {
            check(0);
            var fuels = state.keys()
                .stream()
                .filter(key -> state.available(key) > 0 && burnTicks(process, key) > 0 && fuelRank(key) < 3)
                .sorted(Comparator.comparingInt(CraftingCalculation::fuelRank)
                    .thenComparingInt((ItemKey key) -> -burnTicks(process, key))
                    .thenComparing(key -> key))
                .toList();
            if (fuels.isEmpty()) {
                if (!simulate) {
                    throw new BranchFailure();
                }
                state.missing.merge("fuel (" + station + ", burn ticks)", requiredHeat - heat, Long::sum);
                heat = requiredHeat;
                break;
            }
            ItemKey fuel = fuels.getFirst();
            int burn = burnTicks(process, fuel);
            long needed = (requiredHeat - heat + burn - 1) / burn;
            long used = state.extract(fuel, Math.min(needed, state.available(fuel)));
            inputs.merge(fuel, used, Long::sum);
            heat += used * burn;
            var remainder = fuel.stack().getCraftingRemainingItem();
            if (!remainder.isEmpty()) {
                outputs.merge(new ItemKey(remainder), (long) remainder.getCount() * used, Long::sum);
            }
        }
        state.heat.put(station, heat - requiredHeat);
    }

    /**
     * What a player throws in a furnace: coal first, then wood, then other burnable materials.
     * Never tools, weapons, armor (unstackable, nothing left after burning; a lava bucket leaves its
     * bucket) or crafted storage and workstations (3 = not fuel), though vanilla burns a bow or a
     * chest as well as a log.
     */
    public static int fuelRank(ItemKey fuel) {
        ItemStack stack = fuel.stack();
        if (stack.getMaxStackSize() == 1 && !stack.hasCraftingRemainingItem() || stack.is(net.neoforged.neoforge.common.Tags.Items.CHESTS)
            || stack.is(net.neoforged.neoforge.common.Tags.Items.BARRELS)
            || stack.is(net.neoforged.neoforge.common.Tags.Items.PLAYER_WORKSTATIONS_CRAFTING_TABLES)) {
            return 3;
        }
        if (stack.is(net.minecraft.tags.ItemTags.COALS)) return 0;
        if (stack.is(net.minecraft.tags.ItemTags.LOGS_THAT_BURN) || stack.is(net.minecraft.tags.ItemTags.PLANKS)) return 1;
        return 2;
    }

    private static int burnTicks(Process process, ItemKey fuel) {
        int burn = fuel.stack().getBurnTime(process.holder.value().getType());
        // Vanilla blast furnaces and smokers halve both cooking duration and fuel duration.
        return process.station.equals("blast_furnace") || process.station.equals("smoker") ? burn / 2 : burn;
    }

    // Inventory-aware ordering only: strict branch evaluation, not this heuristic, proves success.
    private int cost(ItemKey key, Set<ItemKey> path, int depth) {
        check(depth);
        if (supplied.contains(key)) {
            return 0;
        }
        if (depth > 8 || !path.add(key)) {
            return 1_000_000;
        }
        Integer cached = costs.get(key);
        if (cached != null) {
            path.remove(key);
            return cached;
        }
        int best = processes(key).isEmpty() ? 100 : 1_000_000;
        for (Process process : processes(key)) {
            best = Math.min(best, processCost(process, path));
        }
        path.remove(key);
        costs.put(key, best);
        return best;
    }

    private int processCost(Process process, Set<ItemKey> path) {
        int total = 1;
        for (var input : process.inputs) {
            if (input.ingredient().isEmpty()) {
                continue;
            }
            int best = 1_000_000;
            for (var stack : input.ingredient().getItems()) {
                if (!stack.isEmpty()) {
                    best = Math.min(best, cost(new ItemKey(stack), new HashSet<>(path), path.size()));
                }
            }
            total = Math.min(1_000_000, total + best);
        }
        if (process.stationItem != null && !availableStations.contains(process.station)) {
            int stationCost = cost(new ItemKey(new ItemStack(process.stationItem)),
                new HashSet<>(path), path.size());
            total = Math.min(1_000_000, total + stationCost);
        }
        return total;
    }

    /**
     * Aggregate identical operations only when a material/station replay proves the new order.
     * Otherwise preserve the original execution order (important for reusable remainders).
     */
    private List<CraftPlan.Step> compact(List<CraftPlan.Step> steps, CraftingSimulationState initial) {
        // Cooking order also carries implicit burn-time credit. Preserve that order exactly.
        if (steps.stream().anyMatch(
                step -> !step.station().equals("inventory") && !step.station().equals("crafting_table"))) {
            return List.copyOf(steps);
        }
        record Signature(
            ResourceLocation id, String station, Map<ItemKey, Long> inputs, Map<ItemKey, Long> outputs) {
        }
        Map<Signature, Long> grouped = new LinkedHashMap<>();
        for (var step : steps) {
            Map<ItemKey, Long> inputs = new TreeMap<>();
            Map<ItemKey, Long> outputs = new TreeMap<>();
            step.inputs().forEach((key, amount) -> inputs.put(key, amount / step.times()));
            step.outputs().forEach((key, amount) -> outputs.put(key, amount / step.times()));
            grouped.merge(
                new Signature(step.recipeId(), step.station(), inputs, outputs), step.times(), Long::sum);
        }
        var pending = new ArrayList<>(grouped.entrySet());
        var stock = new TreeMap<>(initial.original);
        List<CraftPlan.Step> result = new ArrayList<>();
        while (!pending.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return List.copyOf(steps);
            }
            boolean progress = false;
            for (var entry : pending) {
                Signature signature = entry.getKey();
                long times = entry.getValue();
                boolean station = signature.station.equals("inventory") || availableStations.contains(signature.station)
                    || stock.keySet().stream().anyMatch(
                        key -> key.id().equals("minecraft:" + signature.station) && stock.get(key) > 0);
                if (!station
                    || signature.inputs.entrySet().stream().anyMatch(
                        input -> stock.getOrDefault(input.getKey(), 0L) < input.getValue() * times)) {
                    continue;
                }
                Map<ItemKey, Long> inputs = new TreeMap<>();
                Map<ItemKey, Long> outputs = new TreeMap<>();
                signature.inputs.forEach((key, amount) -> {
                    inputs.put(key, amount * times);
                    stock.merge(key, -amount * times, Long::sum);
                });
                signature.outputs.forEach((key, amount) -> {
                    outputs.put(key, amount * times);
                    stock.merge(key, amount * times, Long::sum);
                });
                result.add(new CraftPlan.Step(signature.id, signature.station, times, inputs, outputs));
                pending.remove(entry);
                progress = true;
                break;
            }
            if (!progress) {
                return List.copyOf(steps);
            }
        }
        return result;
    }
}
