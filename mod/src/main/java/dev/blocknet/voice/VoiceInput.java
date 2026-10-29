// SPDX-License-Identifier: MIT
package dev.blocknet.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blocknet.body.BotManager;
import dev.blocknet.bridge.BridgeServer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * What a bot hears: a player's voice, as a proximity voice-chat mod delivers it, cut into phrases. A
 * phrase ends after a short pause (or the push-to-talk release); it goes to the brain together with
 * the bots close enough to have heard it. The voice mod adapter only feeds decoded frames in here.
 */
public final class VoiceInput {
    /** Bots hear a voice about as far as voice chat carries an ordinary voice indoors. */
    public static final double HEAR_RANGE = 24;
    static final int RATE = 48_000, OUT_RATE = 16_000;
    /** Silence that ends a phrase. */
    static final long PAUSE_MS = 450;
    static final int MIN_MS = 300, MAX_MS = 30_000;

    private static final class Phrase {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        volatile long lastFrame;
        int samples;
    }

    private static final Map<UUID, Phrase> PHRASES = new ConcurrentHashMap<>();

    private VoiceInput() {}

    /** Cheap check before decoding: is any bot near enough to hear this player? */
    public static boolean anyBotHears(ServerPlayer speaker) {
        return BotManager.all().stream().anyMatch(bot -> hears(bot, speaker));
    }

    static boolean hears(ServerPlayer bot, ServerPlayer speaker) {
        return bot != speaker && bot.level() == speaker.level() && bot.distanceTo(speaker) <= HEAR_RANGE;
    }

    /** One decoded 48 kHz mono frame, from the voice thread. */
    public static void frame(ServerPlayer speaker, short[] samples) {
        var phrase = PHRASES.computeIfAbsent(speaker.getUUID(), id -> new Phrase());
        synchronized (phrase) {
            if (phrase.samples >= RATE / 1000 * MAX_MS) return;
            phrase.pcm.write(downsample(samples), 0, samples.length / 3 * 2);
            phrase.samples += samples.length;
            phrase.lastFrame = System.currentTimeMillis();
        }
    }

    /** 48 kHz to 16 kHz little-endian bytes: the mean of each three samples is a cheap low-pass. */
    static byte[] downsample(short[] samples) {
        var out = ByteBuffer.allocate(samples.length / 3 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 2 < samples.length; i += 3) out.putShort((short) ((samples[i] + samples[i + 1] + samples[i + 2]) / 3));
        return out.array();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PHRASES.isEmpty()) finish(event.getServer(), System.currentTimeMillis()).forEach(BridgeServer::moment);
    }

    /** Phrases that ended by {@code now}, as voice events for the bots that heard them. */
    public static java.util.List<JsonObject> finish(net.minecraft.server.MinecraftServer server, long now) {
        var finished = new java.util.ArrayList<JsonObject>();
        PHRASES.forEach((id, phrase) -> {
            if (now - phrase.lastFrame < PAUSE_MS) return;
            PHRASES.remove(id);
            byte[] pcm;
            int ms;
            synchronized (phrase) { pcm = phrase.pcm.toByteArray(); ms = (int) ((long) phrase.samples * 1000 / RATE); }
            var speaker = server.getPlayerList().getPlayer(id);
            if (speaker == null || ms < MIN_MS) return;
            var bots = new JsonArray();
            BotManager.all().stream().filter(bot -> hears(bot, speaker)).forEach(bot -> bots.add(bot.getGameProfile().getName()));
            if (bots.isEmpty()) return;
            JsonObject message = BridgeServer.playerMessage(speaker, "voice");
            message.add("bots", bots);
            message.addProperty("ms", ms);
            message.addProperty("wav", Base64.getEncoder().encodeToString(wav(pcm, OUT_RATE)));
            finished.add(message);
        });
        return finished;
    }

    /** 16-bit mono WAV around raw little-endian samples. */
    public static byte[] wav(byte[] pcm, int rate) {
        var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII))
            .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
        var out = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
