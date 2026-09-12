package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.SoundPacketEvent
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent
import java.util.UUID
import java.util.logging.Logger

/** Reduces native player speech only when Simple Voice Chat routes it as lofi-group audio. */
class GroupSpeechAttenuator(private val api: VoicechatServerApi, private val groupId: UUID, gain: Double, private val logger: Logger) : AutoCloseable {
    private val scaler = OpusVolumeScaler(api, gain)

    fun onStaticSoundPacket(event: StaticSoundPacketEvent) {
        val sender = event.getSenderConnection()
        val receiver = event.getReceiverConnection()
        if (receiver == null || !shouldAttenuate(event.getSource(), groupId, sender)) return
        val original = event.getPacket() ?: return
        try {
            val scaled = scaler.scale(sender!!.getPlayer().getUuid(), original.getSequenceNumber(), original.getOpusEncodedData())
            val replacement = original.staticSoundPacketBuilder().opusEncodedData(scaled).build()
            if (event.cancel()) api.sendStaticSoundPacketTo(receiver, replacement)
        } catch (e: Exception) {
            logger.fine { "Could not attenuate lofi group speech: " + e.message }
        }
    }

    fun remove(speakerId: UUID) { scaler.remove(speakerId) }
    override fun close() { scaler.close() }

    companion object {
        @JvmStatic fun shouldAttenuate(source: String?, groupId: UUID, sender: VoicechatConnection?): Boolean =
            SoundPacketEvent.SOURCE_GROUP == source && sender != null && sender.getGroup() != null && groupId == sender.getGroup()!!.getId()
    }
}
