// SPDX-License-Identifier: MIT
package dev.blocknet.compat.voicechat;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import dev.blocknet.body.BotPlayer;
import dev.blocknet.voice.VoiceInput;
import dev.blocknet.voice.VoiceOutput;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bots hear players and speak through Simple Voice Chat. The voice mod discovers this class by its
 * annotation and loads it only when it is installed; without it nothing here is ever touched.
 */
@ForgeVoicechatPlugin
public final class BlocknetVoicePlugin implements VoicechatPlugin {
    private VoicechatApi api;
    private volatile VoicechatServerApi server;
    /** Opus keeps state between frames, so each speaker needs a decoder of their own. */
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, EntityAudioChannel> mouths = new ConcurrentHashMap<>();

    @Override public String getPluginId() { return "blocknet_ai"; }

    @Override public void initialize(VoicechatApi api) { this.api = api; }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::microphone);
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            server = event.getVoicechat();
            mouths.clear();
            VoiceOutput.register(this::speak);
        });
    }

    private void microphone(MicrophonePacketEvent event) {
        var sender = event.getSenderConnection();
        if (sender == null || !(sender.getPlayer().getPlayer() instanceof ServerPlayer speaker)) return;
        if (speaker instanceof BotPlayer || !VoiceInput.anyBotHears(speaker)) return;
        byte[] opus = event.getPacket().getOpusEncodedData();
        var decoder = decoders.computeIfAbsent(speaker.getUUID(), id -> api.createDecoder());
        if (opus.length == 0) { decoder.resetState(); return; }
        VoiceInput.frame(speaker, decoder.decode(opus));
    }

    private boolean speak(BotPlayer bot, short[] samples) {
        var voice = server;
        if (voice == null) return false;
        var mouth = mouths.computeIfAbsent(bot.getUUID(), id -> {
            var channel = voice.createEntityAudioChannel(UUID.randomUUID(), voice.fromEntity(bot));
            if (channel != null) channel.setDistance((float) VoiceInput.HEAR_RANGE);
            return channel;
        });
        if (mouth == null || mouth.isClosed()) { mouths.remove(bot.getUUID()); return false; }
        // A respawned bot is a new entity object: keep the channel attached to the live one.
        mouth.updateEntity(voice.fromEntity(bot));
        voice.createAudioPlayer(mouth, voice.createEncoder(), samples).startPlaying();
        return true;
    }
}
