package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import de.maxhenkel.voicechat.api.opus.OpusEncoder
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry
import crabcraft.net.crabUtilities.media.audio.AudioEngine
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** Continuously resolves and plays one live stream to local members of the lofi group. */
class LofiStreamPlayer(private val api: VoicechatServerApi, private val groupId: UUID, private val sourceUrl: String,
                       musicVolume: Float, private val logger: Logger) : AutoCloseable {
    private val musicVolume = Math.max(0F, Math.min(1F, musicVolume))
    private val targets = ConcurrentHashMap<UUID, VoicechatConnection>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "CrabUtilities-Lofi").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile private var channel: StaticAudioChannel? = null
    @Volatile private var stream: AudioEngine.OpenedStream? = null
    @Volatile private var encoder: OpusEncoder? = null
    @Volatile private var player: AudioPlayer? = null
    private var resolutionFailureLogged = false

    fun start() {
        if (!openChannel()) {
            logger.warning("Could not create the 24/7 Lofi audio channel")
            return
        }
        running = true
        executor.execute(::playLoop)
    }

    fun openChannel(): Boolean {
        val opened = api.createStaticAudioChannel(CHANNEL_ID) ?: return false
        opened.setBypassGroupIsolation(true)
        opened.setCategory(VoiceMediaRegistry.LOFI_CATEGORY)
        opened.setFilter { serverPlayer ->
            val connection = api.getConnectionOf(serverPlayer.getUuid())
            connection != null && connection.getGroup() != null && groupId == connection.getGroup()!!.getId()
        }
        channel = opened
        return true
    }

    fun reconcileTarget(connection: VoicechatConnection?) {
        if (connection == null) return
        val currentGroupId = connection.getGroup()?.getId()
        updateTarget(connection, currentGroupId)
    }

    fun updateTarget(connection: VoicechatConnection?, currentGroupId: UUID?) {
        if (connection == null) return
        val playerId = connection.getPlayer().getUuid()
        val currentChannel = channel ?: return
        if (groupId != currentGroupId) {
            removeTarget(playerId)
            return
        }
        val previous = targets.put(playerId, connection)
        if (previous != null && previous !== connection) currentChannel.removeTarget(previous)
        currentChannel.addTarget(connection)
    }

    fun removeTarget(playerId: UUID) {
        val connection = targets.remove(playerId)
        val currentChannel = channel
        if (connection != null && currentChannel != null) currentChannel.removeTarget(connection)
    }

    private fun playLoop() {
        var forceRefresh = false
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                if (!AudioEngine.getInstance().isReady()) {
                    waitBeforeRetry()
                    continue
                }
                val opened = AudioEngine.getInstance().openLofiStream(sourceUrl, musicVolume, forceRefresh)
                forceRefresh = true
                if (opened == null) {
                    if (!resolutionFailureLogged) {
                        logger.warning("Could not resolve the 24/7 Lofi stream; retrying every " + TimeUnit.SECONDS.toMinutes(RETRY_SECONDS) + " minutes")
                        resolutionFailureLogged = true
                    }
                    waitBeforeRetry()
                    continue
                }
                resolutionFailureLogged = false
                val stopped = CountDownLatch(1)
                val openedEncoder = api.createEncoder(OpusEncoderMode.AUDIO)
                val openedPlayer = api.createAudioPlayer(channel, openedEncoder, opened.pcm().frames())
                stream = opened
                encoder = openedEncoder
                player = openedPlayer
                openedPlayer.setOnStopped(stopped::countDown)
                if (!running) break
                logger.info("Playing 24/7 Lofi: " + opened.track().title())
                openedPlayer.startPlaying()
                while (running && !stopped.await(1L, TimeUnit.SECONDS)) {
                    // Wake periodically so shutdown never depends on a callback.
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (running) logger.warning("24/7 Lofi playback failed: " + e.message)
            } finally {
                closeCurrentPlayback()
            }
            if (running) waitBeforeRetry()
        }
    }

    private fun waitBeforeRetry() {
        try { TimeUnit.SECONDS.sleep(RETRY_SECONDS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun closeCurrentPlayback() {
        val currentPlayer = player
        val currentStream = stream
        val currentEncoder = encoder
        player = null
        stream = null
        encoder = null
        if (currentPlayer != null) try { currentPlayer.stopPlaying() } catch (_: Exception) {}
        if (currentStream != null) try { currentStream.close() } catch (_: Exception) {}
        if (currentEncoder != null) try { currentEncoder.close() } catch (_: Exception) {}
    }

    override fun close() {
        running = false
        closeCurrentPlayback()
        executor.shutdownNow()
        val currentChannel = channel
        channel = null
        targets.clear()
        if (currentChannel != null) {
            try { currentChannel.flush() } catch (_: Exception) {}
            try { currentChannel.clearTargets() } catch (_: Exception) {}
        }
    }

    companion object {
        @JvmField val RETRY_SECONDS = TimeUnit.MINUTES.toSeconds(5L)
        private val CHANNEL_ID = UUID.nameUUIDFromBytes("crabcraft:svc:lofi:stream".toByteArray(StandardCharsets.UTF_8))
    }
}
