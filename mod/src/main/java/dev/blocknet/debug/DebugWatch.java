// SPDX-License-Identifier: MIT
package dev.blocknet.debug;

import dev.blocknet.body.BotManager;
import dev.blocknet.body.BotPlayer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Which player watches which bot, and the frames sent to them. With the client mod a player gets
 * the full overlay; without it, a few dust particles (target and route) and an action-bar line.
 */
public final class DebugWatch {
    private static final int FRAME_EVERY_TICKS = 4;
    private static final int SCENE_EVERY_TICKS = 20;
    private static final Map<UUID, String> WATCHERS = new HashMap<>();
    private static final Map<String, DebugFrames.Scene> SCENES = new HashMap<>();

    private DebugWatch() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        // Optional: vanilla clients and clients without this mod still join; they get the fallback.
        event.registrar("1").optional().playToClient(DebugPayload.TYPE, DebugPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> dev.blocknet.client.DebugClient.receive(payload)));
    }

    /** Toggles watching; returns the bot now watched, or null when the player stopped watching. */
    @Nullable
    public static String toggle(ServerPlayer player, BotPlayer bot) {
        String name = key(bot);
        if (name.equals(WATCHERS.get(player.getUUID()))) {
            stop(player);
            return null;
        }
        WATCHERS.put(player.getUUID(), name);
        bot.trace().add("debug", player.getGameProfile().getName() + " started watching");
        return bot.getGameProfile().getName();
    }

    public static boolean stop(ServerPlayer player) {
        boolean removed = WATCHERS.remove(player.getUUID()) != null;
        if (removed && hasOverlay(player)) {
            // An empty frame clears the overlay at once instead of waiting for it to go stale.
            PacketDistributor.sendToPlayer(player, empty());
        }
        return removed;
    }

    public static boolean hasOverlay(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(DebugPayload.TYPE);
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHERS.remove(event.getEntity().getUUID());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Set<String> watched = new HashSet<>(WATCHERS.values());
        for (BotPlayer bot : BotManager.all()) bot.vision().recording(watched.contains(key(bot)));
        if (WATCHERS.isEmpty() || server.getTickCount() % FRAME_EVERY_TICKS != 0) return;
        Map<String, DebugPayload> frames = new HashMap<>();
        WATCHERS.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            BotPlayer bot = BotManager.get(entry.getValue());
            if (bot == null || !bot.isAlive()) return false; // Keep watching across death and respawn.
            DebugPayload frame = frames.computeIfAbsent(entry.getValue(), name -> {
                DebugFrames.Scene scene = SCENES.get(name);
                if (scene == null || bot.serverLevel().getGameTime() - scene.tick() >= SCENE_EVERY_TICKS
                        || bot.serverLevel().getGameTime() < scene.tick()) {
                    scene = DebugFrames.scene(bot);
                    SCENES.put(name, scene);
                }
                return DebugFrames.frame(bot, scene);
            });
            if (hasOverlay(player)) PacketDistributor.sendToPlayer(player, frame);
            else if (server.getTickCount() % 20 == 0) fallback(player, bot, frame);
            return false;
        });
        SCENES.keySet().retainAll(WATCHERS.values());
    }

    /** Vanilla-visible hints: red target corners, green route, yellow goal, and the current step. */
    private static void fallback(ServerPlayer player, BotPlayer bot, DebugPayload frame) {
        if (player.serverLevel() == bot.serverLevel()) {
            if (frame.target() != null) corners(player, frame.target(), new Vector3f(1, 0.1F, 0.1F));
            for (DebugPayload.Goal goal : frame.goals().subList(0, Math.min(4, frame.goals().size()))) {
                dust(player, goal.pos().getX() + 0.5, goal.pos().getY() + 0.2, goal.pos().getZ() + 0.5, new Vector3f(1, 0.9F, 0.1F));
            }
            List<BlockPos> path = frame.path();
            for (int i = Math.max(0, frame.pathIndex()); i < path.size() && i < frame.pathIndex() + 40; i++) {
                BlockPos pos = path.get(i);
                dust(player, pos.getX() + 0.5, pos.getY() + 0.15, pos.getZ() + 0.5, new Vector3f(0.2F, 1, 0.3F));
            }
        }
        String last = frame.trace().isEmpty() ? "" : " | " + frame.trace().getLast().source() + ": " + frame.trace().getLast().text();
        String line = bot.getGameProfile().getName() + ": " + bot.controller().describe() + last;
        player.displayClientMessage(Component.literal(line.length() > 180 ? line.substring(0, 180) + "..." : line), true);
    }

    private static void corners(ServerPlayer player, BlockPos pos, Vector3f color) {
        for (int corner = 0; corner < 8; corner++) {
            dust(player, pos.getX() + (corner & 1), pos.getY() + (corner >> 1 & 1), pos.getZ() + (corner >> 2 & 1), color);
        }
    }

    private static void dust(ServerPlayer player, double x, double y, double z, Vector3f color) {
        player.serverLevel().sendParticles(player, new DustParticleOptions(color, 1.0F), true, x, y, z, 1, 0, 0, 0, 0);
    }

    /** A fresh frame for one bot, as a watcher would get it; GameTests check it and its codec. */
    public static DebugPayload frame(BotPlayer bot) {
        return DebugFrames.frame(bot, DebugFrames.scene(bot));
    }

    private static DebugPayload empty() {
        return new DebugPayload(-1, "", List.of(), null, null, List.of(), List.of(), 0, List.of(), List.of(), List.of(),
            -1, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static String key(BotPlayer bot) {
        return bot.getGameProfile().getName().toLowerCase(java.util.Locale.ROOT);
    }
}
