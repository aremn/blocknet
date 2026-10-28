// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.crafting.*;
import dev.blocknet.knowledge.Knowledge;
import dev.blocknet.state.ToolFacts;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Bounded goal stack: inventory, remembered menus, planner deficits, then survival sources. */
final class ObtainTask extends SkillTask {
    private record Goal(Item item, int count) {}
    private final Deque<Goal> goals = new ArrayDeque<>();
    private final List<String> actions = new ArrayList<>();
    private final Set<String> triedSources = new HashSet<>();
    private final Set<Item> explored = new HashSet<>();
    private final Goal target;
    private MineOreTask mining;
    private int operations;
    private boolean surveyed;
    @Override String depthContext() { return mining == null ? "" : mining.depthContext(); }

    ObtainTask(BotPlayer bot, Item item, int count, Consumer<String> report) {
        super(bot, report, 20*60*10);
        target = new Goal(item, count); goals.push(target);
    }
    private int held(Item item) { return bot.getInventory().countItem(item); }
    private String name(Item item) { return Knowledge.shortId(BuiltInRegistries.ITEM.getKey(item)); }
    @Override String describe() {
        return mining != null ? mining.describe() : "obtaining " + target.count + " " + name(target.item) + ": " + held(target.item) + "/" + target.count
            + (goals.isEmpty() ? "" : "; needs " + goals.peek().count + " " + name(goals.peek().item));
    }
    private boolean need(Item item, int count) {
        if (held(item) >= count) return false;
        if (goals.size() >= 16 || goals.stream().anyMatch(g -> g.item == item)) {
            complete("unsupported: acquisition cycle/depth for " + name(item)); return true;
        }
        controller.trace("obtain", "to get " + (goals.isEmpty() ? "" : goals.peek().count + " " + name(goals.peek().item))
            + " I first need " + count + " " + name(item) + " (have " + held(item) + ")");
        goals.push(new Goal(item, count)); return true;
    }
    @Override void work() {
        if (mining != null) { mining.tick(); return; }
        if (busy) return;
        if (++operations > 256) { complete("stopped: acquisition operation limit; " + describe()); return; }
        while (!goals.isEmpty() && held(goals.peek().item) >= goals.peek().count) goals.pop();
        if (goals.isEmpty()) {
            complete("obtained " + target.count + " " + name(target.item) + (actions.isEmpty() ? " (already in inventory)" : ": " + String.join(", ", actions))); return;
        }
        Goal goal = goals.peek();
        bot.vision().observe(net.minecraft.world.phys.Vec3.atCenterOf(bot.blockPosition().below()));
        for (var source : bot.containerMemory().sources(bot)) {
            String key = source.dimension() + ":" + source.position() + ":" + name(goal.item);
            long available = source.contents().entrySet().stream().filter(e -> e.getKey().stack().is(goal.item)).mapToLong(Map.Entry::getValue).sum();
            if (available == 0 || !triedSources.add(key)) continue;
            int before = held(goal.item);
            controller.trace("obtain", "a remembered container at " + BlockPos.of(source.position()).toShortString() + " holds " + available + " " + name(goal.item) + "; taking from it");
            action(cb -> controller.withdraw(BlockPos.of(source.position()), BuiltInRegistries.ITEM.getKey(goal.item).toString(), goal.count-before, cb), message -> {
                if (held(goal.item) > before) actions.add("withdrew " + (held(goal.item)-before) + " " + name(goal.item));
            }); return;
        }
        Knowledge knowledge = Knowledge.get(bot.serverLevel());
        var ore = knowledge.ore(goal.item);
        boolean logs = new ItemStack(goal.item).is(ItemTags.LOGS);
        boolean ground = goal.item == Items.COBBLESTONE || goal.item == Items.DIRT || goal.item == Items.SAND;
        if (ore != null || logs || ground) {
            var state = ore != null ? ore.blocks().iterator().next().defaultBlockState()
                : ground && goal.item == Items.COBBLESTONE ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            if (state.requiresCorrectToolForDrops() && bot.getInventory().items.stream().noneMatch(s -> s.isCorrectToolForDrops(state))) {
                Item tool = ToolFacts.minimumTool(state).orElse(null);
                if (tool == null) { complete("unsupported: no known harvest tool for " + name(goal.item)); return; }
                if (tool == Items.STONE_PICKAXE && held(Items.WOODEN_PICKAXE) == 0 && need(Items.WOODEN_PICKAXE, 1)) return;
                if (need(tool, 1)) return;
            }
            int before = held(goal.item);
            if (ore != null) {
                controller.trace("obtain", name(goal.item) + " comes from ore; mining it at its known depth");
                mining = new MineOreTask(bot, goal.item, goal.count-before, budget-ticks, message -> {
                    mining = null; actions.add(message);
                    if (failed(message)) complete(message);
                }); return;
            }
            if (logs && !explored.contains(goal.item)) {
                explored.add(goal.item);
                controller.trace("obtain", "need logs; looking around for trees first");
                action(cb -> controller.look("trees", true, cb), message -> {}); return;
            }
            controller.trace("obtain", "gathering " + (goal.count - before) + " " + name(goal.item) + " from what I can see");
            action(cb -> controller.gather(BuiltInRegistries.ITEM.getKey(goal.item).toString(), goal.count-before, cb), message -> {
                if (held(goal.item) > before) actions.add("gathered " + (held(goal.item)-before) + " " + name(goal.item));
                if (held(goal.item) >= goal.count) return;
                if (logs && explored.add(Items.AIR)) {
                    action(cb -> controller.explore("auto", 12, cb), ignored -> {});
                } else complete(message + "; " + describe());
            }); return;
        }
        var plan = new CraftingCalculation(bot.serverLevel(), PlannerCatalogs.get(bot.serverLevel()), CraftingCalculation.Limits.DEFAULT,
            p -> CraftStations.EXECUTABLE.contains(p.station())).plan(new ItemStack(goal.item), goal.count,
                new ArrayList<>(bot.getInventory().items), CraftStations.remembered(bot).keySet(), bot.containerMemory().sources(bot), CraftPlan.Mode.OBTAIN);
        if (plan.status() == CraftPlan.Status.OK) {
            int before = held(goal.item);
            controller.trace("obtain", "can craft " + goal.count + " " + name(goal.item) + " now: " + plan.text().replace('\n', ' '));
            action(cb -> controller.get(new ItemStack(goal.item), goal.count, cb), message -> {
                if (held(goal.item) > before) {
                    boolean cooked = plan.steps().stream().anyMatch(step -> bot.getServer().getRecipeManager().byKey(step.recipeId())
                        .map(holder -> holder.value() instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe).orElse(false));
                    String made = plan.steps().stream().flatMap(step -> step.outputs().keySet().stream())
                        .map(key -> name(key.stack().getItem())).filter(plan.neededStations()::contains).distinct()
                        .collect(java.util.stream.Collectors.joining(", "));
                    actions.add(cooked ? "smelted " + (held(goal.item)-before) + " " + name(goal.item) + (made.isEmpty() ? "" : " (made " + made + ")")
                        : message.replace("minecraft:", ""));
                }
                if (held(goal.item) < goal.count) complete(message + "; " + describe());
            }); return;
        }
        if (plan.status() == CraftPlan.Status.MISSING) {
            if (!surveyed && plan.missing().keySet().stream().anyMatch(k -> k.contains("logs") || k.contains("planks") || k.startsWith("fuel ("))) {
                surveyed = true;
                action(cb -> controller.look("trees", true, cb), ignored -> {}); return;
            }
            // Ask the SAME planner which observed raw materials it would use. These stacks exist
            // only in a forecast copy; all deficits still go through real obtain/player actions.
            Set<Item> availableSources = new LinkedHashSet<>();
            bot.worldMemory().nearby(bot, 64).forEach(entry -> {
                var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), entry.pos());
                if (cell == null || cell.stateId() < 0) return;
                var block = net.minecraft.world.level.block.Block.stateById(cell.stateId()).getBlock();
                Item item = block.asItem();
                if (new ItemStack(item).is(ItemTags.LOGS)) availableSources.add(item);
                for (Item drop : knowledge.drops(block)) if (drop == Items.COBBLESTONE || drop == Items.DIRT || drop == Items.SAND) availableSources.add(drop);
            });
            if (!availableSources.isEmpty()) {
                var forecastInventory = new ArrayList<>(bot.getInventory().items);
                availableSources.forEach(item -> forecastInventory.add(new ItemStack(item, Math.max(64, goal.count*16))));
                var forecast = new CraftingCalculation(bot.serverLevel(), PlannerCatalogs.get(bot.serverLevel()), CraftingCalculation.Limits.DEFAULT,
                    p -> CraftStations.EXECUTABLE.contains(p.station())).plan(new ItemStack(goal.item), goal.count, forecastInventory,
                        CraftStations.remembered(bot).keySet(), bot.containerMemory().sources(bot), CraftPlan.Mode.OBTAIN);
                if (forecast.status() == CraftPlan.Status.OK) for (var taken : forecast.taken().entrySet()) {
                    Item item = taken.getKey().stack().getItem();
                    if (availableSources.contains(item) && need(item, Math.toIntExact(taken.getValue()))) return;
                }
            }
            for (var missing : plan.missing().entrySet()) {
                if (missing.getKey().startsWith("fuel (")) {
                    if (need(Items.OAK_LOG, held(Items.OAK_LOG) + (int)Math.ceil(missing.getValue()/300.0))) return;
                }
                Item item = resolve(missing.getKey());
                if (item != null && need(item, held(item) + Math.toIntExact(missing.getValue()))) return;
            }
        }
        controller.trace("obtain", "no way I know to get " + name(goal.item) + "; plan was " + plan.status());
        complete(knowledge.unsupported(goal.item));
    }
    private Item resolve(String label) {
        if (!label.startsWith("#")) {
            var id = ResourceLocation.tryParse(label);
            return id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
        }
        var id = ResourceLocation.tryParse(label.substring(1));
        if (id == null) return null;
        var tag = TagKey.create(Registries.ITEM, id);
        // Prefer a species actually observed, then owned alternatives, then stable registry order.
        Set<Item> seen = new HashSet<>();
        bot.worldMemory().nearby(bot, 64).forEach(e -> {
            var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), e.pos());
            if (cell != null && cell.stateId() >= 0) {
                var block = net.minecraft.world.level.block.Block.stateById(cell.stateId()).getBlock();
                seen.add(block.asItem());
                seen.addAll(Knowledge.get(bot.serverLevel()).drops(block));
            }
        });
        return java.util.stream.StreamSupport.stream(BuiltInRegistries.ITEM.getTagOrEmpty(tag).spliterator(), false).map(h -> h.value())
                .sorted(Comparator.<Item>comparingInt(i -> seen.contains(i) ? 0 : held(i) > 0 ? 1
                    : i == Items.COBBLESTONE ? 2 : 3).thenComparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()))
                .findFirst().orElse(null);
    }
}
