package dev.blocknet.body;

import com.mojang.authlib.GameProfile;
import dev.blocknet.control.BotController;
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
    private final BotController controller = new BotController(this);

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
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
        // The pathfinder, when it has work, overrides this tick's inputs and rotation.
        IBaritone pathing = IBaritone.KEY.getNullable(this);
        if (pathing != null) {
            pathing.serverTick();
        }
        super.tick();
        doTick();
    }

    /**
     * Until respawning is implemented, a dead bot leaves the game; its items drop as usual. Health
     * and hunger are reset before it leaves, because leaving saves player data and a bot spawned
     * again under the same name would otherwise load in dead.
     */
    @Override
    public void die(DamageSource source) {
        super.die(source);
        controller.stop();
        BotManager.forget(this);
        setHealth(getMaxHealth());
        foodData = new FoodData();
        if (connection instanceof BotPacketListener listener) {
            listener.disconnect(new DisconnectionDetails(Component.literal("Died")));
        }
    }
}
