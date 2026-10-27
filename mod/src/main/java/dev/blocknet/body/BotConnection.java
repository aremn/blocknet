package dev.blocknet.body;

import io.netty.channel.embedded.EmbeddedChannel;
import javax.annotation.Nullable;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Network connection of a bot. There is no client behind it: everything the server sends is dropped.
 *
 * <p>It sits on an inert {@link EmbeddedChannel}, the same way vanilla's GameTest mock players do,
 * because NeoForge and mods read per-connection attributes from {@code connection.channel()}.
 */
public final class BotConnection extends Connection {
    private BotConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    public static BotConnection create() {
        BotConnection connection = new BotConnection();
        // Registering the connection as the channel's handler activates it and sets its channel.
        new EmbeddedChannel(connection);
        return connection;
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {}

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {}

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {}

    @Override
    public void setReadOnly() {}

    @Override
    public void handleDisconnection() {}

    @Override
    public void flushChannel() {}
}
