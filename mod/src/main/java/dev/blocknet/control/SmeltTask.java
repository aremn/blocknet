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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.common.Tags;

/**
 * "Переплавь руду": every raw ore or ore block in the bag (or one named item) goes through a
 * furnace. A small batch is one ordinary world-only obtain of its result (furnace and fuel come the
 * usual way). A big one is shared out like a player does it: a few furnaces side by side, the ore and
 * coal split between them, one wait, then the ingots and the furnaces are taken back. One furnace
 * took ten minutes for 64 iron and the order ran out of time.
 */
final class SmeltTask extends SkillTask {
    /** Items one furnace is given before another is worth placing, and the most furnaces set up. */
    private static final int PER_FURNACE = 12, MAX_FURNACES = 4, ITEMS_PER_COAL = 8;
    private record Batch(Item input, Item result, int count, int cookTicks) {}
    private enum Stage { START, FURNACES, FUEL, PLACE, LOAD, WAIT, COLLECT }
    private final Deque<Batch> batches = new ArrayDeque<>();
    private final List<String> done = new ArrayList<>();
    @Nullable private Batch current;
    /** The running obtain; a skill ticks its child itself (controller.obtain would replace this skill). */
    @Nullable private ObtainTask obtaining;
    private Stage stage = Stage.START;
    private int wanted, waitTicks, loadIndex, resultBefore;
    private boolean craftedFurnaces, fetchedCoal;
    private final List<BlockPos> furnaces = new ArrayList<>();
    private final List<int[]> loads = new ArrayList<>();
    private final Map<Integer, Boolean> fuelLoaded = new LinkedHashMap<>();

    SmeltTask(BotPlayer bot, @Nullable Item only, Consumer<String> report) {
        super(bot, report, 20 * 60 * 30);
        Map<Item, Integer> inputs = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().items) {
            if (stack.isEmpty() || (only != null ? !stack.is(only) : !(stack.is(Tags.Items.RAW_MATERIALS) || stack.is(Tags.Items.ORES)))) continue;
            inputs.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        var level = bot.serverLevel();
        inputs.forEach((item, count) -> level.getRecipeManager()
            .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(item)), level)
            .ifPresent(recipe -> {
                ItemStack result = recipe.value().getResultItem(level.registryAccess());
                if (!result.isEmpty() && result.getItem() != item)
                    batches.add(new Batch(item, result.getItem(), count, recipe.value().getCookingTime()));
            }));
    }

    private static String name(Item item) { return Knowledge.shortId(BuiltInRegistries.ITEM.getKey(item)); }
    private static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }
    private int held(Item item) { return bot.getInventory().countItem(item); }

    @Override String describe() {
        if (current == null) return "smelting: " + String.join(", ", done);
        return "smelting " + current.count() + " " + name(current.input()) + " into " + name(current.result())
            + (furnaces.size() > 1 ? " in " + furnaces.size() + " furnaces" : "") + (stage == Stage.WAIT ? " (" + waitTicks / 20 + " s left)" : "");
    }

    /** A cancelled child never runs its own ending, which releases world-only mode. */
    @Override void ended(String message) { controller.worldOnly(false); }

    @Override void work() {
        if (obtaining != null) { obtaining.tick(); return; }
        if (busy) return;
        if (current == null) {
            current = batches.poll();
            if (current == null) {
                complete(done.isEmpty() ? "failed: nothing to smelt in the inventory" : "smelted " + String.join(", ", done));
                return;
            }
            stage = Stage.START;
            furnaces.clear(); loads.clear(); fuelLoaded.clear();
            craftedFurnaces = false; fetchedCoal = false; loadIndex = 0;
            resultBefore = held(current.result());
        }
        Batch batch = current;
        switch (stage) {
            case START -> {
                wanted = Math.min(MAX_FURNACES, (batch.count() + PER_FURNACE - 1) / PER_FURNACE);
                if (wanted <= 1) { oneFurnace(batch); return; }
                stage = Stage.FURNACES;
            }
            case FURNACES -> {
                int have = held(Items.FURNACE);
                if (have < wanted && !craftedFurnaces) {
                    craftedFurnaces = true;
                    controller.trace("smelt", batch.count() + " " + name(batch.input()) + " is a lot for one furnace; making " + (wanted - have) + " more");
                    action(cb -> controller.craft(new ItemStack(Items.FURNACE), wanted - have, cb), message -> {}); return;
                }
                wanted = Math.min(wanted, have);
                if (wanted <= 1) { oneFurnace(batch); return; }
                stage = Stage.FUEL;
            }
            case FUEL -> {
                int needed = (Math.min(batch.count(), held(batch.input())) + ITEMS_PER_COAL - 1) / ITEMS_PER_COAL + wanted;
                int coal = held(Items.COAL) + held(Items.CHARCOAL);
                if (coal < needed && !fetchedCoal) {
                    fetchedCoal = true;
                    controller.trace("smelt", "need " + needed + " coal for " + wanted + " furnaces, have " + coal + "; getting more");
                    obtaining = new ObtainTask(bot, Items.COAL, held(Items.COAL) + needed - coal, true, message -> obtaining = null);
                    return;
                }
                if (coal < wanted) { oneFurnace(batch); return; }
                stage = Stage.PLACE;
            }
            case PLACE -> {
                if (furnaces.size() < wanted) {
                    BlockPos spot = furnaceSpot();
                    if (spot != null) {
                        action(cb -> controller.place(new ItemStack(Items.FURNACE), spot, Direction.UP, cb), message -> {
                            if (bot.level().getBlockState(spot).is(net.minecraft.world.level.block.Blocks.FURNACE)) furnaces.add(spot);
                            else wanted--;
                        }); return;
                    }
                    wanted = furnaces.size();
                }
                if (furnaces.isEmpty()) { oneFurnace(batch); return; }
                share(batch);
                stage = Stage.LOAD;
            }
            case LOAD -> {
                if (loadIndex < furnaces.size()) {
                    int i = loadIndex;
                    BlockPos pos = furnaces.get(i);
                    int[] load = loads.get(i);
                    if (!fuelLoaded.containsKey(i)) {
                        fuelLoaded.put(i, false);
                        action(cb -> controller.deposit(pos, id(batch.input()), load[0], cb), message -> {
                            if (SkillTask.failed(message)) controller.trace("smelt", "furnace " + (i + 1) + " took no ore: " + message);
                        }); return;
                    }
                    if (!fuelLoaded.get(i)) {
                        fuelLoaded.put(i, true);
                        Item fuel = held(Items.COAL) >= load[1] ? Items.COAL : Items.CHARCOAL;
                        action(cb -> controller.deposit(pos, id(fuel), load[1], cb), message -> loadIndex++); return;
                    }
                    loadIndex++;
                    return;
                }
                int most = loads.stream().mapToInt(load -> load[0]).max().orElse(0);
                waitTicks = most * batch.cookTicks() + 40;
                controller.trace("smelt", "loaded " + furnaces.size() + " furnaces; about " + waitTicks / 20 + " s");
                stage = Stage.WAIT;
            }
            case WAIT -> {
                if (--waitTicks > 0) return;
                loadIndex = 0;
                stage = Stage.COLLECT;
            }
            case COLLECT -> {
                if (loadIndex < furnaces.size() * 3) {
                    int i = loadIndex / 3, step = loadIndex % 3;
                    BlockPos pos = furnaces.get(i);
                    loadIndex++;
                    if (step == 0) { action(cb -> controller.withdraw(pos, id(batch.result()), 0, cb), message -> {}); return; }
                    // Ore left over (the fuel ran short) comes back too, then the furnace itself.
                    if (step == 1) { action(cb -> controller.withdraw(pos, id(batch.input()), 0, cb), message -> {}); return; }
                    if (bot.getInventory().items.stream().anyMatch(s -> s.isCorrectToolForDrops(bot.level().getBlockState(pos)))) {
                        action(cb -> controller.mine(pos, cb), message -> {}); return;
                    }
                    return;
                }
                int made = held(batch.result()) - resultBefore;
                if (made > 0) done.add(made + " " + name(batch.input()) + " into " + name(batch.result()) + " in " + furnaces.size() + " furnaces");
                current = null;
            }
        }
    }

    /** The small (or fallback) way: one world-only obtain of the result places one furnace itself. */
    private void oneFurnace(Batch batch) {
        int before = held(batch.result());
        controller.trace("smelt", batch.count() + " " + name(batch.input()) + " in the bag smelt into " + name(batch.result()));
        obtaining = new ObtainTask(bot, batch.result(), before + Math.min(batch.count(), held(batch.input())), true, message -> {
            obtaining = null;
            int made = held(batch.result()) - before;
            if (made > 0) done.add(made + " " + name(batch.input()) + " into " + name(batch.result()));
            current = null;
            if (failed(message)) complete((done.isEmpty() ? "" : "smelted " + String.join(", ", done) + "; then ") + message);
        });
    }

    /** Ore and coal per furnace: the batch split evenly, one coal per eight items rounded up. */
    private void share(Batch batch) {
        int total = Math.min(batch.count(), held(batch.input())), n = furnaces.size();
        for (int i = 0; i < n; i++) {
            int ore = total / n + (i < total % n ? 1 : 0);
            loads.add(new int[] {ore, (ore + ITEMS_PER_COAL - 1) / ITEMS_PER_COAL});
        }
    }

    /** A free floor cell beside the bot (not its own) for one more furnace in the row. */
    @Nullable
    private BlockPos furnaceSpot() {
        BlockPos feet = bot.blockPosition();
        for (int r = 1; r <= 2; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            BlockPos spot = feet.offset(dx, 0, dz);
            if (furnaces.contains(spot) || !bot.level().getBlockState(spot).getFluidState().isEmpty()) continue;
            if (BlockPlacement.face(bot, spot, Direction.UP) != null) return spot;
        }
        return null;
    }
}
