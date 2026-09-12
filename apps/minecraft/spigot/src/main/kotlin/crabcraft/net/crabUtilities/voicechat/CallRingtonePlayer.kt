package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import de.maxhenkel.voicechat.api.opus.OpusEncoder
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.LongSupplier
import java.util.function.Supplier
import java.util.logging.Logger

/** Plays the two bundled call tones only to their intended local voice-chat player. */
class CallRingtonePlayer(private val api: VoicechatServerApi, incoming: ShortArray, outgoing: ShortArray,
                         private val clock: LongSupplier, private val logger: Logger) : AutoCloseable {
    private val clips = EnumMap<VoiceMessages.RingDirection, ShortArray>(VoiceMessages.RingDirection::class.java)
    private val endpoints = ConcurrentHashMap<SessionKey, Endpoint>()
    @Volatile private var closed = false

    init {
        require(incoming.isNotEmpty() && outgoing.isNotEmpty()) { "Call ringtone clips must not be empty" }
        clips[VoiceMessages.RingDirection.INCOMING] = incoming
        clips[VoiceMessages.RingDirection.OUTGOING] = outgoing
    }

    @Throws(IOException::class)
    constructor(api: VoicechatServerApi, logger: Logger) : this(api, loadClip(INCOMING_RESOURCE), loadClip(OUTGOING_RESOURCE), LongSupplier(System::currentTimeMillis), logger)

    fun start(start: VoiceMessages.CallRingStart) {
        if (closed) return
        val now = clock.asLong
        if (start.expiresAtMillis() <= now) return
        val connection = api.getConnectionOf(start.playerId())
        if (connection == null || !connection.isInstalled() || !connection.isConnected()) return
        // Bound forged or skewed deadlines to the invitation's intended lifetime.
        val maximumDeadline = if (now > Long.MAX_VALUE - MAX_RING_DURATION_MILLIS) Long.MAX_VALUE else now + MAX_RING_DURATION_MILLIS
        val safeDeadline = Math.min(start.expiresAtMillis(), maximumDeadline)
        val key = SessionKey(start.playerId(), start.direction())
        val endpoint = endpoints.compute(key) { _, existing -> existing ?: createEndpoint(key) }
        endpoint?.start(start.token(), safeDeadline, connection)
    }

    fun stop(stop: VoiceMessages.CallRingStop) { endpoints[SessionKey(stop.playerId(), stop.direction())]?.stopToken(stop.token()) }
    fun removePlayer(playerId: UUID) {
        for (direction in VoiceMessages.RingDirection.values()) endpoints.remove(SessionKey(playerId, direction))?.close()
    }

    private fun createEndpoint(key: SessionKey): Endpoint? {
        if (closed) return null
        try {
            val channel = api.createStaticAudioChannel(channelId(key))
            if (channel == null) {
                logger.warning("Could not create the " + key.direction.name.lowercase(java.util.Locale.getDefault()) + " call ringtone channel for " + key.playerId)
                return null
            }
            channel.setBypassGroupIsolation(true)
            channel.setCategory(VoiceMediaRegistry.CALL_CATEGORY)
            channel.setFilter { player -> key.playerId == player.getUuid() }
            return Endpoint(channel, clips[key.direction]!!)
        } catch (e: Exception) {
            logger.warning("Could not initialise a call ringtone channel for " + key.playerId)
            return null
        }
    }

    override fun close() {
        closed = true
        for (endpoint in endpoints.values) endpoint.close()
        endpoints.clear()
    }

    private data class SessionKey(val playerId: UUID, val direction: VoiceMessages.RingDirection)

    private inner class Endpoint(private val channel: StaticAudioChannel, private val clip: ShortArray) : AutoCloseable {
        private var target: VoicechatConnection? = null
        private var playback: Playback? = null
        private var endpointClosed = false

        @Synchronized fun start(token: String, deadline: Long, connection: VoicechatConnection) {
            if (closed || endpointClosed) return
            if (!setTarget(connection)) return
            if (playback?.frames?.addToken(token, deadline) == true) return
            playback?.let { stale -> playback = null; stale.stop() }
            val frames = LoopingFrames(clip, clock)
            frames.addToken(token, deadline)
            var encoder: OpusEncoder? = null
            var opened: Playback? = null
            try {
                encoder = api.createEncoder(OpusEncoderMode.AUDIO)
                val player = api.createAudioPlayer(channel, encoder, frames)
                opened = Playback(player, encoder, frames)
                val callbackPlayback = opened
                player.setOnStopped { onPlaybackStopped(callbackPlayback) }
                playback = opened
                player.startPlaying()
            } catch (e: Exception) {
                if (playback === opened) playback = null
                if (opened != null) opened.stop() else if (encoder != null) try { encoder.close() } catch (_: Exception) {}
                logger.warning("Could not start a call ringtone")
            }
        }

        fun stopToken(token: String) {
            var stopped: Playback? = null
            synchronized(this) {
                if (playback?.frames?.removeToken(token) == true) { stopped = playback; playback = null }
            }
            stopped?.stop()
        }

        private fun onPlaybackStopped(stopped: Playback) {
            synchronized(this) { if (playback === stopped) playback = null }
            stopped.release(false)
        }

        private fun setTarget(connection: VoicechatConnection): Boolean {
            if (target === connection) return true
            if (target != null) try { channel.removeTarget(target) } catch (_: Exception) {}
            try {
                channel.addTarget(connection)
                target = connection
                return true
            } catch (e: Exception) { target = null; return false }
        }

        override fun close() {
            val stopped: Playback?
            val removedTarget: VoicechatConnection?
            synchronized(this) {
                if (endpointClosed) return
                endpointClosed = true
                stopped = playback
                playback = null
                removedTarget = target
                target = null
            }
            stopped?.stop()
            if (removedTarget != null) try { channel.removeTarget(removedTarget) } catch (_: Exception) {}
            try { channel.flush() } catch (_: Exception) {}
            try { channel.clearTargets() } catch (_: Exception) {}
        }
    }

    private class Playback(private val player: AudioPlayer, private val encoder: OpusEncoder, val frames: LoopingFrames) {
        private val released = AtomicBoolean()
        fun stop() { frames.stop(); release(true) }
        fun release(stopPlayer: Boolean) {
            if (!released.compareAndSet(false, true)) return
            if (stopPlayer) try { player.stopPlaying() } catch (_: Exception) {}
            try { encoder.close() } catch (_: Exception) {}
        }
    }

    /** Token-aware, deadline-limited 20 ms frame source shared by one direction. */
    class LoopingFrames(private val clip: ShortArray, private val clock: LongSupplier) : Supplier<ShortArray?> {
        private val deadlines = HashMap<String, Long>()
        private var cursor = 0
        private var stopped = false
        init { require(clip.isNotEmpty()) { "Ringtone clip is empty" } }
        @Synchronized fun addToken(token: String, deadline: Long): Boolean {
            if (stopped) return false
            deadlines.merge(token, deadline, Math::max)
            return true
        }
        /** Returns true only when removing this exact token ends the playback. */
        @Synchronized fun removeToken(token: String): Boolean {
            if (deadlines.remove(token) == null || deadlines.isNotEmpty()) return false
            stopped = true
            return true
        }
        @Synchronized fun stop() { stopped = true; deadlines.clear() }
        @Synchronized override fun get(): ShortArray? {
            if (stopped) return null
            val now = clock.asLong
            deadlines.entries.removeIf { it.value <= now }
            if (deadlines.isEmpty()) { stopped = true; return null }
            val finalDeadline = deadlines.values.maxOrNull() ?: now
            val remainingMillis = finalDeadline - now
            if (remainingMillis <= 0L) { stopped = true; return null }
            val audibleSamples = Math.min(FRAME_SAMPLES.toLong(), remainingMillis * SAMPLES_PER_MILLISECOND).toInt()
            val frame = ShortArray(FRAME_SAMPLES)
            for (sample in 0 until audibleSamples) {
                frame[sample] = clip[cursor++]
                if (cursor == clip.size) cursor = 0
            }
            return frame
        }
    }

    companion object {
        const val MAX_RING_DURATION_MILLIS = 30_000L
        const val FRAME_SAMPLES = 960
        const val RINGTONE_GAIN = 0.5
        private const val SAMPLES_PER_MILLISECOND = 48
        // 48 kHz mono, signed 16-bit little-endian PCM avoids a native MP3 decoder at runtime.
        private const val INCOMING_RESOURCE = "crabcraft/call/incoming_ringtone.pcm"
        private const val OUTGOING_RESOURCE = "crabcraft/call/outgoing_ringtone.pcm"
        private fun channelId(key: SessionKey): UUID = UUID.nameUUIDFromBytes(("crabcraft:svc:call-ringtone:" + key.playerId + ':' + key.direction.name).toByteArray(StandardCharsets.UTF_8))
        @JvmStatic @Throws(IOException::class) fun loadClip(resource: String): ShortArray {
            CallRingtonePlayer::class.java.classLoader.getResourceAsStream(resource).use { input ->
                if (input == null) throw IOException("Missing bundled call ringtone " + resource)
                val encoded = input.readAllBytes()
                if (encoded.isEmpty() || (encoded.size and 1) != 0) throw IOException("Call ringtone contains invalid PCM audio: " + resource)
                val decoded = ShortArray(encoded.size / 2)
                for (i in decoded.indices) {
                    val offset = i * 2
                    decoded[i] = ((encoded[offset].toInt() and 0xFF) or (encoded[offset + 1].toInt() shl 8)).toShort()
                }
                OpusVolumeScaler.applyGain(decoded, RINGTONE_GAIN)
                return decoded
            }
        }
    }
}
