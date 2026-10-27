package dev.blocknet.body;

import com.mojang.authlib.GameProfile;
import dev.blocknet.control.BotController;
import dev.blocknet.bridge.BridgeServer;
import dev.blocknet.memory.ContainerMemory;
import dev.blocknet.memory.WorldMemory;
import dev.blocknet.vision.VisionSensor;
import dev.blocknet.state.BodyStateTracker;
import dev.blocknet.pathing.api.IBaritone;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.food.FoodData;

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

    private final BodyStateTracker bodyState = new BodyStateTracker(this);

    public BodyStateTracker bodyState() {
        return bodyState;
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

    @Override
    public void tick() {
        if (getServer() != null && getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
        controller.tick();
        vision.tick();
        // The pathfinder, when it has work, overrides this tick's inputs and rotation.
        IBaritone pathing = IBaritone.KEY.getNullable(this);
        if (pathing != null) {
            float forward = zza;
            float sideways = xxa;
            boolean jump = jumping;
            pathing.serverTick();
            // Its idle input handler clears keys too. Preserve controller movement (e.g.
            // collecting a freshly mined drop) when no navigation leg owns the feet.
            if (!controller.isNavigating()) {
                zza = forward;
                xxa = sideways;
                setJumping(jump);
            }
        }
        super.tick();
        doTick();
        if (isAlive()) moments.tick();
    }

    @Override
    protected void actuallyHurt(DamageSource source, float amount) {
        float before = getHealth();
        super.actuallyHurt(source, amount);
        moments.damage(source, before, getHealth());
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

    /**
     * Until respawning is implemented, a dead bot leaves the game; its items drop as usual. Health
     * and hunger are reset before it leaves, because leaving saves player data and a bot spawned
     * again under the same name would otherwise load in dead.
     */
    @Override
    public void die(DamageSource source) {
        moments.death(source);
        controller.stop();
        super.die(source);
        BridgeServer.botDied(this);
        BotManager.forget(this);
        setHealth(getMaxHealth());
        foodData = new FoodData();
        if (connection instanceof BotPacketListener listener) {
            listener.disconnect(new DisconnectionDetails(Component.literal("Died")));
        }
    }
}
