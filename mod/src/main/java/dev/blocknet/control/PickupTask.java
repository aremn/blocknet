// SPDX-License-Identifier: MIT
package dev.blocknet.control;

import dev.blocknet.body.BotPlayer;
import dev.blocknet.knowledge.Knowledge;
import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walks over dropped items like a player: nearest first, until none match or a minute passes.
 * Pickup itself is vanilla (touching the item); this only chooses where to step.
 */
final class PickupTask extends SkillTask {
    private final @Nullable UUID only;
    private final @Nullable Item item;
    private final Vec3 center;
    private final double radius;
    private final Map<Item, Integer> before = new LinkedHashMap<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private UUID current;
    private int waited;

    /** {@code only} picks one item entity; {@code item} filters by kind; both null take everything. */
    PickupTask(BotPlayer bot, @Nullable UUID only, @Nullable Item item, Vec3 center, double radius, Consumer<String> report) {
        super(bot, report, 20 * 60);
        this.only = only; this.item = item; this.center = center; this.radius = radius;
        for (ItemStack stack : bot.getInventory().items) if (!stack.isEmpty()) before.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    private List<ItemEntity> candidates() {
        return bot.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(BlockPos.containing(center)).inflate(radius),
                e -> e.isAlive() && (only == null || e.getUUID().equals(only)) && (item == null || e.getItem().is(item))
                    && attempts.getOrDefault(e.getUUID(), 0) < 2)
            .stream().sorted(Comparator.comparingDouble(bot::distanceToSqr)).toList();
    }

    @Override String describe() {
        Map<Item, Integer> now = new LinkedHashMap<>();
        for (ItemStack stack : bot.getInventory().items) if (!stack.isEmpty()) now.merge(stack.getItem(), stack.getCount(), Integer::sum);
        List<String> gains = new ArrayList<>();
        now.forEach((kind, n) -> { int gain = n - before.getOrDefault(kind, 0); if (gain > 0) gains.add(gain + " " + Knowledge.shortId(BuiltInRegistries.ITEM.getKey(kind))); });
        return gains.isEmpty() ? "picked up nothing" : "picked up " + String.join(", ", gains);
    }

    @Override void work() {
        if (busy) return;
        if (current != null) {
            // Vanilla pickup may lag a few ticks (pickup delay, the last step onto the item).
            var entity = bot.serverLevel().getEntity(current);
            if (entity != null && entity.isAlive() && ++waited < 20) return;
            current = null;
        }
        var next = candidates().stream().findFirst();
        if (next.isEmpty()) {
            String text = describe();
            complete(text.equals("picked up nothing") ? (bot.getInventory().getFreeSlot() < 0 ? "failed: inventory full; " + text : "no items to pick up") : text);
            return;
        }
        ItemEntity target = next.get();
        attempts.merge(target.getUUID(), 1, Integer::sum);
        current = target.getUUID();
        waited = 0;
        if (bot.distanceToSqr(target) <= 1.5) return;
        controller.trace("pickup", "walking to " + Knowledge.shortId(BuiltInRegistries.ITEM.getKey(target.getItem().getItem()))
            + " x" + target.getItem().getCount() + " at " + target.blockPosition().toShortString());
        action(cb -> controller.goTo(target.blockPosition(), cb), message -> {});
    }
}
