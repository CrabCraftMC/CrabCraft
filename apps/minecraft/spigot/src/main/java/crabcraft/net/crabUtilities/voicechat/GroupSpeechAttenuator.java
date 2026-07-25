package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

import java.util.UUID;
import java.util.logging.Logger;

/** Reduces native player speech only when Simple Voice Chat routes it as lofi-group audio. */
final class GroupSpeechAttenuator implements AutoCloseable {
    private final VoicechatServerApi api;
    private final UUID groupId;
    private final OpusVolumeScaler scaler;
    private final Logger logger;

    GroupSpeechAttenuator(VoicechatServerApi api, UUID groupId, double gain, Logger logger) {
        this.api = api;
        this.groupId = groupId;
        this.scaler = new OpusVolumeScaler(api, gain);
        this.logger = logger;
    }

    void onStaticSoundPacket(StaticSoundPacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        VoicechatConnection receiver = event.getReceiverConnection();
        if (receiver == null || !shouldAttenuate(event.getSource(), groupId, sender)) return;

        StaticSoundPacket original = event.getPacket();
        if (original == null) return;
        try {
            byte[] scaled = scaler.scale(sender.getPlayer().getUuid(), original.getSequenceNumber(),
                    original.getOpusEncodedData());
            StaticSoundPacket replacement = original.staticSoundPacketBuilder()
                    .opusEncodedData(scaled)
                    .build();
            if (event.cancel()) api.sendStaticSoundPacketTo(receiver, replacement);
        } catch (Exception e) {
            logger.fine(() -> "Could not attenuate lofi group speech: " + e.getMessage());
        }
    }

    static boolean shouldAttenuate(String source, UUID groupId, VoicechatConnection sender) {
        return SoundPacketEvent.SOURCE_GROUP.equals(source)
                && sender != null
                && sender.getGroup() != null
                && groupId.equals(sender.getGroup().getId());
    }

    void remove(UUID speakerId) { scaler.remove(speakerId); }

    @Override public void close() { scaler.close(); }
}
