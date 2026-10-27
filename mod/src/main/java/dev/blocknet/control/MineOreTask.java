// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.knowledge.Knowledge;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Direction schedule is geometry-independent; only observed ore can interrupt it. */
final class MineOreTask extends SkillTask {
    private final Knowledge.Ore ore;
    private final int count, initial;
    private final Direction forward;
    private final int y;
    private int mainSteps, phase, branch;
    private boolean descended;
    private final Set<Long> exhausted = new HashSet<>();

    MineOreTask(BotPlayer bot, Item item, int count, int budget, Consumer<String> report) {
        super(bot, report, budget);
        this.count = count; ore = Knowledge.get(bot.serverLevel()).ore(item); forward = bot.getDirection();
        initial = ore == null ? 0 : held();
        String dimension = Knowledge.shortId(bot.level().dimension().location());
        y = ore == null ? bot.getBlockY() : ore.ranges().stream()
            .filter(r -> r.dimension().equals(dimension) && r.min() != null && r.peak() <= bot.getBlockY()
                && r.peak() > bot.level().getMinBuildHeight())
            .sorted(Comparator.comparingDouble(Knowledge.Range::frequency).reversed()
                .thenComparing(Comparator.comparingInt(Knowledge.Range::peak).reversed()))
            .mapToInt(Knowledge.Range::peak).findFirst().orElse(Integer.MIN_VALUE);
    }
    private int held() { return bot.getInventory().countItem(ore.drop()); }
    private String name() { return ore == null ? "ore" : Knowledge.shortId(BuiltInRegistries.ITEM.getKey(ore.drop())); }
    @Override String describe() { return "mining " + name() + " depth y=" + bot.getBlockY() + ", found " + (ore == null ? 0 : held()-initial) + "/" + count; }
    @Override void work() {
        if (busy) return;
        if (ore == null) { complete("unsupported: no ore generation for this item"); return; }
        if (held()-initial >= count) { complete("mined " + (held()-initial) + " " + name() + " at y=" + y); return; }
        if (y == Integer.MIN_VALUE) { complete("unsupported: no known ore peak reachable below the bot (unknown range or another dimension)"); return; }
        if (!descended) {
            descended = true;
            if (bot.getBlockY() > y) {
                action(cb -> controller.descend(y, forward, cb), this::check); return;
            }
        }
        bot.worldMemory().glance(bot, 4);
        // This is an observed-map query, never a scan of live world states.
        var seen = bot.worldMemory().nearby(bot, 16).stream().filter(e -> !exhausted.contains(e.pos().asLong()))
            .filter(e -> {
                var cell = bot.worldMemory().observed().cell(bot.level().dimension().location().toString(), e.pos());
                return cell != null && cell.stateId() >= 0 && ore.blocks().contains(Block.stateById(cell.stateId()).getBlock());
            }).findFirst();
        if (seen.isPresent()) {
            // Gather follows newly exposed connected ore through the existing vision path.
            var pos = seen.get().pos();
            int before = held();
            action(cb -> controller.gather(BuiltInRegistries.ITEM.getKey(ore.drop()).toString(), count-(held()-initial), cb), message -> {
                if (held() == before) exhausted.add(pos.asLong());
                if (message.contains("blocked:") || message.contains("almost broken") || message.contains("failed:")) complete(message + "; " + describe());
            });
            return;
        }
        Direction direction = forward;
        int length = 1;
        if (phase != 0) {
            Direction side = branch == 0 ? forward.getClockWise() : forward.getCounterClockWise();
            direction = phase == 1 ? side : side.getOpposite();
            length = 12;
        }
        final Direction leg = direction;
        final int distance = length;
        action(cb -> controller.tunnel(leg, distance, cb), message -> {
            if (failed(message)) {
                if ((message.contains("lava") || message.contains("water")) && phase == 0) {
                    // A deterministic parallel lane around the observed fluid; never inspect hidden cells.
                    action(cb -> controller.tunnel(forward.getClockWise(), 3, cb), this::check);
                } else complete(message + "; " + describe());
                return;
            }
            if (phase == 0) { if (++mainSteps % 3 == 0) phase = 1; }
            else if (phase == 1) phase = 2;
            else { phase = branch == 0 ? 1 : 0; branch = 1-branch; }
        });
    }
    private void check(String message) { if (failed(message)) complete(message + "; " + describe()); }
}
