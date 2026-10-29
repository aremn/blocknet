package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.behavior.InventoryBehavior;
import dev.blocknet.state.ItemDescriber;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;

/** Shared command/memory filter and the single policy for items retained by deposit junk. */
public final class ContainerFilter {
    private ContainerFilter() {}

    public static boolean junk(BotPlayer bot, ItemStack stack) {
        var facts = ItemDescriber.describe(stack);
        return !stack.has(DataComponents.TOOL) && !stack.is(Tags.Items.TOOLS) && facts.kinds().isEmpty()
            && !stack.is(Tags.Items.ARMORS) && facts.armor() == 0 && facts.food() == null
            && !InventoryBehavior.isAcceptableThrowaway(stack, IBaritone.KEY.get(bot).settings());
    }

    /** Gear a player keeps on them: tools, weapons, armor and food. */
    public static boolean kit(ItemStack stack) {
        var facts = ItemDescriber.describe(stack);
        return stack.has(DataComponents.TOOL) || stack.is(Tags.Items.TOOLS) || !facts.kinds().isEmpty()
            || stack.is(Tags.Items.ARMORS) || facts.armor() > 0 || facts.food() != null;
    }

    public static Predicate<ItemStack> parse(BotPlayer bot, String text) {
        if (text.equals("all")) {
            return stack -> !stack.isEmpty();
        }
        if (text.equals("loot")) {
            // "Put everything away" as a player means it: keep the kit (tools, weapons, armor, food).
            return stack -> !stack.isEmpty() && !kit(stack);
        }
        if (text.equals("junk")) {
            return stack -> !stack.isEmpty() && junk(bot, stack);
        }
        boolean tag = text.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
        if (id == null || !tag && !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item or tag: " + text);
        }
        return tag ? stack -> stack.is(TagKey.create(Registries.ITEM, id))
            : stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
    }
}
