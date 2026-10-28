// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.knowledge.Knowledge;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
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
    private int branchSteps, dugBranchSteps;
    private int reroutes, sealed;
    private final int surfaceY;
    private BlockPos junction;
    private boolean descended;
    private final Set<Long> exhausted = new HashSet<>();

    MineOreTask(BotPlayer bot, Item item, int count, int budget, Consumer<String> report) {
        this(bot, item, count, budget, null, report);
    }
    MineOreTask(BotPlayer bot, Item item, int count, int budget, Integer targetY, Consumer<String> report) {
        super(bot, report, budget);
        this.count = count; ore = Knowledge.get(bot.serverLevel()).ore(item); forward = bot.getDirection();
        surfaceY = controller.miningSurfaceY();
        initial = ore == null ? 0 : held();
        String dimension = Knowledge.shortId(bot.level().dimension().location());
        y = targetY != null ? targetY : ore == null ? bot.getBlockY() : ore.ranges().stream()
            .filter(r -> r.dimension().equals(dimension) && r.min() != null && r.peak() <= bot.getBlockY()
                && r.peak() > bot.level().getMinBuildHeight())
            .sorted(Comparator.comparingDouble(Knowledge.Range::frequency).reversed()
                .thenComparing(Comparator.comparingInt(Knowledge.Range::peak).reversed()))
            .mapToInt(Knowledge.Range::peak).findFirst().orElse(Integer.MIN_VALUE);
        controller.trace("mine_ore", ore == null ? "no ore generation known for this item"
            : y == Integer.MIN_VALUE ? "no known " + name() + " peak below y=" + bot.getBlockY() + " in this dimension"
            : "best depth for " + name() + " is y=" + y + (targetY != null ? " (given)" : " (most frequent ore peak at or below me)")
                + "; I am at y=" + bot.getBlockY() + ", digging " + forward.getName());
    }
    private int held() { return bot.getInventory().countItem(ore.drop()); }
    private String name() { return ore == null ? "ore" : Knowledge.shortId(BuiltInRegistries.ITEM.getKey(ore.drop())); }
    @Override String depthContext() {
        return y == Integer.MIN_VALUE ? "" : ", " + name().replace("raw_", "") + " best ~" + y + ", "
            + Math.max(0, bot.getBlockY()-y) + " below";
    }
    @Override String describe() { return "mining " + name() + " depth y=" + bot.getBlockY() + ", surface y=" + surfaceY
        + depthContext() + ", found " + (ore == null ? 0 : held()-initial) + "/" + count
        + ", main " + mainSteps + ", branches " + dugBranchSteps + ", reroutes " + reroutes + ", sealed " + sealed; }
    private void recordLeg(String message) {
        var match = java.util.regex.Pattern.compile("reroutes (\\d+), sealed (\\d+)").matcher(message);
        if (match.find()) { reroutes += Integer.parseInt(match.group(1)); sealed += Integer.parseInt(match.group(2)); }
    }
    @Override void work() {
        if (busy) return;
        if (ore == null) { complete("unsupported: no ore generation for this item"); return; }
        if (held()-initial >= count) { complete("mined " + (held()-initial) + " " + name() + " at y=" + y + "; " + describe()); return; }
        if (y == Integer.MIN_VALUE) { complete("unsupported: no known ore peak reachable below the bot (unknown range or another dimension)"); return; }
        if (!descended) {
            if (bot.getBlockY() > y) {
                action(cb -> controller.miningLeg(forward, y, true, cb), message -> {
                    recordLeg(message);
                    if (failed(message)) check(message);
                    else if (bot.getBlockY() <= y) descended = true;
                    else complete("stopped: descent ended above target y=" + y + "; " + describe());
                }); return;
            }
            descended = true;
        }
        if (phase == 2) {
            action(cb -> controller.goTo(junction, cb), message -> {
                if (failed(message)) { check(message); return; }
                branchSteps = 0;
                phase = branch == 0 ? 1 : 0;
                branch = 1-branch;
            });
            return;
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
            controller.trace("mine_ore", "saw " + name() + " ore at " + pos.toShortString() + "; collecting the vein before tunneling on");
            int before = held();
            action(cb -> controller.gather(BuiltInRegistries.ITEM.getKey(ore.drop()).toString(), count-(held()-initial), cb), message -> {
                if (held() == before) exhausted.add(pos.asLong());
                if (message.contains("blocked:") || message.contains("almost broken") || message.contains("failed:")) complete(message + "; " + describe());
            });
            return;
        }
        Direction direction = forward;
        if (phase != 0) {
            Direction side = branch == 0 ? forward.getClockWise() : forward.getCounterClockWise();
            direction = side;
        }
        final Direction leg = direction;
        action(cb -> controller.miningLeg(leg, 1, false, cb), message -> {
            recordLeg(message);
            if (failed(message)) {
                complete(message + "; " + describe());
                return;
            }
            if (phase == 0) {
                if (++mainSteps % 3 == 0) { junction = bot.blockPosition(); phase = 1; }
            } else { dugBranchSteps++; if (++branchSteps >= 12) phase = 2; }
        });
    }
    private void check(String message) { if (failed(message)) complete(message + "; " + describe()); }
}
