// SPDX-License-Identifier: MIT
package dev.blocknet.map;

import dev.blocknet.body.BotPlayer;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Live creatures around one bot without line of sight (ADR-0011), each with a stable ref
 * ("e3") for goto/attack, and dropped items within 24 blocks ("i3") for pickup. Creatures that left
 * the range are remembered for a minute; items only while they lie there.
 */
public final class Radar {
    public static final double RANGE = 48;
    /** Dropped items are small: tracked closer than creatures. */
    public static final double ITEM_RANGE = 24;
    private static final long MEMORY_TICKS = 20 * 60;

    /** {@code count} is the stack size for dropped items ("item" category, refs "i#"), else 1. */
    public record Contact(String ref, UUID uuid, String type, String category, String name, Vec3 position,
            long lastSeen, boolean live, int count) {
        public String label() { return name.isEmpty() ? type : name; }
    }

    private final BotPlayer bot;
    private final Map<UUID, Contact> contacts = new LinkedHashMap<>();
    private final Map<UUID, String> refs = new HashMap<>();
    private int counter, itemCounter;

    public Radar(BotPlayer bot) { this.bot = bot; }

    public void tick() {
        if (bot.tickCount % 5 != 0) return;
        long now = bot.serverLevel().getGameTime();
        Set<UUID> present = new HashSet<>();
        for (Entity entity : bot.serverLevel().getEntities(bot, bot.getBoundingBox().inflate(RANGE),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            present.add(entity.getUUID());
            String ref = refs.computeIfAbsent(entity.getUUID(), ignored -> "e" + ++counter);
            contacts.put(entity.getUUID(), new Contact(ref, entity.getUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), category(entity),
                entity instanceof Player ? entity.getName().getString() : "", entity.position(), now, true, 1));
        }
        for (ItemEntity item : bot.serverLevel().getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(ITEM_RANGE),
                ItemEntity::isAlive)) {
            present.add(item.getUUID());
            String ref = refs.computeIfAbsent(item.getUUID(), ignored -> "i" + ++itemCounter);
            contacts.put(item.getUUID(), new Contact(ref, item.getUUID(), BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getPath(),
                "item", "", item.position(), now, true, item.getItem().getCount()));
        }
        contacts.replaceAll((uuid, c) -> present.contains(uuid) || !c.live() ? c
            : new Contact(c.ref(), uuid, c.type(), c.category(), c.name(), c.position(), c.lastSeen(), false, c.count()));
        contacts.values().removeIf(c -> {
            Entity entity = bot.serverLevel().getEntity(c.uuid());
            // A picked-up or despawned item is simply gone; there is nothing to remember.
            return now - c.lastSeen() > MEMORY_TICKS || entity instanceof LivingEntity living && !living.isAlive()
                || c.category().equals("item") && !c.live();
        });
        refs.keySet().retainAll(contacts.keySet());
    }

    private static String category(Entity entity) {
        if (entity instanceof Player) return "player";
        return entity instanceof Enemy || entity.getType().getCategory() == MobCategory.MONSTER ? "hostile" : "passive";
    }

    /** Nearest first; live contacts carry their current position. */
    public List<Contact> contacts() {
        return contacts.values().stream()
            .sorted(Comparator.comparing((Contact c) -> !c.live()).thenComparingDouble(c -> c.position().distanceToSqr(bot.position())))
            .toList();
    }

    /** A contact by ref, UUID, player name or entity type (nearest live one of that type). */
    @Nullable
    public Contact find(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        for (Contact c : contacts.values()) {
            if (c.ref().equals(q) || c.uuid().toString().equals(q)) return c;
        }
        return contacts().stream().filter(c -> c.live() && !c.category().equals("item") && (c.type().equals(q) || c.name().equalsIgnoreCase(q)))
            .findFirst().orElse(null);
    }

    /** The live creature behind a contact, or null when it is gone or out of range. */
    @Nullable
    public LivingEntity entity(Contact contact) {
        return contact != null && contact.live() && bot.serverLevel().getEntity(contact.uuid()) instanceof LivingEntity living
            && living.isAlive() ? living : null;
    }
}
