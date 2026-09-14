package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.opus.OpusDecoder
import de.maxhenkel.voicechat.api.opus.OpusEncoder
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stateful Opus decode/scale/re-encode pipeline, isolated per speaker. */
class OpusVolumeScaler(private val api: VoicechatServerApi, gain: Double) : AutoCloseable {
    private val gain = clampGain(gain)
    private val speakers = ConcurrentHashMap<UUID, SpeakerState>()

    fun scale(speakerId: UUID, sequence: Long, opus: ByteArray?): ByteArray? = scale(speakerId, sequence, true, opus)
    fun scaleNext(speakerId: UUID, opus: ByteArray?): ByteArray? = scale(speakerId, 0L, false, opus)

    private fun scale(speakerId: UUID, sequence: Long, cacheBySequence: Boolean, opus: ByteArray?): ByteArray? {
        if (opus == null || gain >= 1.0) return opus
        val state = speakers.computeIfAbsent(speakerId) { SpeakerState(api.createDecoder(), api.createEncoder(OpusEncoderMode.VOIP)) }
        synchronized(state) {
            if (cacheBySequence && state.cached != null && state.sequence == sequence) return state.cached
            if (opus.isEmpty()) {
                state.decoder.resetState()
                state.encoder.resetState()
                state.sequence = sequence
                state.cached = opus
                return opus
            }
            val pcm = state.decoder.decode(opus)
            applyGain(pcm, gain)
            val scaled = state.encoder.encode(pcm)
            state.sequence = sequence
            state.cached = scaled
            return scaled
        }
    }

    fun remove(speakerId: UUID) { speakers.remove(speakerId)?.close() }
    override fun close() {
        speakers.values.forEach { it.close() }
        speakers.clear()
    }

    private class SpeakerState(val decoder: OpusDecoder, val encoder: OpusEncoder) {
        var sequence = Long.MIN_VALUE
        var cached: ByteArray? = null
        fun close() {
            try { decoder.close() } catch (_: Exception) {}
            try { encoder.close() } catch (_: Exception) {}
        }
    }

    companion object {
        @JvmStatic fun applyGain(samples: ShortArray, gain: Double) {
            val bounded = clampGain(gain)
            for (i in samples.indices) {
                val scaled = Math.round(samples[i] * bounded)
                samples[i] = Math.max(Short.MIN_VALUE.toLong(), Math.min(Short.MAX_VALUE.toLong(), scaled)).toShort()
            }
        }
        private fun clampGain(gain: Double): Double = Math.max(0.0, Math.min(1.0, gain))
    }
}
