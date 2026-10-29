// SPDX-License-Identifier: MIT
package dev.blocknet.voice;

import dev.blocknet.body.BotPlayer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A bot's spoken voice: synthesized speech (from the brain) played from the bot's position through
 * a proximity voice-chat mod, so players hear it where the bot stands. The voice mod adapter
 * registers the speaker; without one, speech stays text-only.
 */
public final class VoiceOutput {
    public interface Speaker {
        /** Play 48 kHz mono samples from the bot; false when the voice server is not running. */
        boolean speak(BotPlayer bot, short[] samples);
    }

    private static volatile Speaker speaker;

    private VoiceOutput() {}

    public static void register(Speaker voice) { speaker = voice; }

    public static boolean available() { return speaker != null; }

    /** Plays a 16-bit mono WAV of any rate; returns what happened, for the caller's log. */
    public static String speak(BotPlayer bot, byte[] wav) {
        var voice = speaker;
        if (voice == null) return "no voice chat on this server; text only";
        var buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44 || buffer.getInt(0) != 0x46464952 || buffer.getShort(22) != 1 || buffer.getShort(34) != 16)
            return "failed: expected a 16-bit mono WAV";
        int rate = buffer.getInt(24);
        // Find the data chunk (some writers add LIST chunks before it).
        int at = 12;
        while (at + 8 <= wav.length && buffer.getInt(at) != 0x61746164) at += 8 + buffer.getInt(at + 4);
        if (at + 8 > wav.length) return "failed: no audio data";
        int count = Math.min(buffer.getInt(at + 4), wav.length - at - 8) / 2;
        short[] in = new short[count];
        for (int i = 0; i < count; i++) in[i] = buffer.getShort(at + 8 + i * 2);
        short[] out = resample(in, rate, 48_000);
        return voice.speak(bot, out) ? "spoke " + out.length * 1000L / 48_000 + " ms" : "voice server not running; text only";
    }

    /** Linear interpolation is plenty for speech. */
    static short[] resample(short[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int length = (int) ((long) in.length * to / from);
        short[] out = new short[length];
        for (int i = 0; i < length; i++) {
            double pos = (double) i * from / to;
            int base = (int) pos;
            double frac = pos - base;
            int next = Math.min(base + 1, in.length - 1);
            out[i] = (short) Math.round(in[base] * (1 - frac) + in[next] * frac);
        }
        return out;
    }
}
