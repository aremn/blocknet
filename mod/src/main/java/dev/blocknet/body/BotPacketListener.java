package dev.blocknet.body;

import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Game packet listener of a bot, installed by {@code PlayerListMixin} in place of the vanilla one.
 *
 * <p>Sends are dropped here, before NeoForge validates them: NeoForge throws when a modded payload is
 * sent to a connection that never negotiated its channel, and in a big modpack many mods broadcast
 * payloads to every player. Channel checks keep their default answer: the bot has no modded client,
 * so mods that ask first skip it, like a vanilla client.
 */
public final class BotPacketListener extends ServerGamePacketListenerImpl {
    private boolean disconnected;

    public BotPacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void send(Packet<?> packet) {}

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {}

    @Override
    public boolean isAcceptingMessages() {
        return !disconnected;
    }

    /**
     * A kick (for example {@code /kick} or a duplicate login) removes the bot from the world. A real
     * connection would do this once its channel closes; the bot has no channel, so it happens here,
     * on the next server task to keep player-list iteration safe.
     */
    @Override
    public void disconnect(DisconnectionDetails details) {
        if (disconnected) {
            return;
        }
        disconnected = true;
        server.execute(() -> onDisconnect(details));
    }

    /** Removes the bot from the world right away. Must not be called while iterating the player list. */
    public void removeNow(DisconnectionDetails details) {
        if (disconnected) {
            return;
        }
        disconnected = true;
        onDisconnect(details);
    }
}
