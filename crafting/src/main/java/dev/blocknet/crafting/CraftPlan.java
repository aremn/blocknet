/* SPDX-License-Identifier: LGPL-3.0-or-later */
package dev.blocknet.crafting;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

/** Value-only plan. Missing plans are hypothetical; limit plans must never be executed. */
public record CraftPlan(Status status, ItemKey target, long count, List<Step> steps, Map<ItemKey, Long> taken,
    Map<String, Long> missing, Map<ItemKey, Long> leftovers, Set<String> neededStations, List<String> hints) {
    public enum Status { OK, MISSING, LIMIT }

    public record Step(ResourceLocation recipeId, String station, long times, Map<ItemKey, Long> inputs,
        Map<ItemKey, Long> outputs) {
        public Step {
            inputs = immutable(inputs);
            outputs = immutable(outputs);
        }
    }

    public CraftPlan {
        steps = List.copyOf(steps);
        taken = immutable(taken);
        missing = immutable(missing);
        leftovers = immutable(leftovers);
        neededStations = Collections.unmodifiableSet(new TreeSet<>(neededStations));
        hints = List.copyOf(hints);
    }

    static <K extends Comparable<? super K>, V> Map<K, V> immutable(Map<K, V> map) {
        return Collections.unmodifiableMap(new TreeMap<>(map));
    }

    static String items(Map<?, Long> map) {
        return map.entrySet()
            .stream()
            .map(entry -> entry.getValue() + " " + entry.getKey())
            .collect(Collectors.joining(", "));
    }

    public String text() {
        StringBuilder text =
            new StringBuilder("plan " + count + " " + target + ": " + status.name().toLowerCase(Locale.ROOT));
        if (!taken.isEmpty()) {
            text.append("\nuse: ").append(items(taken));
        }
        int stepNumber = 0;
        for (Step step : steps) {
            text.append("\n").append(++stepNumber).append(". ").append(items(step.outputs))
                .append(" <- ").append(items(step.inputs)).append(" [").append(step.station).append("]")
                .append(step.times == 1 ? "" : " x" + step.times)
                .append(" (").append(step.recipeId).append(")");
        }
        if (!missing.isEmpty()) {
            text.append("\nmissing: ").append(items(missing));
        }
        if (!leftovers.isEmpty()) {
            text.append("\nleft: ").append(items(leftovers));
        }
        if (!neededStations.isEmpty()) {
            text.append("\nstations: ").append(String.join(", ", neededStations));
        }
        for (String hint : hints) {
            text.append("\n").append(hint);
        }
        if (status == Status.LIMIT) {
            text.append("\nsearch limit");
        }
        return text.toString();
    }
}
