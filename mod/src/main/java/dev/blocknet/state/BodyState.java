package dev.blocknet.state;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.behavior.InventoryBehavior;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Immutable sections. Capture on the server thread; consumers need no Minecraft objects. */
public record BodyState(Map<String, Section> sections) {
    public BodyState {
        sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
    }

    public record Section(long revision, String text) {}

    public Map<String, Long> revisions() {
        Map<String, Long> result = new LinkedHashMap<>();
        sections.forEach((name, section) -> result.put(name, section.revision()));
        return Collections.unmodifiableMap(result);
    }

    static String body(BotPlayer bot) {
        List<String> facts = new ArrayList<>();
        facts.add("health " + ItemDescriber.number(bot.getHealth()) + "/" + ItemDescriber.number(bot.getMaxHealth()));
        facts.add("food " + bot.getFoodData().getFoodLevel());
        facts.add("saturation " + ItemDescriber.number(bot.getFoodData().getSaturationLevel()));
        if (bot.getAirSupply() < bot.getMaxAirSupply()) facts.add("air " + bot.getAirSupply() + "/" + bot.getMaxAirSupply());
        bot.getActiveEffects().stream().map(effect -> ItemDescriber.shortId(effect.getEffect().unwrapKey().orElseThrow().location())
            + " " + (effect.getAmplifier() + 1) + " " + (effect.isInfiniteDuration() ? "infinite" : effect.getDuration() / 20 + "s"))
            .sorted().forEach(facts::add);
        if (bot.getArmorValue() != 0) facts.add("armor " + bot.getArmorValue());
        if (bot.experienceLevel != 0) facts.add("xp " + bot.experienceLevel);
        facts.add("dimension " + ItemDescriber.shortId(bot.level().dimension().location()));
        facts.add("pos " + bot.blockPosition().toShortString());
        facts.add(bot.controller().depthContext());
        if (!bot.onGround()) facts.add("airborne");
        if (bot.isInWater()) facts.add("in_water");
        if (bot.isInLava()) facts.add("in_lava");
        if (bot.isOnFire()) facts.add("on_fire");
        if (bot.isShiftKeyDown()) facts.add("sneaking");
        if (bot.isSprinting()) facts.add("sprinting");
        return String.join(", ", facts);
    }

    record InventoryView(String text, Map<String, Integer> items) {}

    private static final class Group {
        final ItemStack stack;
        final ItemDescriber.Description description;
        final List<Integer> slots = new ArrayList<>();
        int count;
        Group(ItemStack stack, ItemDescriber.Description description) { this.stack = stack; this.description = description; }
    }

    private record Tool(ItemStack stack, ItemDescriber.Description description) {}

    static InventoryView inventory(BotPlayer bot) {
        List<String> lines = new ArrayList<>();
        var inventory = bot.getInventory();
        lines.add("selected " + inventory.selected);
        if (!bot.getMainHandItem().isEmpty()) lines.add("main: " + ItemDescriber.describe(bot.getMainHandItem()).line());
        if (!bot.getOffhandItem().isEmpty()) lines.add("off: " + ItemDescriber.describe(bot.getOffhandItem()).line());
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            if (!bot.getItemBySlot(slot).isEmpty()) lines.add(slot.getName() + ": " + ItemDescriber.describe(bot.getItemBySlot(slot)).line());
        }
        List<Group> groups = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Tool> best = new TreeMap<>();
        int free = 0, food = 0, blocks = 0;
        var settings = IBaritone.KEY.get(bot).settings();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                if (slot < inventory.items.size()) free++;
                continue;
            }
            var description = ItemDescriber.describe(stack);
            // A count-free description is the semantic line used by the multiset diff.
            String singleLine = description.count() == 1 ? description.line() : description.line().substring((description.count() + " ").length());
            counts.merge(singleLine, stack.getCount(), Integer::sum);
            if (description.food() != null) food += description.food().nutrition() * stack.getCount();
            if (InventoryBehavior.isAcceptableThrowaway(stack, settings)) blocks += stack.getCount();
            for (String kind : description.kinds()) {
                var old = best.get(kind);
                var candidate = new Tool(stack, description);
                if (old == null || better(candidate, old, kind)) best.put(kind, candidate);
            }
            if (slot >= inventory.items.size()) continue; // Equipment was printed above; never double count it.
            Group group = null;
            if (!stack.isDamageableItem() && description.kinds().isEmpty()) {
                group = groups.stream().filter(g -> ItemStack.isSameItemSameComponents(g.stack, stack)).findFirst().orElse(null);
            }
            if (group == null) { group = new Group(stack.copy(), description); groups.add(group); }
            group.slots.add(slot);
            group.count += stack.getCount();
        }
        groups.stream().sorted(java.util.Comparator.comparing((Group g) -> g.description.id())
            .thenComparing(g -> g.description.line()).thenComparingInt(g -> g.slots.getFirst()))
            .forEach(g -> lines.add(ItemDescriber.describe(g.stack.copyWithCount(g.count)).line() + " slots " + g.slots));
        lines.add("free slots " + free);
        List<String> summary = new ArrayList<>();
        // One entry per tool, so an item that is best at several kinds is not listed twice.
        Map<Tool, List<String>> kindsByTool = new java.util.LinkedHashMap<>();
        best.forEach((kind, tool) -> kindsByTool.computeIfAbsent(tool, ignored -> new ArrayList<>()).add(kind));
        kindsByTool.forEach((tool, kinds) -> {
            var item = tool.description();
            summary.add("best " + String.join("/", kinds) + " " + item.id()
            + (item.maxDurability() == 0 ? "" : " " + item.remaining() + "/" + item.maxDurability())
            + (item.wear().isEmpty() ? "" : " " + item.wear()));
        });
        summary.add("food points " + food);
        summary.add("building blocks " + blocks);
        summary.add("free slots " + free);
        lines.add("summary: " + String.join(", ", summary));
        return new InventoryView(String.join("\n", lines), Collections.unmodifiableMap(counts));
    }

    private static boolean better(Tool candidate, Tool old, String kind) {
        if (kind.equals("pickaxe") && candidate.description().miningLevel() != old.description().miningLevel()) {
            return candidate.description().miningLevel() > old.description().miningLevel();
        }
        double speed = candidate.stack().getDestroySpeed(ToolFacts.probe(kind));
        double oldSpeed = old.stack().getDestroySpeed(ToolFacts.probe(kind));
        if (kind.equals("sword")) { speed = candidate.description().attackDamage(); oldSpeed = old.description().attackDamage(); }
        if (speed != oldSpeed) return speed > oldSpeed;
        return candidate.description().remaining() > old.description().remaining();
    }
}
