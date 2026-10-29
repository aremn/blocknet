package dev.blocknet.control;

import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.map.Radar;
import dev.blocknet.pathing.api.pathing.goals.Goal;
import dev.blocknet.pathing.api.pathing.goals.GoalBlock;
import dev.blocknet.pathing.api.pathing.goals.GoalNear;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * What the bot does while nobody's order is running, so it behaves like a companion rather than a
 * statue: it turns to whoever speaks and nods, looks where its player looks, walks alongside when
 * they walk, and when they stay busy in one place it does small useful jobs nearby ({@link Chores}).
 * No model is involved; every move is a player input (an eased head turn no faster than a
 * player's, the sneak key, walking through the same pathfinder). Any order, reflex or open menu
 * takes over at once.
 */
public final class IdleLife {
    /** How long after a player spoke the bot treats itself as talked to (the brain is thinking). */
    static final int ATTENTION_TICKS = 20 * 25;
    static final int HEAR_RANGE = 24;
    /** Walking alongside: start when the player walks off past {@link #ALONGSIDE}, or is past {@link #KEEP_NEAR}. */
    static final double ALONGSIDE = 6, KEEP_NEAR = 10, COME_TO = 3;
    /** After an explicit stop the bot stays where it was told, unless given a new order. */
    static final int STAY_TICKS = 20 * 60 * 3;
    /** Chores wait until the bot has had nothing to do, and its player has stood still, this long. */
    static final int CHORES_AFTER = 20 * 12, PLAYER_SETTLED = 20 * 5;
    private static final double LOOK_RANGE = 16, MIRROR_RANGE = 8, COMPANION_RANGE = 48, PICKUP_RANGE = 8;
    /** A player looking at the same spot this long makes the bot look there too. */
    private static final int SHARED_LOOK_TICKS = 15, SHARED_LOOK_HOLD = 60;
    /** Looking for a chore is not free (it reads the observed objects): at most this often. */
    private static final int CHORE_SEARCH_EVERY = 40;
    /** An item the bot walked to but did not get (bag full, out of reach) is left alone this long. */
    private static final int PASS_OVER_ITEM = 20 * 30;
    /** A brain that said it is working and then went quiet (crashed, disconnected) is ignored after this. */
    private static final long BRAIN_WORKING_MS = 10 * 60 * 1000;

    private final BotPlayer bot;
    private final BotController controller;
    private final Chores chores;
    private ServerPlayer companion;
    private long attendedAt = Long.MIN_VALUE / 2, idleSince, stayUntil, nextWalk, nextGlance = 100, glanceUntil, nextChoreSearch;
    /** Set from the bridge thread while the brain plans or acts on an order (wall-clock, 0 = not working). */
    private volatile long brainWorkingSince;
    private Vec3 glance;
    /** Where the player was a moment ago, and since when they have stood (roughly) still. */
    private Vec3 lastPlayerPos;
    private ServerPlayer tracked;
    private long playerStillSince;
    private boolean playerMoving;
    /** What the player keeps looking at, and how long; the shared point while the bot looks too. */
    private Vec3 playerGaze, sharedLook;
    private int gazeTicks;
    private long sharedUntil;
    private int nodTicks;
    private boolean sneaking;
    /** The player's short crouches being counted, and the bot's own crouches still to make in reply. */
    private boolean playerCrouched;
    private long playerCrouchedAt, lastPlayerCrouch, nextCrouch;
    private int playerCrouches, crouchesLeft;
    private final Map<UUID, Long> passedOver = new HashMap<>();
    private Walk walk = Walk.NONE;
    private enum Walk { NONE, TO_PLAYER, TO_ITEM }
    /** The running idle walk's goal and the spot it was made for (kept until the spot moves). */
    private Goal walkGoal;
    private BlockPos walkSpot;
    private long walkAimedAt;

    IdleLife(BotPlayer bot, BotController controller) {
        this.bot = bot;
        this.controller = controller;
        this.chores = new Chores(bot, controller);
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
        // A nod when someone starts talking to it, not at every word.
        if (speaker != companion || now() - attendedAt > ATTENTION_TICKS) nodTicks = 8;
        companion = speaker;
        attendedAt = now();
    }

    void stay() {
        stayUntil = now() + STAY_TICKS;
    }

    boolean attending() {
        return now() - attendedAt < ATTENTION_TICKS;
    }

    boolean choring() {
        return chores.running();
    }

    /**
     * The brain says it is working on an order (planning, between steps, acting) or done. While it
     * works the bot keeps company but starts no chore and picks nothing up: the next step is coming.
     */
    public void brainWorking(boolean working) {
        brainWorkingSince = working ? System.currentTimeMillis() : 0;
    }

    private boolean brainWorking() {
        long since = brainWorkingSince;
        return since != 0 && System.currentTimeMillis() - since < BRAIN_WORKING_MS;
    }

    /**
     * Something else owns the body. Hands back the sneak key and stops an idle walk. A new order
     * (not a reflex) also ends a stop's "stay here".
     */
    void busy(boolean order) {
        if (order) stayUntil = 0;
        idleSince = now();
        crouchesLeft = 0;
        playerCrouches = 0;
        sneak(false);
        if (walk != Walk.NONE) stopWalking(false);
    }

    /** A chore is running: it ends when its player walks off, as a person would drop it and follow. */
    void superviseChore() {
        sneak(false);
        ServerPlayer focus = focus();
        track(focus);
        if (focus == null || bot.distanceTo(focus) > KEEP_NEAR + 4 || playerMoving && bot.distanceTo(focus) > KEEP_NEAR) {
            chores.abandon(focus == null ? "its player left" : focus.getGameProfile().getName() + " walked off");
            idleSince = now();
        }
    }

    void tick() {
        long now = now();
        // A chore can end without a word (a task replaced by the brain); idle again means it is over.
        if (chores.running()) chores.abandon("the body is idle again");
        ServerPlayer focus = focus();
        track(focus);
        if (focus == null) {
            sneak(false);
            if (walk == Walk.TO_PLAYER) stopWalking(false);
            return;
        }
        double distance = bot.distanceTo(focus);
        if (keepNear(focus, distance) || pickUp(now)) return;
        if (focus == companion && !attending() && !brainWorking() && now >= stayUntil && now - idleSince >= CHORES_AFTER
                && now - playerStillSince >= PLAYER_SETTLED && distance <= KEEP_NEAR && now >= nextChoreSearch) {
            nextChoreSearch = now + CHORE_SEARCH_EVERY;
            if (chores.start(focus)) {
                sneak(false);
                return;
            }
        }
        look(focus, distance, now);
        crouchBack(focus, distance, now);
    }

    /**
     * Crouching back at a crouching player is the oldest hello in the game, but a mirror looks like
     * a puppet. The bot counts the player's short crouches and, once they pause, answers after a
     * beat with a few of its own in its own rhythm. A long crouch (sneaking along a ledge) is not a hello.
     */
    private void crouchBack(ServerPlayer focus, double distance, long now) {
        boolean down = focus.isShiftKeyDown() && distance <= MIRROR_RANGE;
        if (down && !playerCrouched) playerCrouchedAt = now;
        if (!down && playerCrouched && now - playerCrouchedAt <= 15) {
            playerCrouches++;
            lastPlayerCrouch = now;
        }
        playerCrouched = down;
        var random = bot.getRandom();
        if (crouchesLeft == 0 && playerCrouches > 0 && !down && now - lastPlayerCrouch >= 5) {
            crouchesLeft = Mth.clamp(playerCrouches - 1 + random.nextInt(3), 1, 5);
            playerCrouches = 0;
            nextCrouch = now + 4 + random.nextInt(12);
        }
        if (crouchesLeft == 0 || now < nextCrouch) return;
        if (sneaking) {
            sneak(false);
            crouchesLeft--;
            nextCrouch = now + 2 + random.nextInt(8);
        } else {
            sneak(true);
            nextCrouch = now + 2 + random.nextInt(5);
        }
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

    /** Whether the player is walking (sampled every ten ticks) and what they keep looking at. */
    private void track(@Nullable ServerPlayer focus) {
        if (focus != tracked) {
            // Another player: their position says nothing about whether they walked.
            tracked = focus;
            lastPlayerPos = null;
            playerGaze = null;
            gazeTicks = 0;
        }
        if (focus == null) return;
        if (bot.tickCount % 10 == 0) {
            playerMoving = lastPlayerPos != null && focus.position().distanceTo(lastPlayerPos) > 0.8;
            if (playerMoving || lastPlayerPos == null) playerStillSince = now();
            lastPlayerPos = focus.position();
        }
        Vec3 gaze = gazePoint(focus);
        if (gaze != null && playerGaze != null && gaze.distanceTo(playerGaze) < 1.5) gazeTicks++;
        else gazeTicks = 0;
        playerGaze = gaze;
        if (gazeTicks == SHARED_LOOK_TICKS) {
            sharedLook = gaze;
            sharedUntil = now() + SHARED_LOOK_HOLD;
        }
    }

    /** The block (or creature) the player's crosshair rests on within 12 blocks; null for the sky or the bot. */
    @Nullable
    private Vec3 gazePoint(ServerPlayer focus) {
        Vec3 eye = focus.getEyePosition(), view = focus.getViewVector(1.0F);
        for (Radar.Contact contact : bot.radar().contacts()) {
            if (!contact.live() || contact.category().equals("player") || contact.category().equals("item")) continue;
            Vec3 to = contact.position().add(0, 0.6, 0).subtract(eye);
            if (to.length() <= 12 && to.normalize().dot(view) > Math.cos(Math.toRadians(6))) return contact.position().add(0, 0.6, 0);
        }
        var hit = focus.level().clip(new ClipContext(eye, eye.add(view.scale(12)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, focus));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        // Looking at the bot itself is eye contact, not something to look at together.
        return hit.getLocation().distanceTo(bot.getEyePosition()) < 1.5 ? null : hit.getLocation();
    }

    /** Eyes on the player (a nod when addressed); what the player looks at; now and then a glance around. */
    private void look(ServerPlayer focus, double distance, long now) {
        Vec3 target;
        if (sharedLook != null && now < sharedUntil) target = sharedLook;
        else {
            if (!attending() && now >= nextGlance) {
                glance = glanceTarget();
                glanceUntil = now + 20 + bot.getRandom().nextInt(25);
                nextGlance = now + 120 + bot.getRandom().nextInt(200);
            }
            target = glance != null && now < glanceUntil ? glance : distance <= LOOK_RANGE ? focus.getEyePosition() : null;
        }
        if (target == null) return;
        float pitchOffset = 0;
        if (nodTicks > 0) pitchOffset = nodTicks-- > 4 ? 14.0F : 0.0F;
        controller.idleLook(target, pitchOffset);
    }

    /**
     * Walking alongside: when the player walks off (or is far), go to a spot a few blocks to the
     * side the bot is already on, re-aimed as the player moves; stop once there and they stop.
     */
    private boolean keepNear(ServerPlayer focus, double distance) {
        long now = now();
        if (walk == Walk.TO_PLAYER) {
            if (distance <= COME_TO + 1.5 && !playerMoving) stopWalking(false);
            else step(ground(BlockPos.containing(beside(focus))), spot -> new GoalNear(spot, 1));
            return walk != Walk.NONE;
        }
        boolean walkedOff = playerMoving && distance > ALONGSIDE;
        if (focus != companion || !(walkedOff || distance > KEEP_NEAR) || now < stayUntil || now < nextWalk
                || !bot.onGround() && !bot.isInWater()) return false;
        controller.trace("idle", String.format(Locale.ROOT, "%s %s %.0f blocks away; going along",
            focus.getGameProfile().getName(), playerMoving ? "walks off," : "is", distance));
        walk = Walk.TO_PLAYER;
        sneak(false);
        crouchesLeft = 0;
        step(ground(BlockPos.containing(beside(focus))), spot -> new GoalNear(spot, 1));
        return true;
    }

    /**
     * One tick of an idle walk. A spot that moved more than 3 blocks gets a new leg, but not more
     * often than every half second: a running or flying player would otherwise cancel and replan
     * the route every tick.
     */
    private void step(BlockPos spot, java.util.function.Function<BlockPos, Goal> goal) {
        long now = now();
        boolean canAim = now - walkAimedAt >= 10;
        if (walkGoal == null || walkSpot.distSqr(spot) > 9 && canAim) {
            walkSpot = spot;
            walkGoal = goal.apply(spot);
            walkAimedAt = now;
        }
        var state = controller.idleWalk(walkGoal);
        // Beside a player who walked on, or who has just stopped: aim at where "beside" is now
        // instead of standing at the old spot.
        if (state == BotController.PathState.ARRIVED && walk == Walk.TO_PLAYER) {
            if (canAim && (walkSpot.distSqr(spot) > 2 || !playerMoving && !walkSpot.equals(spot))) walkGoal = null;
        }
        else if (state != BotController.PathState.MOVING) stopWalking(state == BotController.PathState.FAILED);
    }

    /** The standable spot at or below {@code spot}: beside a flying or jumping player is in the air. */
    private BlockPos ground(BlockPos spot) {
        var level = bot.level();
        for (BlockPos pos = spot.above(2); pos.getY() >= spot.getY() - 24 && pos.getY() > level.getMinBuildHeight(); pos = pos.below()) {
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                return pos;
            }
        }
        return spot;
    }

    /** Three blocks beside the player, on the bot's side of them, a step behind. */
    private Vec3 beside(ServerPlayer focus) {
        float yaw = focus.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), right = new Vec3(-forward.z, 0, forward.x);
        double side = Math.signum(bot.position().subtract(focus.position()).dot(right));
        return focus.position().add(right.scale((side == 0 ? 1 : side) * COME_TO)).subtract(forward);
    }

    /** With time on its hands and its player around, the bot picks up items lying close by. */
    private boolean pickUp(long now) {
        if (walk == Walk.TO_ITEM) {
            Radar.Contact item = nearestItem();
            if (item == null) stopWalking(false);
            else {
                step(BlockPos.containing(item.position()), GoalBlock::new);
                // Walked onto it (or could not) and it is still lying there: the bag did not take it.
                if (walk == Walk.NONE) passedOver.put(item.uuid(), now + PASS_OVER_ITEM);
            }
            return walk != Walk.NONE;
        }
        if (attending() || brainWorking() || now - idleSince < CHORES_AFTER || now < stayUntil || now < nextWalk) return false;
        Radar.Contact item = nearestItem();
        if (item == null) return false;
        controller.trace("idle", "nothing to do; picking up " + item.count() + " " + item.type() + " lying nearby");
        walk = Walk.TO_ITEM;
        step(BlockPos.containing(item.position()), GoalBlock::new);
        return true;
    }

    /** The nearest item lying close by that the bag has room for and that the bot has not given up on. */
    @Nullable
    private Radar.Contact nearestItem() {
        passedOver.values().removeIf(until -> until <= now());
        for (Radar.Contact contact : bot.radar().contacts()) {
            if (contact.live() && contact.category().equals("item") && contact.position().distanceTo(bot.position()) <= PICKUP_RANGE
                    && Math.abs(contact.position().y - bot.getY()) <= 2 && !passedOver.containsKey(contact.uuid())
                    && bot.serverLevel().getEntity(contact.uuid()) instanceof ItemEntity entity && fits(entity.getItem())) {
                return contact;
            }
        }
        return null;
    }

    private boolean fits(ItemStack stack) {
        var inventory = bot.getInventory();
        return inventory.getFreeSlot() >= 0 || inventory.getSlotWithRemainingSpace(stack) >= 0;
    }

    /** Ends an idle walk; after a failed route the next one waits a few seconds instead of every tick. */
    private void stopWalking(boolean failed) {
        walk = Walk.NONE;
        walkGoal = null;
        walkSpot = null;
        if (failed) nextWalk = now() + 60;
        controller.idleHalt();
    }

    private Vec3 glanceTarget() {
        List<Vec3> around = new ArrayList<>();
        for (Radar.Contact contact : bot.radar().contacts()) {
            if (contact.live() && !contact.category().equals("player") && contact.position().distanceTo(bot.position()) <= 12) {
                around.add(contact.position().add(0, 0.6, 0));
            }
        }
        if (!around.isEmpty() && bot.getRandom().nextBoolean()) return around.get(bot.getRandom().nextInt(around.size()));
        float yaw = (bot.getYRot() + (bot.getRandom().nextFloat() - 0.5F) * 120.0F) * Mth.DEG_TO_RAD;
        float pitch = (bot.getRandom().nextFloat() * 25.0F - 5.0F) * Mth.DEG_TO_RAD;
        return bot.getEyePosition().add(-Mth.sin(yaw) * Mth.cos(pitch) * 6, -Mth.sin(pitch) * 6, Mth.cos(yaw) * Mth.cos(pitch) * 6);
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
