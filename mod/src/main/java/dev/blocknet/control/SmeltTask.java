// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.knowledge.Knowledge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.common.Tags;

/**
 * "Переплавь руду": every raw ore or ore block in the bag (or one named item) goes through a
 * furnace. Each kind is one ordinary world-only obtain of its smelting result, so the bot places a
 * furnace and finds fuel like any other acquisition and never takes the result from a chest.
 */
final class SmeltTask extends SkillTask {
    private record Batch(Item input, Item result, int count) {}
    private final Deque<Batch> batches = new ArrayDeque<>();
    private final List<String> done = new ArrayList<>();
    @Nullable private Batch current;
    /** The running obtain; a skill ticks its child itself (controller.obtain would replace this skill). */
    @Nullable private ObtainTask obtaining;

    SmeltTask(BotPlayer bot, @Nullable Item only, Consumer<String> report) {
        super(bot, report, 20 * 60 * 15);
        Map<Item, Integer> inputs = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().items) {
            if (stack.isEmpty() || (only != null ? !stack.is(only) : !(stack.is(Tags.Items.RAW_MATERIALS) || stack.is(Tags.Items.ORES)))) continue;
            inputs.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        var level = bot.serverLevel();
        inputs.forEach((item, count) -> level.getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(item)), level)
            .map(recipe -> recipe.value().getResultItem(level.registryAccess()))
            .filter(result -> !result.isEmpty() && result.getItem() != item)
            .ifPresent(result -> batches.add(new Batch(item, result.getItem(), count * result.getCount()))));
    }

    private static String name(Item item) { return Knowledge.shortId(BuiltInRegistries.ITEM.getKey(item)); }

    @Override String describe() {
        return current == null ? "smelting: " + String.join(", ", done)
            : "smelting " + current.count() + " " + name(current.input()) + " into " + name(current.result());
    }

    /** A cancelled child never runs its own ending, which releases world-only mode. */
    @Override void ended(String message) { controller.worldOnly(false); }

    @Override void work() {
        if (obtaining != null) { obtaining.tick(); return; }
        current = batches.poll();
        if (current == null) {
            complete(done.isEmpty() ? "failed: nothing to smelt in the inventory" : "smelted " + String.join(", ", done));
            return;
        }
        Batch batch = current;
        int before = bot.getInventory().countItem(batch.result());
        controller.trace("smelt", batch.count() + " " + name(batch.input()) + " in the bag smelt into " + name(batch.result()));
        obtaining = new ObtainTask(bot, batch.result(), before + batch.count(), true, message -> {
            obtaining = null;
            int made = bot.getInventory().countItem(batch.result()) - before;
            if (made > 0) done.add(made + " " + name(batch.input()) + " into " + name(batch.result()));
            if (failed(message)) complete((done.isEmpty() ? "" : "smelted " + String.join(", ", done) + "; then ") + message);
        });
    }
}
