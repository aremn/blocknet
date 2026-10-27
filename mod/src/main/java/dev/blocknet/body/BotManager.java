package dev.blocknet.body;

import com.mojang.authlib.GameProfile;
import dev.blocknet.Blocknet;
import dev.blocknet.control.PathingDefaults;
import dev.blocknet.pathing.api.IBaritone;
import dev.blocknet.pathing.api.utils.IInteractionController;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Spawns, tracks and removes bots on the running server. */
public final class BotManager {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Map<String, BotPlayer> BOTS = new LinkedHashMap<>();

    private BotManager() {}

    /**
     * Joins a new bot to the server like a player logging in, then moves it to {@code pos}.
     *
     * @throws IllegalArgumentException if the name is not a valid player name or is already online
     */
    public static BotPlayer spawn(MinecraftServer server, String name, ServerLevel level, Vec3 pos, float yaw) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Bot name must be 3-16 letters, digits or underscores: " + name);
        }
        if (server.getPlayerList().getPlayerByName(name) != null) {
            throw new IllegalArgumentException("A player named " + name + " is already online");
        }

        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        BotPlayer bot = new BotPlayer(server, level, profile);
        // PlayerListMixin swaps in BotPacketListener for BotPlayer, so nothing is ever sent to this connection.
        // OTHER: the bot negotiated no NeoForge channels, like a vanilla client.
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, bot.clientInformation(), false, ConnectionType.OTHER);
        server.getPlayerList().placeNewPlayer(BotConnection.create(), bot, cookie);

        bot.setGameMode(GameType.SURVIVAL);
        // Player data saved by an older build after a death can hold zero health.
        if (bot.isDeadOrDying()) {
            bot.setHealth(bot.getMaxHealth());
        }
        bot.teleportTo(level, pos.x, pos.y, pos.z, yaw, 0.0F);
        bot.setYHeadRot(yaw);
        PathingDefaults.apply(IBaritone.KEY.get(bot).settings());
        BOTS.put(key(name), bot);
        dev.blocknet.bridge.BridgeServer.botsChanged();
        Blocknet.LOGGER.info("Spawned bot {} at {}", name, pos);
        return bot;
    }

    /** Removes the bot from the world. Its player data is saved like a player's on logout. */
    public static boolean despawn(String name) {
        BotPlayer bot = BOTS.remove(key(name));
        if (bot == null) {
            return false;
        }
        dev.blocknet.bridge.BridgeServer.botsChanged();
        bot.moments().flushDamage();
        bot.controller().stop();
        if (bot.connection instanceof BotPacketListener listener) {
            listener.removeNow(new DisconnectionDetails(Component.literal("Despawned")));
        }
        return true;
    }

    @Nullable
    public static BotPlayer get(String name) {
        return BOTS.get(key(name));
    }

    /** The bot in {@code level} closest to {@code pos}, if there is any. */
    public static Optional<BotPlayer> nearest(ServerLevel level, Vec3 pos) {
        return BOTS.values().stream()
            .filter(bot -> bot.serverLevel() == level && bot.isAlive() && !bot.isRemoved())
            .min(Comparator.comparingDouble(bot -> bot.position().distanceToSqr(pos)));
    }

    public static Collection<BotPlayer> all() {
        return Collections.unmodifiableCollection(BOTS.values());
    }

    /** Stops tracking a bot that is leaving (for example after dying), so no command reaches it. */
    static void forget(BotPlayer bot) {
        BOTS.values().remove(bot);
        dev.blocknet.bridge.BridgeServer.botsChanged();
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof BotPlayer bot) {
            bot.controller().stop();
            BOTS.values().remove(bot);
        dev.blocknet.bridge.BridgeServer.botsChanged();
            // The pathfinder keeps per-entity state by UUID; a bot spawned again starts fresh.
            IBaritone.KEY.remove(bot);
            IInteractionController.KEY.remove(bot);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        List<String> names = new ArrayList<>(BOTS.keySet());
        names.forEach(BotManager::despawn);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
