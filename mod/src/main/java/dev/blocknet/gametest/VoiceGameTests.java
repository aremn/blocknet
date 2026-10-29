// SPDX-License-Identifier: MIT
package dev.blocknet.gametest;

import dev.blocknet.Blocknet;
import dev.blocknet.body.BotManager;
import dev.blocknet.voice.VoiceInput;
import dev.blocknet.voice.VoiceOutput;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Voice without a voice mod: frames in, one phrase out for the bots in earshot; speech degrades to text. */
@GameTestHolder(Blocknet.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VoiceGameTests {
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void voiceFramesBecomeOnePhraseForBotsInEarshot(GameTestHelper h) {
        TestArena.floor(h);
        var speaker = TestArena.spawnBot(h, "BnVoiceSpeaker", new Vec3(2.5, 1, 2.5));
        var listener = TestArena.spawnBot(h, "BnVoiceListener", new Vec3(6.5, 1, 6.5));
        // Half a second of a 440 Hz tone in 20 ms frames, as voice chat decodes them.
        for (int frame = 0; frame < 25; frame++) {
            short[] samples = new short[960];
            for (int i = 0; i < samples.length; i++) samples[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * (frame * 960 + i) / 48_000.0));
            VoiceInput.frame(speaker, samples);
        }
        long now = System.currentTimeMillis();
        h.assertTrue(VoiceInput.finish(h.getLevel().getServer(), now).isEmpty(), "a phrase does not end while the speaker is still talking");
        var phrases = VoiceInput.finish(h.getLevel().getServer(), now + 1_000);
        h.assertTrue(phrases.size() == 1, "one phrase after the pause: " + phrases.size());
        var phrase = phrases.getFirst();
        h.assertTrue(phrase.get("from").getAsString().equals("BnVoiceSpeaker") && phrase.get("ms").getAsInt() == 500, "speaker and length: " + phrase.get("ms"));
        h.assertTrue(phrase.getAsJsonArray("bots").toString().contains("BnVoiceListener")
            && !phrase.getAsJsonArray("bots").toString().contains("BnVoiceSpeaker"), "only other bots hear it: " + phrase.get("bots"));
        var wav = ByteBuffer.wrap(Base64.getDecoder().decode(phrase.get("wav").getAsString())).order(ByteOrder.LITTLE_ENDIAN);
        h.assertTrue(wav.getInt(24) == 16_000 && wav.getInt(40) == 8_000 * 2, "16 kHz mono, 8000 samples: " + wav.getInt(24) + " " + wav.getInt(40));
        h.assertTrue(VoiceOutput.speak(listener, new byte[44]).startsWith("no voice chat") || VoiceOutput.available(),
            "speech without a voice mod stays text-only");
        BotManager.despawn("BnVoiceSpeaker");
        BotManager.despawn("BnVoiceListener");
        h.succeed();
    }
}
