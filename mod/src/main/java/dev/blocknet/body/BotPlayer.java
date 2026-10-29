package dev.blocknet.body;

import com.mojang.authlib.GameProfile;
import dev.blocknet.control.BotController;
import dev.blocknet.bridge.BridgeServer;
import dev.blocknet.memory.ContainerMemory;
import dev.blocknet.memory.WorldMemory;
import dev.blocknet.vision.VisionSensor;
import dev.blocknet.state.BodyStateTracker;
import dev.blocknet.pathing.api.IBaritone;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;


/**
 * The bot's body: a real server-side player without a client.
 *
 * <p>A real player's physics tick ({@link #doTick()}) is driven by its client's movement packets. The
 * bot has no client, so it runs physics itself every tick, after the controller has set its inputs.
 */
public final class BotPlayer extends ServerPlayer {
    private final dev.blocknet.state.Moments moments = new dev.blocknet.state.Moments(this);
    public dev.blocknet.state.Moments moments() { return moments; }

    private final BotController controller = new BotController(this);
    private final VisionSensor vision = new VisionSensor(this);

    public VisionSensor vision() {
        return vision;
    }

    private final dev.blocknet.map.Radar radar = new dev.blocknet.map.Radar(this);
    private boolean fullPerception;

    /** ADR-0012: the bot may find blocks through walls (ores included); set from the config at spawn. */
    public boolean fullPerception() {
        return fullPerception;
    }

    public void fullPerception(boolean full) {
        fullPerception = full;
    }

    /** Live creatures around the bot, without line of sight (ADR-0011). */
    public dev.blocknet.map.Radar radar() {
        return radar;
    }

    private final BodyStateTracker bodyState = new BodyStateTracker(this);

    public BodyStateTracker bodyState() {
        return bodyState;
    }

    private dev.blocknet.debug.BotTrace trace;

    /** The reasoning trace: decisions and their reasons, for the overlay and logs/blocknet. */
    public dev.blocknet.debug.BotTrace trace() {
        if (trace == null) trace = dev.blocknet.debug.BotTrace.of(this);
        return trace;
    }

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    public ContainerMemory containerMemory() {
        return ContainerMemory.forBot(this);
    }

    public WorldMemory worldMemory() {
        return WorldMemory.forBot(this);
    }

    public BotController controller() {
        return controller;
    }

    private int respawnTicks;

    @Override
    public void tick() {
        if (isDeadOrDying()) {
            if (++respawnTicks == 20) BotManager.respawn(this);
            return;
        }
        if (getServer() != null && getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
        long started = System.nanoTime();
        controller.tick();
        long controlled = System.nanoTime();
        vision.tick();
        long seen = System.nanoTime();
        // After the vision timestamp: the radar is not part of the vision budget.
        radar.tick();
        // The pathfinder, when it has work, overrides this tick's inputs and rotation.
        IBaritone pathing = IBaritone.KEY.getNullable(this);
        // Even idle pathing rearranges hotbar tools; defense and meals own hands as well as feet.
        if (pathing != null && !controller.ownsHands()) {
            float forward = zza;
            float sideways = xxa;
            boolean jump = jumping;
            boolean sneak = isShiftKeyDown();
            pathing.serverTick();
            // Its idle input handler clears keys too. Preserve controller movement (e.g.
            // collecting a freshly mined drop, or crouching back at a player) when no
            // navigation leg owns the feet.
            if (!controller.isNavigating()) {
                zza = forward;
                xxa = sideways;
                setJumping(jump);
                setShiftKeyDown(sneak);
            }
        }
        long pathed = System.nanoTime();
        super.tick();
        doTick();
        long moved = System.nanoTime();
        if (isAlive()) moments.tick();
        trace().tickCost(controlled - started, seen - controlled, pathed - seen, moved - pathed);
        // The state line is throttled to one write per five seconds anyway; build it once a second.
        if (tickCount % 20 == 0) trace().state(controller.stateLine());
    }

    @Override
    protected void actuallyHurt(DamageSource source, float amount) {
        float before = getHealth();
        super.actuallyHurt(source, amount);
        moments.damage(source, before, getHealth());
        if (getHealth() < before) {
            Entity attacker = source.getEntity();
            trace().add("body", String.format(java.util.Locale.ROOT, "hurt by %s%s: health %.1f -> %.1f", source.getMsgId(),
                attacker == null ? "" : " (" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath() + ")",
                before, getHealth()));
        }
        if (getHealth() < before) controller.mobHurt(source);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        if (distance > 4) moments.emit("fall", "fell " + distance + " blocks");
        return super.causeFallDamage(distance, multiplier, source);
    }

    @Override
    public void onEquippedItemBroken(net.minecraft.world.item.Item item, net.minecraft.world.entity.EquipmentSlot slot) {
        super.onEquippedItemBroken(item, slot);
        moments.emit("item_broke", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
    }

    /** Vanilla death owns drops and keepInventory; respawning runs on a later server tick. */
    @Override
    public void die(DamageSource source) {
        if (isDeadOrDying() && respawnTicks > 0) return;
        moments.death(source);
        trace().add("body", "died (" + source.getMsgId() + ") at " + blockPosition().toShortString() + " while " + controller.describe());
        controller.stop();
        super.die(source);
        BridgeServer.botDied(this, source);
    }
}
