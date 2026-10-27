package dev.blocknet.state;

import dev.blocknet.body.BotPlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** On-demand, server-thread state; no tick hook and no transport dependencies. */
public final class BodyStateTracker {
    private static final int HISTORY_LIMIT = 64;
    private final BotPlayer bot;
    private final Map<String, BodyState.Section> sections = new LinkedHashMap<>();
    private final Map<Long, Map<String, Integer>> inventoryHistory = new LinkedHashMap<>();
    private Map<String, Long> commandBaseline = Map.of();
    private long lastCaptureNanos;

    public BodyStateTracker(BotPlayer bot) { this.bot = bot; }

    public BodyState snapshot() {
        long started = System.nanoTime();
        update("body", BodyState.body(bot));
        var inventory = BodyState.inventory(bot);
        long rev = update("inventory", inventory.text());
        inventoryHistory.put(rev, inventory.items());
        while (inventoryHistory.size() > HISTORY_LIMIT) inventoryHistory.remove(inventoryHistory.keySet().iterator().next());
        update("task", bot.controller().describe());
        lastCaptureNanos = System.nanoTime() - started;
        return new BodyState(sections);
    }

    private long update(String name, String text) {
        var old = sections.get(name);
        if (old == null || !old.text().equals(text)) sections.put(name, new BodyState.Section(old == null ? 1 : old.revision() + 1, text));
        return sections.get(name).revision();
    }

    public record Delta(Map<String, BodyState.Section> sections, List<String> inventoryDiff, boolean inventoryReset) {
        public Delta {
            sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
            inventoryDiff = List.copyOf(inventoryDiff);
        }
    }

    /** Unknown/evicted inventory revisions return the full section and mark inventoryReset. */
    public Delta since(Map<String, Long> known) {
        BodyState current = snapshot();
        Map<String, BodyState.Section> changed = new LinkedHashMap<>();
        current.sections().forEach((name, section) -> {
            if (known.getOrDefault(name, 0L) != section.revision()) changed.put(name, section);
        });
        List<String> diff = new ArrayList<>();
        boolean reset = false;
        if (changed.containsKey("inventory")) {
            var previous = inventoryHistory.get(known.getOrDefault("inventory", 0L));
            reset = previous == null;
            if (previous == null) previous = Map.of();
            var now = inventoryHistory.get(current.sections().get("inventory").revision());
            var keys = new TreeSet<>(previous.keySet());
            keys.addAll(now.keySet());
            for (String line : keys) {
                int amount = now.getOrDefault(line, 0) - previous.getOrDefault(line, 0);
                if (amount != 0) diff.add((amount > 0 ? "+ " : "- ") + (Math.abs(amount) == 1 ? "" : Math.abs(amount) + " ") + line);
            }
        }
        return new Delta(changed, diff, reset);
    }

    /** One debug-command baseline per bot, independent of bridge callers. */
    public Delta changes() {
        Delta delta = since(commandBaseline);
        Map<String, Long> revisions = new TreeMap<>();
        sections.forEach((name, section) -> revisions.put(name, section.revision()));
        commandBaseline = Map.copyOf(revisions);
        return delta;
    }

    public long lastCaptureNanos() { return lastCaptureNanos; }
}
