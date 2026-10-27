package dev.blocknet.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Immutable, transport-independent facts and their compact rendering. */
public final class ItemDescriber {
    private ItemDescriber() {}

    public record Food(int nutrition, float saturation) {}
    public record Contents(int count, List<String> topIds) {}
    public record Description(String id, int count, String customName, int remaining, int maxDurability,
                              String wear, Map<String, Integer> enchantments, Food food, List<String> kinds,
                              int miningLevel, float stoneSpeed, double attackDamage, double attackSpeed,
                              double armor, Contents container, Contents bundle, String line) {}

    public static String shortId(ResourceLocation id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    public static String number(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public static String wear(ItemStack stack) {
        if (!stack.isDamageableItem()) return "";
        int left = stack.getMaxDamage() - stack.getDamageValue();
        if (left < 16 || left < stack.getMaxDamage() * .1) return "almost broken";
        return left < stack.getMaxDamage() * .25 ? "worn" : "";
    }

    public static Description describe(ItemStack stack) {
        String id = shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        var name = stack.get(DataComponents.CUSTOM_NAME);
        String custom = name == null ? "" : name.getString();
        int max = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
        int left = max == 0 ? 0 : max - stack.getDamageValue();
        var enchants = new TreeMap<String, Integer>();
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null) enchantments.entrySet().forEach(e ->
            e.getKey().unwrapKey().ifPresent(key -> enchants.put(shortId(key.location()), e.getIntValue())));
        var foodComponent = stack.get(DataComponents.FOOD);
        Food food = foodComponent == null ? null : new Food(foodComponent.nutrition(), foodComponent.saturation());
        List<String> kinds = ToolFacts.kinds(stack);
        int level = ToolFacts.miningLevel(stack);
        float speed = stack.getDestroySpeed(Blocks.STONE.defaultBlockState());
        double damage = attribute(stack, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND, 1);
        double attackSpeed = attribute(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND, 4);
        double armor = 0;
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            // A modifier may allow several equipment slots; the item occupies only one.
            armor = Math.max(armor, attribute(stack, Attributes.ARMOR, slot, 0));
        }
        var containerComponent = stack.get(DataComponents.CONTAINER);
        var bundleComponent = stack.get(DataComponents.BUNDLE_CONTENTS);
        Contents container = containerComponent == null ? null : contents(containerComponent.nonEmptyItems());
        Contents bundle = bundleComponent == null ? null : contents(bundleComponent.items());
        List<String> facts = new ArrayList<>();
        if (max > 0) facts.add(left + "/" + max + (wear(stack).isEmpty() ? "" : " " + wear(stack)));
        enchants.forEach((key, value) -> facts.add(key + " " + value));
        if (food != null) facts.add("food " + food.nutrition() + ", saturation " + number(food.saturation()));
        for (String kind : kinds) facts.add(kind + (kind.equals("pickaxe") ? ": " + ToolFacts.miningText(level) : ""));
        if (speed != 1) facts.add("stone speed " + number(speed));
        if (damage != 1) facts.add("attack " + number(damage));
        if (attackSpeed != 4) facts.add("attack speed " + number(attackSpeed));
        if (armor != 0) facts.add("armor " + number(armor));
        addContents(facts, "container", container);
        addContents(facts, "bundle", bundle);
        String line = (stack.getCount() == 1 ? "" : stack.getCount() + " ") + id
            + (custom.isEmpty() ? "" : " \"" + custom.replaceAll("\\s+", " ").replace("\"", "\\\"") + "\"")
            + (facts.isEmpty() ? "" : " " + String.join(", ", facts));
        return new Description(id, stack.getCount(), custom, left, max, wear(stack),
            java.util.Collections.unmodifiableMap(enchants), food, kinds, level, speed, damage, attackSpeed, armor, container, bundle, line);
    }

    private static double attribute(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot, double base) {
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.forEachModifier(slot, (key, modifier) -> { if (key.equals(attribute)) modifiers.add(modifier); });
        double added = base;
        for (var m : modifiers) if (m.operation() == AttributeModifier.Operation.ADD_VALUE) added += m.amount();
        double result = added;
        for (var m : modifiers) if (m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) result += added * m.amount();
        for (var m : modifiers) if (m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) result *= 1 + m.amount();
        return result;
    }

    private static Contents contents(Iterable<ItemStack> stacks) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : stacks) if (!stack.isEmpty()) counts.merge(shortId(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount(), Integer::sum);
        var top = counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(3).map(e -> e.getValue() + " " + e.getKey()).toList();
        return new Contents(counts.values().stream().mapToInt(Integer::intValue).sum(), top);
    }

    private static void addContents(List<String> facts, String label, Contents contents) {
        if (contents != null && contents.count() > 0) facts.add(label + " " + contents.count() + " items [" + String.join(", ", contents.topIds()) + "]");
    }
}
