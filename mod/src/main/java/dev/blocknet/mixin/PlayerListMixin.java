package dev.blocknet.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.blocknet.body.BotPacketListener;
import dev.blocknet.body.BotPlayer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives bots their own packet listener while they log in. It has to be in place before
 * {@code placeNewPlayer} fires the datapack sync and login events, because mods react to those by
 * sending payloads to the new player.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @WrapOperation(
        method = "placeNewPlayer",
        at = @At(value = "NEW", target = "net/minecraft/server/network/ServerGamePacketListenerImpl")
    )
    private ServerGamePacketListenerImpl blocknet$useBotListener(
        MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie,
        Operation<ServerGamePacketListenerImpl> original
    ) {
        if (player instanceof BotPlayer) {
            return new BotPacketListener(server, connection, player, cookie);
        }
        return original.call(server, connection, player, cookie);
    }
    @WrapOperation(method = "respawn",
        at = @At(value = "NEW", target = "net/minecraft/server/level/ServerPlayer"))
    private ServerPlayer blocknet$respawnBot(MinecraftServer server, net.minecraft.server.level.ServerLevel level,
            com.mojang.authlib.GameProfile profile, net.minecraft.server.level.ClientInformation info,
            Operation<ServerPlayer> original, ServerPlayer oldPlayer) {
        if (!(oldPlayer instanceof BotPlayer)) return original.call(server, level, profile, info);
        BotPlayer bot = new BotPlayer(server, level, profile);
        // Vanilla's packet handler normally rebinds this after respawn; our inert listener has no handler.
        oldPlayer.connection.player = bot;
        return bot;
    }
}
