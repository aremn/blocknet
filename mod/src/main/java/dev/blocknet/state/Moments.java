package dev.blocknet.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.bridge.BridgeServer;
import dev.blocknet.memory.WorldMemory;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Bounded, transition-based diagnostics. Hidden world facts live ONLY in the unseen field. */
public final class Moments {
    private final BotPlayer bot;
    private final ArrayDeque<JsonObject> tools = new ArrayDeque<>();
    private final ArrayDeque<JsonObject> recent = new ArrayDeque<>();
    private final Set<String> hostiles = new HashSet<>();
    private JsonObject damage;
    private long lastDamage, lastReportTick = -1;
    private boolean lowHealth, lowFood, full, water, lava, fire, night;
    private String dimension = "", lastReport = "";

    public Moments(BotPlayer bot) { this.bot = bot; }
    public java.util.List<JsonObject> recent() { return java.util.List.copyOf(recent); }

    public void tool(String name, JsonObject args) {
        JsonObject call = new JsonObject();
        call.addProperty("tool", name);
        call.add("args", args.deepCopy());
        tools.addLast(call);
        while (tools.size() > 3) tools.removeFirst();
    }

    public void report(String text) {
        long tick = bot.level().getGameTime();
        if (text.equals(lastReport) && tick == lastReportTick) return;
        lastReportTick = tick;
        lastReport = text;
        if (text.matches("(?is).*(failed|blocked).*")) emit("task_failed", text);
    }

    public void damage(DamageSource source, float before, float after) {
        if (after >= before) return;
        long tick = bot.level().getGameTime();
        if (damage != null && tick - lastDamage > 40) flushDamage();
        if (damage == null) {
            damage = event("damage", source.getMsgId());
            damage.addProperty("source", source.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse(source.getMsgId()));
            damage.addProperty("health_before", before);
            damage.addProperty("amount", 0);
            Entity attacker = source.getEntity();
            if (attacker != null) {
                JsonObject identity = bot.hasLineOfSight(attacker) ? damage : new JsonObject();
                identity.addProperty("attacker", BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
                identity.addProperty("attacker_distance", Math.round(bot.distanceTo(attacker) * 10) / 10.0);
                if (identity != damage) damage.add("unseen", identity);
            }
        }
        damage.addProperty("amount", damage.get("amount").getAsFloat() + before - after);
        damage.addProperty("health_after", after);
        damage.getAsJsonObject("snapshot").addProperty("health", after);
        damage.getAsJsonObject("snapshot").addProperty("health_before", damage.get("health_before").getAsFloat());
        damage.getAsJsonObject("snapshot").addProperty("health_after", after);
        lastDamage = tick;
    }

    public void flushDamage() {
        if (damage == null) return;
        publish(damage);
        damage = null;
    }

    public void death(DamageSource source) { flushDamage(); emit("death", source.getMsgId()); }
    public void emit(String kind, String note) { publish(event(kind, note)); }

    private JsonObject event(String kind, String note) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "moment");
        event.addProperty("bot", bot.getGameProfile().getName());
        event.addProperty("kind", kind);
        event.addProperty("note", note);
        event.add("snapshot", snapshot());
        return event;
    }

    private void publish(JsonObject event) {
        recent.addLast(event);
        while (recent.size() > 16) recent.removeFirst();
        BridgeServer.moment(event);
    }

    public void tick() {
        long tick = bot.level().getGameTime();
        if (damage != null && tick - lastDamage >= 40) flushDamage();
        if (tick % 10 != 0) return;
        boolean healthNow = bot.getHealth() < bot.getMaxHealth() * 0.3;
        boolean foodNow = bot.getFoodData().getFoodLevel() <= 6;
        boolean fullNow = bot.getInventory().getFreeSlot() < 0;
        boolean nightNow = bot.level().getDayTime() % 24000 >= 13000 && bot.level().getDayTime() % 24000 < 23000;
        String dim = bot.level().dimension().location().toString();
        if (healthNow && !lowHealth) emit("low_health", "health below 30%");
        if (foodNow && !lowFood) emit("hunger_low", "food <= 6");
        if (fullNow && !full) emit("inventory_full", "no empty inventory slot");
        if (bot.isInWater() && !water) emit("entered_water", "water");
        if (bot.isInLava() && !lava) emit("entered_lava", "lava");
        if (bot.isOnFire() && !fire) emit("entered_fire", "on fire");
        if (nightNow && !night) emit("night_started", "night");
        if (!dimension.isEmpty() && !dimension.equals(dim)) emit("dimension_change", dimension + " -> " + dim);
        lowHealth = healthNow; lowFood = foodNow; full = fullNow; night = nightNow;
        water = bot.isInWater(); lava = bot.isInLava(); fire = bot.isOnFire(); dimension = dim;
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(16), e -> e instanceof Enemy)) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (bot.distanceToSqr(e) <= 256 && bot.hasLineOfSight(e) && hostiles.add(type)) emit("hostile_seen", type);
        }
    }

    private JsonObject snapshot() {
        var level = bot.serverLevel();
        BlockPos feet = bot.blockPosition();
        JsonObject s = new JsonObject();
        s.addProperty("tick", level.getGameTime());
        s.addProperty("time", level.getDayTime() % 24000);
        s.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
        s.addProperty("dimension", level.dimension().location().toString());
        s.addProperty("pos", feet.getX() + " " + feet.getY() + " " + feet.getZ());
        s.addProperty("biome", level.getBiome(feet).unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
        s.addProperty("light", level.getMaxLocalRawBrightness(feet));
        s.addProperty("health", bot.getHealth());
        s.addProperty("food", bot.getFoodData().getFoodLevel());
        s.addProperty("held", bot.getMainHandItem().isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).toString());
        s.addProperty("task", bot.controller().describe());
        s.addProperty("last_report", lastReport);
        JsonArray calls = new JsonArray(); tools.forEach(c -> calls.add(c.deepCopy())); s.add("tools", calls);
        JsonArray entities = new JsonArray(), unseen = new JsonArray(), hazards = new JsonArray();
        level.getEntities(bot, bot.getBoundingBox().inflate(16), e -> e.isAlive() && bot.distanceToSqr(e) <= 256).stream()
            .sorted(java.util.Comparator.comparingDouble(bot::distanceToSqr)).limit(8).forEach(e -> {
                JsonObject info = new JsonObject();
                info.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                info.addProperty("distance", Math.round(bot.distanceTo(e) * 10) / 10.0);
                info.addProperty("hostile", e instanceof Enemy);
                if (e instanceof Player) info.addProperty("name", e.getName().getString());
                if (bot.hasLineOfSight(e)) entities.add(info); else unseen.add(info);
            });
        s.add("entities", entities);
        block(s, unseen, "under", feet.below());
        block(s, unseen, "front", feet.relative(bot.getDirection()));
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-4, -4, -4), feet.offset(4, 4, 4))) {
            if (p.distSqr(feet) > 16) continue;
            var state = level.getBlockState(p);
            String hazard = state.is(BlockTags.FIRE) ? "fire" : state.getFluidState().is(FluidTags.LAVA) ? "lava"
                : state.getFluidState().is(FluidTags.WATER) ? "water" : null;
            if (hazard == null) continue;
            String info = hazard + " at " + p.toShortString();
            if (visible(p)) { if (hazards.size() < 8) hazards.add(info); }
            else if (unseen.size() < 16) unseen.add(info);
        }
        s.add("hazards", hazards); s.add("unseen", unseen);
        return s;
    }

    private boolean visible(BlockPos p) {
        var hit = WorldMemory.clip(bot, Vec3.atCenterOf(p), ClipContext.Block.VISUAL);
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(p);
    }

    private void block(JsonObject s, JsonArray unseen, String label, BlockPos p) {
        String id = BuiltInRegistries.BLOCK.getKey(bot.level().getBlockState(p).getBlock()).toString();
        if (visible(p)) s.addProperty(label, id); else unseen.add(label + ": " + id);
    }
}
