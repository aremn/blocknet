package dev.blocknet.control;

import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.map.Radar;
import dev.blocknet.pathing.api.pathing.goals.GoalBlock;
import dev.blocknet.pathing.api.pathing.goals.GoalNear;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * What the bot does while nobody's order is running, so it is not a statue while players talk to
 * it and the brain thinks: it turns to whoever speaks, fidgets while it is being talked to (swaps
 * what it holds, crouches, shifts its feet), keeps near its player, glances around and picks up
 * what lies close by. No model is involved; every move is a player input (head turned no faster
 * than a player's, sneak key, hotbar slot, walking through the same pathfinder). Any order,
 * reflex or open menu takes over at once.
 */
public final class IdleLife {
    /** How long after a player spoke the bot treats itself as talked to (the brain is thinking). */
    static final int ATTENTION_TICKS = 20 * 25;
    static final int HEAR_RANGE = 24;
    /** Farther than this from its player, the bot walks back to {@link #COME_TO} blocks. */
    static final double KEEP_NEAR = 10, COME_TO = 4;
    /** After an explicit stop the bot stays where it was told, unless given a new order. */
    static final int STAY_TICKS = 20 * 60 * 3;
    private static final double LOOK_RANGE = 16, MIRROR_RANGE = 8, COMPANION_RANGE = 48, CHORE_RANGE = 8;
    private static final int CHORES_AFTER = 20 * 15;

    private final BotPlayer bot;
    private final BotController controller;
    private ServerPlayer companion;
    /** The first glance waits a few seconds after spawn; the first look goes to the player. */
    private long attendedAt = Long.MIN_VALUE, idleSince, stayUntil, nextFidget, nextGlance = 100, glanceUntil, heldUntil, nextWalk;
    private Vec3 glance;
    /** The slot held before a fidget and the one the fidget chose; -1 when not fidgeting. */
    private int heldBefore = -1, heldNow = -1, crouchTicks, strafeTicks;
    private float strafe;
    /** Fidgets in the current talk: a crouch first, then something in the hand, then any. */
    private int fidgets;
    private boolean sneaking;
    private Walk walk = Walk.NONE;
    private enum Walk { NONE, TO_PLAYER, TO_ITEM }

    IdleLife(BotPlayer bot, BotController controller) {
        this.bot = bot;
        this.controller = controller;
    }

    /** Chat near bots: each of them turns to the speaker (a voice phrase does the same). */
    public static void onChat(ServerChatEvent event) {
        heard(event.getPlayer());
    }

    /** A human player spoke (chat line, or voice frames arriving): bots in earshot attend to them. */
    public static void heard(ServerPlayer speaker) {
        if (speaker instanceof BotPlayer) return;
        for (BotPlayer bot : BotManager.all()) {
            if (bot.level() == speaker.level() && bot.distanceTo(speaker) <= HEAR_RANGE) bot.controller().idleLife().attend(speaker);
        }
    }

    void attend(ServerPlayer speaker) {
        if (speaker != companion || now() - attendedAt > ATTENTION_TICKS) fidgets = 0;
        companion = speaker;
        attendedAt = now();
    }

    void stay() {
        stayUntil = now() + STAY_TICKS;
    }

    boolean attending() {
        return now() - attendedAt < ATTENTION_TICKS;
    }

    /**
     * Something else owns the body. Hands back what idling changed (the held slot, the sneak key)
     * and stops its walk. A new order (not a reflex) also ends a stop's "stay here".
     */
    void busy(boolean order) {
        if (order) stayUntil = 0;
        idleSince = now();
        restoreHeld();
        sneak(false);
        strafeTicks = 0;
        if (walk != Walk.NONE) {
            walk = Walk.NONE;
            controller.idleHalt();
        }
    }

    void tick() {
        long now = now();
        ServerPlayer focus = focus();
        if (heldBefore >= 0 && now >= heldUntil) restoreHeld();
        if (focus == null) {
            sneak(false);
            if (walk == Walk.TO_PLAYER) halt();
            return;
        }
        double distance = bot.distanceTo(focus);
        if (keepNear(focus, distance) || chore(now)) return;
        // Eyes on the player; now and then a glance at something else, as people do.
        if (!attending() && now >= nextGlance) {
            glance = glanceTarget();
            glanceUntil = now + 20 + bot.getRandom().nextInt(25);
            nextGlance = now + 80 + bot.getRandom().nextInt(140);
        }
        if (glance != null && now < glanceUntil) controller.idleLook(glance);
        else if (distance <= LOOK_RANGE) controller.idleLook(focus.getEyePosition());
        if (attending()) fidget(now);
        if (strafeTicks > 0) {
            strafeTicks--;
            bot.xxa = strafe;
        }
        // Crouching back at a crouching player is the oldest hello in the game.
        boolean crouch = crouchTicks > 0;
        if (crouch) crouchTicks--;
        sneak(crouch || focus.isShiftKeyDown() && distance <= MIRROR_RANGE);
    }

    /** The player the bot is with: the last one who spoke while near, else the nearest human. */
    @Nullable
    private ServerPlayer focus() {
        if (companion != null && companion.isAlive() && !companion.isRemoved() && companion.level() == bot.level()
                && bot.distanceTo(companion) <= COMPANION_RANGE && !companion.isSpectator()) {
            return companion;
        }
        ServerPlayer nearest = null;
        for (ServerPlayer player : bot.serverLevel().players()) {
            if (player instanceof BotPlayer || player.isSpectator() || !player.isAlive() || bot.distanceTo(player) > LOOK_RANGE) continue;
            if (nearest == null || bot.distanceTo(player) < bot.distanceTo(nearest)) nearest = player;
        }
        return nearest;
    }

    private boolean keepNear(ServerPlayer focus, double distance) {
        if (walk == Walk.TO_PLAYER) {
            if (distance <= COME_TO + 1 || !controller.idleWalk(new GoalNear(focus.blockPosition(), (int) COME_TO))) halt();
            return walk != Walk.NONE;
        }
        if (focus != companion || distance <= KEEP_NEAR || now() < stayUntil || now() < nextWalk || !bot.onGround() && !bot.isInWater()) return false;
        controller.trace("idle", String.format(java.util.Locale.ROOT, "%s is %.0f blocks away; catching up", focus.getGameProfile().getName(), distance));
        restoreHeld();
        sneak(false);
        walk = Walk.TO_PLAYER;
        controller.idleWalk(new GoalNear(focus.blockPosition(), (int) COME_TO));
        return true;
    }

    /** With time on its hands and its player around, the bot picks up items lying close by. */
    private boolean chore(long now) {
        if (walk == Walk.TO_ITEM) {
            Radar.Contact item = nearestItem();
            if (item == null || !controller.idleWalk(new GoalBlock(BlockPos.containing(item.position())))) halt();
            return walk != Walk.NONE;
        }
        if (attending() || now - idleSince < CHORES_AFTER || now < stayUntil || now < nextWalk) return false;
        Radar.Contact item = nearestItem();
        if (item == null) return false;
        controller.trace("idle", "nothing to do; picking up " + item.count() + " " + item.type() + " lying nearby");
        restoreHeld();
        walk = Walk.TO_ITEM;
        controller.idleWalk(new GoalBlock(BlockPos.containing(item.position())));
        return true;
    }

    @Nullable
    private Radar.Contact nearestItem() {
        for (Radar.Contact contact : bot.radar().contacts()) {
            if (contact.live() && contact.category().equals("item") && contact.position().distanceTo(bot.position()) <= CHORE_RANGE
                    && Math.abs(contact.position().y - bot.getY()) <= 2) {
                return contact;
            }
        }
        return null;
    }

    /** Ends an idle walk; the next one waits a few seconds (a failed route is not retried every tick). */
    private void halt() {
        walk = Walk.NONE;
        nextWalk = now() + 60;
        controller.idleHalt();
    }

    /** Small things a person does while listening: shift weight, turn something over in the hand, crouch. */
    private void fidget(long now) {
        if (now < nextFidget) return;
        nextFidget = now + 40 + bot.getRandom().nextInt(70);
        int kind = switch (fidgets++) { case 0 -> 2; case 1 -> 0; default -> bot.getRandom().nextInt(3); };
        switch (kind) {
            case 0 -> {
                List<Integer> filled = new ArrayList<>();
                for (int slot = 0; slot < 9; slot++) if (slot != bot.getInventory().selected && !bot.getInventory().getItem(slot).isEmpty()) filled.add(slot);
                if (filled.isEmpty()) return;
                if (heldBefore < 0) heldBefore = bot.getInventory().selected;
                heldNow = filled.get(bot.getRandom().nextInt(filled.size()));
                bot.getInventory().selected = heldNow;
                heldUntil = now + 20 + bot.getRandom().nextInt(30);
            }
            case 1 -> {
                strafe = bot.getRandom().nextBoolean() ? 1.0F : -1.0F;
                strafeTicks = 3;
            }
            default -> crouchTicks = fidgets == 1 ? 6 : 4;
        }
    }

    private Vec3 glanceTarget() {
        List<Vec3> around = new ArrayList<>();
        for (Radar.Contact contact : bot.radar().contacts()) {
            if (contact.live() && !contact.category().equals("player") && contact.position().distanceTo(bot.position()) <= 12) {
                around.add(contact.position().add(0, 0.6, 0));
            }
        }
        if (!around.isEmpty() && bot.getRandom().nextBoolean()) return around.get(bot.getRandom().nextInt(around.size()));
        float yaw = (bot.getYRot() + (bot.getRandom().nextFloat() - 0.5F) * 140.0F) * Mth.DEG_TO_RAD;
        float pitch = (bot.getRandom().nextFloat() * 30.0F - 10.0F) * Mth.DEG_TO_RAD;
        return bot.getEyePosition().add(-Mth.sin(yaw) * Mth.cos(pitch) * 6, -Mth.sin(pitch) * 6, Mth.cos(yaw) * Mth.cos(pitch) * 6);
    }

    /** Back to what was held, unless something else (a meal, a fight) has chosen a slot since. */
    private void restoreHeld() {
        if (heldBefore >= 0 && bot.getInventory().selected == heldNow) bot.getInventory().selected = heldBefore;
        heldBefore = -1;
        heldNow = -1;
    }

    private void sneak(boolean on) {
        if (on == sneaking) return;
        sneaking = on;
        bot.setShiftKeyDown(on);
    }

    private long now() {
        return bot.tickCount;
    }
}
