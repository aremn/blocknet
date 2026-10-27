// SPDX-License-Identifier: MIT
package dev.blocknet.vision;

import dev.blocknet.body.BotPlayer;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

/** Bounded, egocentric summaries. Every object and entity comes from eye evidence. */
public final class Scene {
    private final BotPlayer bot;
    private final Map<String, String> looks = new HashMap<>();
    private Map<String, String> previous = Map.of();
    private String goal = "";
    public Scene(BotPlayer bot) { this.bot = bot; }
    public void goal(String goal) { this.goal = goal.toLowerCase(Locale.ROOT); }
    public String gist() {
        var level = bot.serverLevel();
        String biome = level.getBiome(bot.blockPosition()).unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
        // Contact with the support is body sense; do not turn it into visual map evidence.
        String under = BuiltInRegistries.BLOCK.getKey(level.getBlockState(bot.getOnPos()).getBlock())
            .getPath().replace("_block", "");
        return biome + ", " + (level.isDay() ? "day" : "night") + ", " + (level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear")
            + ". you: on " + under + ", facing " + Frontiers.direction(bot.getViewVector(1)) + ", holding "
            + (bot.getMainHandItem().isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).getPath());
    }
    private String group(ObjectGraph.ObjectView o) {
        return switch (o.category()) {
            case "tree" -> "trees";
            case "ore" -> "ores";
            case "cliff" -> "stone";
            case "station", "container", "machine" -> "stations";
            case "lava", "cave_mouth", "dark" -> "danger";
            default -> o.category();
        };
    }
    private double salience(ObjectGraph.ObjectView o) {
        String words = o.kind() + " " + o.category();
        boolean relevant = Arrays.stream(goal.split("[^a-z_]+" )).anyMatch(w -> w.length() > 2 && words.contains(w))
            || o.category().equals("tree") && goal.matches(".*(wood|logs|plank).* ".trim());
        return (relevant ? 80 : 0) + (group(o).equals("danger") ? 100 : 0)
            + (previous.containsKey(o.ref()) ? 0 : 20) - bot.position().distanceTo(o.center());
    }
    public String object(ObjectGraph.ObjectView o) {
        return o.ref() + " " + o.kind() + " x" + o.knownMembers() + " "
            + Frontiers.direction(o.center().subtract(bot.position())) + " "
            + Math.round(bot.position().distanceTo(Vec3.atCenterOf(o.nearest()))) + "m"
            + (o.provenance() == ObjectGraph.Provenance.REMEMBERED ? " (seen before)" : "")
            + bot.worldMemory().nearby(bot, 64).stream().filter(e -> e.pos().equals(o.nearest()) && e.container())
                .findFirst().map(e -> " (contents " + bot.worldMemory().contents(bot, e) + ")").orElse("");
    }
    public String unexplored() {
        String text = bot.vision().frontiers().stream().filter(f -> !f.blocked())
            .sorted(Comparator.comparingDouble(Frontiers.Frontier::knownDistance).reversed()).limit(2)
            .map(f -> f.direction() + " " + (f.caveMouth() ? "cave mouth " : "past ") + Math.round(f.knownDistance()) + "m")
            .collect(Collectors.joining("; "));
        return "unexplored: " + (text.isEmpty() ? "no open frontier; try look elsewhere" : text);
    }
    public String text(String focus) {
        Map<String, List<ObjectGraph.ObjectView>> groups = new LinkedHashMap<>();
        bot.vision().objects().stream().sorted(Comparator.comparingDouble(this::salience).reversed().thenComparing(ObjectGraph.ObjectView::ref))
            .filter(o -> focus.isEmpty() || (group(o).equals(focus) || focus.equals("food") && o.category().equals("crop"))).forEach(o -> groups.computeIfAbsent(group(o), k -> new ArrayList<>()).add(o));
        List<String> lines = new ArrayList<>();
        if (focus.isEmpty()) lines.add(gist());
        groups.forEach((category, objects) -> {
            if (category.equals("trees") && focus.isEmpty()) {
                Map<String, List<ObjectGraph.ObjectView>> species = new LinkedHashMap<>();
                objects.forEach(o -> species.computeIfAbsent(o.kind(), k -> new ArrayList<>()).add(o));
                List<String> descriptions = new ArrayList<>();
                species.values().stream().limit(2).forEach(trees -> {
                    var first = trees.getFirst();
                    long min = trees.stream().mapToLong(o -> Math.round(bot.position().distanceTo(Vec3.atCenterOf(o.nearest())))).min().orElse(0);
                    long max = trees.stream().mapToLong(o -> Math.round(bot.position().distanceTo(Vec3.atCenterOf(o.nearest())))).max().orElse(0);
                    descriptions.add(trees.stream().limit(1).map(ObjectGraph.ObjectView::ref).collect(Collectors.joining(",")) + " " + first.kind()
                        + " x" + trees.size() + " " + Frontiers.direction(first.center().subtract(bot.position())) + " " + min
                        + (max == min ? "" : "-" + max) + "m" + (trees.stream().anyMatch(o -> o.provenance() == ObjectGraph.Provenance.REMEMBERED) ? " (seen before)" : ""));
                });
                lines.add(category + ": " + String.join("; ", descriptions));
            } else lines.add(category + ": " + objects.stream().limit(2).map(this::object).collect(Collectors.joining("; ")));
        });
        if (focus.isEmpty() || focus.equals("danger") || focus.equals("food")) {
            Map<String, List<VisionSensor.EntitySeen>> entities = new LinkedHashMap<>();
            bot.vision().entities().stream().filter(e -> focus.isEmpty() || focus.equals("danger") && e.category().equals("hostile")
                || focus.equals("food") && food(e))
                .sorted(Comparator.comparingDouble(e -> bot.position().distanceTo(e.position())))
                .forEach(e -> entities.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e));
            entities.forEach((category, seen) -> lines.add((category.equals("passive") ? "animals" : category.equals("player") ? "players" : category)
                + ": " + seen.stream().limit(2).map(e -> (e.name().isEmpty() ? e.type() : e.name()) + " " + Frontiers.direction(e.position().subtract(bot.position()))
                    + " " + Math.round(bot.position().distanceTo(e.position())) + "m").collect(Collectors.joining("; "))));
        }
        if (focus.isEmpty()) lines.add(unexplored());
        return lines.isEmpty() ? "none seen; " + unexplored() + "; try explore" : lines.stream().limit(12).collect(Collectors.joining("\n"));
    }
    private boolean food(VisionSensor.EntitySeen seen) {
        net.minecraft.world.entity.Entity entity = bot.serverLevel().getEntity(seen.uuid());
        return entity instanceof net.minecraft.world.entity.animal.Animal
            || entity instanceof net.minecraft.world.entity.item.ItemEntity item
                && item.getItem().has(net.minecraft.core.component.DataComponents.FOOD);
    }

    /** Ignore pixel jitter (member counts, recency), retain actual changes of object identity/kind/location. */
    private Map<String, String> snapshot() {
        Map<String, String> result = new TreeMap<>();
        bot.vision().objects().forEach(o -> result.put(o.ref(), o.category() + " " + o.kind()));
        bot.vision().entities().forEach(e -> result.put(e.uuid().toString(), e.type() + " " + Frontiers.direction(e.position().subtract(bot.position())) + " " + Math.round(bot.position().distanceTo(e.position()))));
        return result;
    }
    public String looked(String focus) {
        String signature = gist() + snapshot();
        String old = looks.put(focus, signature);
        return signature.equals(old) ? "no change since last look; " + gist() : text(focus);
    }
    public Set<String> refs() { return bot.vision().objects().stream().map(ObjectGraph.ObjectView::ref).collect(Collectors.toSet()); }
    public String discoveries(Set<String> before) {
        Map<String, Integer> counts = new HashMap<>();
        String found = bot.vision().objects().stream().filter(o -> !before.contains(o.ref()))
            .filter(o -> counts.merge(group(o), 1, Integer::sum) <= 2).limit(12)
            .map(this::object).collect(Collectors.joining("; "));
        return found.isEmpty() ? "nothing new; " + unexplored() : "new: " + found;
    }
    public String delta() {
        Map<String, String> current = snapshot();
        String result = current.equals(previous) ? "scene unchanged; " + gist() : discoveries(previous.keySet());
        previous = current;
        return result.replace('\n', ' ');
    }
}
