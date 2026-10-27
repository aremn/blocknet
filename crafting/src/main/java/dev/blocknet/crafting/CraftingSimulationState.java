/*
 * Derived from Applied Energistics 2 (https://github.com/AppliedEnergistics/Applied-Energistics-2)
 * Modified for Blocknet.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.world.item.ItemStack;

/**
 * AE2 child simulation/commit, adapted to small player snapshots.
 * Generated stock and original stock are separate so consumption reports stay honest.
 * Remainders are inserted only after all inputs of an operation have been removed.
 */
final class CraftingSimulationState {
    Map<ItemKey, Long> original = new TreeMap<>();
    Map<ItemKey, Long> generated = new TreeMap<>();
    Map<ItemKey, Long> taken = new TreeMap<>();
    Map<String, Long> missing = new TreeMap<>();
    List<CraftPlan.Step> steps = new ArrayList<>();
    Set<String> stations = new TreeSet<>();
    Set<String> hints = new TreeSet<>();
    Map<String, Long> heat = new HashMap<>();

    CraftingSimulationState(Iterable<ItemStack> inventory) {
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) {
                original.merge(new ItemKey(stack), (long) stack.getCount(), Long::sum);
            }
        }
    }

    CraftingSimulationState(CraftingSimulationState parent) {
        original.putAll(parent.original);
        generated.putAll(parent.generated);
        taken.putAll(parent.taken);
        missing.putAll(parent.missing);
        steps.addAll(parent.steps);
        stations.addAll(parent.stations);
        hints.addAll(parent.hints);
        heat.putAll(parent.heat);
    }

    /** Transfer ownership of a successful child. The caller must discard the child afterward. */
    void apply(CraftingSimulationState child) {
        original = child.original;
        generated = child.generated;
        taken = child.taken;
        missing = child.missing;
        steps = child.steps;
        stations = child.stations;
        hints = child.hints;
        heat = child.heat;
    }

    long available(ItemKey key) {
        return original.getOrDefault(key, 0L) + generated.getOrDefault(key, 0L);
    }

    long extract(ItemKey key, long count) {
        long made = Math.min(count, generated.getOrDefault(key, 0L));
        generated.computeIfPresent(key, (ignored, amount) -> amount == made ? null : amount - made);
        long raw = Math.min(count - made, original.getOrDefault(key, 0L));
        original.computeIfPresent(key, (ignored, amount) -> amount == raw ? null : amount - raw);
        if (raw > 0) {
            taken.merge(key, raw, Long::sum);
        }
        return made + raw;
    }

    void insert(ItemKey key, long count) {
        if (count > 0) {
            generated.merge(key, count, Long::sum);
        }
    }

    Set<ItemKey> keys() {
        Set<ItemKey> keys = new TreeSet<>(original.keySet());
        keys.addAll(generated.keySet());
        return keys;
    }
}
