package crabcraft.net.crabUtilities.media.audio

import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry
import crabcraft.net.crabUtilities.media.item.MediaItemCodec
import crabcraft.net.crabUtilities.media.util.RemoteMediaSecurity
import de.maxhenkel.voicechat.api.Position
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel
import de.maxhenkel.voicechat.api.opus.OpusEncoder
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.Jukebox
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class AudioEngine {
    private val plugin = MediaFeature.get()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val hornSessions = ConcurrentHashMap<UUID, HornSession>()
    private val startingSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val fillingHornCache = ConcurrentHashMap.newKeySet<String>()
    private val executor = createAudioExecutor()
    private val sessionLimiter = SessionLimiter(MAX_ACTIVE_AUDIO_SESSIONS)
    @Volatile private var binaries: BinaryProvisioner? = null
    @Volatile private var resolver: TrackResolver? = null
    @Volatile private var hornCache: HornAudioCache? = null
    @Volatile private var destinationPolicy: MediaDestinationPolicy? = null
    @Volatile private var policyProxy: MediaPolicyProxy? = null
    @Volatile private var lofiResolver: TrackResolver? = null
    @Volatile private var lofiDestinationPolicy: MediaDestinationPolicy? = null
    @Volatile private var lofiPolicyProxy: MediaPolicyProxy? = null

    private fun submitAudioWork(task: Runnable, description: String): Boolean {
        if (executeIfCapacity(executor, task)) return true
        MediaFeature.warn("Audio work queue full; rejected {}", description)
        return false
    }

    /** Locates/downloads yt-dlp and ffmpeg. Call off the main thread; safe to repeat. */
    @Synchronized
    fun provision() {
        if (binaries != null) return
        val b = BinaryProvisioner()
        val policy = MediaDestinationPolicy()
        val trustedLofiPolicy = MediaDestinationPolicy.forTrustedLofiConfiguration()
        var proxy: MediaPolicyProxy? = null
        var trustedLofiProxy: MediaPolicyProxy? = null
        try {
            proxy = MediaPolicyProxy(policy, plugin.getMediaConfig().getYtDlpProxy())
            trustedLofiProxy = MediaPolicyProxy(trustedLofiPolicy, plugin.getMediaConfig().getYtDlpProxy())
        } catch (e: IOException) {
            proxy?.close()
            trustedLofiProxy?.close()
            MediaFeature.error("Could not start the media destination policy: {}", e.message)
            return
        }
        destinationPolicy = policy
        policyProxy = proxy
        resolver = TrackResolver(b, policy, proxy)
        lofiDestinationPolicy = trustedLofiPolicy
        lofiPolicyProxy = trustedLofiProxy
        lofiResolver = TrackResolver(b, trustedLofiPolicy, trustedLofiProxy)
        hornCache = HornAudioCache(File(plugin.getDataFolder(), "horn-cache"))
        binaries = b
    }

    /** Applies reloadable network settings without changing the loopback proxy address. */
    @Synchronized
    fun reloadMediaPolicy(): Boolean {
        val currentProxy = policyProxy
        val currentLofiProxy = lofiPolicyProxy
        val currentBinaries = binaries
        if (currentProxy == null || currentLofiProxy == null || currentBinaries == null) return true
        val newPolicy = MediaDestinationPolicy()
        val newLofiPolicy = MediaDestinationPolicy.forTrustedLofiConfiguration()
        try {
            currentProxy.reconfigure(newPolicy, plugin.getMediaConfig().getYtDlpProxy())
            currentLofiProxy.reconfigure(newLofiPolicy, plugin.getMediaConfig().getYtDlpProxy())
        } catch (e: IOException) {
            MediaFeature.error("Could not reload the media destination policy: {}", e.message)
            return false
        }
        destinationPolicy = newPolicy
        resolver = TrackResolver(currentBinaries, newPolicy, currentProxy)
        lofiDestinationPolicy = newLofiPolicy
        lofiResolver = TrackResolver(currentBinaries, newLofiPolicy, currentLofiProxy)
        return true
    }

    fun isReady() = binaries?.isReady() == true && policyProxy != null && lofiPolicyProxy != null

    /** Opens a non-spatial stream; the caller owns the returned stream and must close it. */
    @Throws(IOException::class)
    fun openStream(identifier: String, volume: Float, forceRefresh: Boolean): OpenedStream? {
        val currentResolver = resolver
        val currentBinaries = binaries
        if (currentResolver == null || currentBinaries == null || !currentBinaries.isReady()) return null
        val track = currentResolver.resolve(identifier, forceRefresh) ?: return null
        return OpenedStream(
            track,
            FfmpegPcmStream(
                currentBinaries.getFfmpegPath()!!,
                track.streamUrl(),
                volume,
                destinationPolicy!!,
                policyProxy!!,
            ),
        )
    }

    /** Only the administrator-configured lofi source may resolve to a protected network. */
    @Throws(IOException::class)
    fun openLofiStream(identifier: String, volume: Float, forceRefresh: Boolean): OpenedStream? {
        val currentResolver = lofiResolver
        val currentBinaries = binaries
        val currentPolicy = lofiDestinationPolicy
        val currentProxy = lofiPolicyProxy
        if (
            currentResolver == null ||
                currentBinaries == null ||
                !currentBinaries.isReady() ||
                currentPolicy == null ||
                currentProxy == null
        )
            return null
        val track = currentResolver.resolve(identifier, forceRefresh) ?: return null
        return OpenedStream(
            track,
            FfmpegPcmStream(currentBinaries.getFfmpegPath()!!, track.streamUrl(), volume, currentPolicy, currentProxy),
        )
    }

    data class OpenedStream(val track: TrackResolver.ResolvedTrack, val pcm: FfmpegPcmStream) : AutoCloseable {
        fun track() = track

        fun pcm() = pcm

        override fun close() = pcm.close()
    }

    fun shutdown() {
        stopPlayingAll()
        executor.shutdownNow()
        val proxy = policyProxy
        policyProxy = null
        proxy?.close()
        val trustedLofiProxy = lofiPolicyProxy
        lofiPolicyProxy = null
        trustedLofiProxy?.close()
    }

    fun play(block: Block, identifier: String) = play(block, identifier, 1f, 0)

    fun play(block: Block, identifier: String, volume: Float, discDistance: Int) {
        if (!RemoteMediaSecurity.isValidVolume(volume)) {
            MediaFeature.warn("Rejected disc with invalid volume at {}", block.location)
            return
        }
        val uuid = MediaItemCodec.playbackId(block)
        if (!startingSessions.add(uuid)) return
        try {
            if (sessions.containsKey(uuid)) return
            if (!isReady()) {
                MediaFeature.warn("Cannot play {}: yt-dlp/ffmpeg unavailable", identifier)
                return
            }
            val api = VoiceMediaRegistry.getInstance().serverApi()
            val pos = api.createPosition(block.location.x + 0.5, block.location.y + 0.5, block.location.z + 0.5)
            val distance =
                RemoteMediaSecurity.playbackDistance(
                    discDistance,
                    plugin.getMediaConfig().getDiscRangeDefault(),
                    plugin.getMediaConfig().getDiscRangeMin(),
                    plugin.getMediaConfig().getDiscRangeMax(),
                )
            val playersAtStart = api.getPlayersInRange(api.fromServerLevel(block.world), pos, distance.toDouble())
            if (!sessionLimiter.tryAcquire()) {
                messagePlayers(playersAtStart, plugin.getMessages().prefixedComponent("error.play.busy"))
                MediaFeature.warn(
                    "Media session limit ({}) reached; rejected disc at {}",
                    MAX_ACTIVE_AUDIO_SESSIONS,
                    block.location,
                )
                return
            }
            var permitOwnedByCaller = true
            try {
                val channel =
                    api.createLocationalAudioChannel(UUID.randomUUID(), api.fromServerLevel(block.world), pos) ?: return
                channel.setCategory(VoiceMediaRegistry.MUSIC_DISC_CATEGORY)
                channel.setDistance(distance.toFloat())
                val session = Session(block, uuid, identifier, channel, playersAtStart, distance, pos, volume)
                if (sessions.putIfAbsent(uuid, session) != null) return
                permitOwnedByCaller = false
                if (!submitAudioWork(session::startAudio, "disc playback")) {
                    sessions.remove(uuid, session)
                    session.rejectBusy()
                    return
                }
                object : BukkitRunnable() {
                        override fun run() {
                            if (!sessions.containsKey(uuid)) {
                                cancel()
                                return
                            }
                            (block.state as? Jukebox)?.let {
                                it.stopPlaying()
                                it.startPlaying()
                            }
                        }
                    }
                    .runTaskTimer(plugin.getJavaPlugin(), 20L * 5L, 20L * 5L)
            } finally {
                if (permitOwnedByCaller) sessionLimiter.release()
            }
        } finally {
            startingSessions.remove(uuid)
        }
    }

    fun stopPlaying(block: Block) = stop(MediaItemCodec.playbackId(block))

    private fun stop(uuid: UUID) {
        sessions.remove(uuid)?.stop()
    }

    fun stopPlayingAll() {
        sessions.keys.toSet().forEach(::stop)
        hornSessions.keys.toSet().forEach(::stopHorn)
    }

    /** A fresh blow replaces the player's previous horn and follows their moving entity. */
    fun playHorn(player: Player, identifier: String, volume: Float) {
        if (!RemoteMediaSecurity.isValidVolume(volume)) {
            MediaFeature.warn("Rejected horn with invalid volume for {}", player.uniqueId)
            return
        }
        if (!isReady()) {
            MediaFeature.warn("Cannot play horn {}: yt-dlp/ffmpeg unavailable", identifier)
            return
        }
        val playerId = player.uniqueId
        hornSessions.remove(playerId)?.stop()
        if (!sessionLimiter.tryAcquire()) {
            MediaFeature.sendMessage(player, plugin.getMessages().prefixedComponent("error.play.busy"))
            MediaFeature.warn(
                "Media session limit ({}) reached; rejected horn for {}",
                MAX_ACTIVE_AUDIO_SESSIONS,
                playerId,
            )
            return
        }
        var permitOwnedByCaller = true
        try {
            val api = VoiceMediaRegistry.getInstance().serverApi()
            val channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(player)) ?: return
            channel.setCategory(VoiceMediaRegistry.GOAT_HORN_CATEGORY)
            channel.setDistance(plugin.getMediaConfig().getHornRange().toFloat())
            val session =
                HornSession(
                    playerId,
                    identifier,
                    channel,
                    plugin.getMediaConfig().getHornVolume() * volume,
                    plugin.getMediaConfig().getHornMaxLengthSeconds(),
                )
            hornSessions[playerId] = session
            permitOwnedByCaller = false
            if (!submitAudioWork(session::startAudio, "horn playback")) {
                hornSessions.remove(playerId, session)
                session.rejectBusy()
            }
        } finally {
            if (permitOwnedByCaller) sessionLimiter.release()
        }
    }

    fun stopHorn(playerId: UUID) {
        hornSessions.remove(playerId)?.stop()
    }

    /** Resolves and caches a new horn off-thread; reports track duration to the consumer. */
    fun prewarmHornAsync(identifier: String, effectiveVolume: Float, consumer: Consumer<TrackResolver.ResolvedTrack?>) {
        if (!isReady()) return
        val work = Runnable {
            var track: TrackResolver.ResolvedTrack? = null
            try {
                track = resolver!!.resolve(identifier)
                val cache = hornCacheIfEnabled()
                val key = hornCacheKey(identifier, effectiveVolume)
                if (cache != null && track != null && !cache.has(key))
                    cache.write(
                        key,
                        decodeFrames(
                            track.streamUrl(),
                            effectiveVolume,
                            plugin.getMediaConfig().getHornMaxLengthSeconds(),
                        ),
                    )
            } catch (t: Throwable) {
                MediaFeature.warn("Horn pre-warm failed for {}: {}", identifier, t.message)
            } finally {
                consumer.accept(track)
            }
        }
        if (!submitAudioWork(work, "horn pre-warm")) consumer.accept(null)
    }

    private fun hornCacheIfEnabled(): HornAudioCache? {
        val cache = hornCache
        if (cache == null || !plugin.getMediaConfig().isHornCacheEnabled()) return null
        cache.setMaxFiles(plugin.getMediaConfig().getHornCacheSize())
        return cache
    }

    @Throws(IOException::class)
    private fun decodeFrames(streamUrl: String, volume: Float, maxSeconds: Int): List<ShortArray> {
        val pcm =
            FfmpegPcmStream(
                binaries!!.getFfmpegPath()!!,
                streamUrl,
                volume,
                destinationPolicy!!,
                policyProxy!!,
                maxSeconds,
            )
        try {
            val supplier = pcm.frames()
            val limit = maxOf(1, maxSeconds) * 50 + 25
            val frames = ArrayList<ShortArray>()
            while (frames.size < limit) frames.add(supplier.get() ?: break)
            return frames
        } finally {
            pcm.close()
        }
    }

    private fun fillHornCacheAsync(identifier: String, effectiveVolume: Float) {
        val cache = hornCacheIfEnabled() ?: return
        val key = hornCacheKey(identifier, effectiveVolume)
        if (cache.has(key) || !fillingHornCache.add(key)) return
        val work = Runnable {
            try {
                val track = resolver!!.resolve(identifier)
                if (track != null)
                    cache.write(
                        key,
                        decodeFrames(
                            track.streamUrl(),
                            effectiveVolume,
                            plugin.getMediaConfig().getHornMaxLengthSeconds(),
                        ),
                    )
            } catch (t: Throwable) {
                MediaFeature.warn("Horn cache fill failed for {}: {}", identifier, t.message)
            } finally {
                fillingHornCache.remove(key)
            }
        }
        if (!submitAudioWork(work, "horn cache fill")) fillingHornCache.remove(key)
    }

    fun isPlaying(block: Block) = sessions.containsKey(MediaItemCodec.playbackId(block))

    private inner class Session(
        private val block: Block,
        private val uuid: UUID,
        private val identifier: String,
        private val channel: LocationalAudioChannel,
        private val playersAtStart: Collection<ServerPlayer>,
        private val distance: Int,
        private val pos: Position,
        private val volume: Float,
    ) {
        @Volatile private var stream: FfmpegPcmStream? = null
        @Volatile private var encoder: OpusEncoder? = null
        @Volatile private var player: AudioPlayer? = null
        @Volatile private var stopped = false

        fun startAudio() {
            if (stopped) return
            try {
                val track = resolver!!.resolve(identifier)
                if (track == null || stopped) {
                    if (!stopped) messageInRange(plugin.getMessages().prefixedComponent("error.play.no-matches"))
                    this@AudioEngine.stop(uuid)
                    return
                }
                val api = VoiceMediaRegistry.getInstance().serverApi()
                stream =
                    FfmpegPcmStream(
                        binaries!!.getFfmpegPath()!!,
                        track.streamUrl(),
                        plugin.getMediaConfig().getMusicDiscVolume() * volume,
                        destinationPolicy!!,
                        policyProxy!!,
                    )
                encoder = api.createEncoder(OpusEncoderMode.AUDIO)
                player = api.createAudioPlayer(channel, encoder, stream!!.frames())
                if (stopped) {
                    try {
                        player!!.stopPlaying()
                    } catch (_: Exception) {}
                    stream!!.close()
                    try {
                        encoder!!.close()
                    } catch (_: Exception) {}
                    return
                }
                player!!.setOnStopped { if (!stopped) this@AudioEngine.stop(uuid) }
                player!!.startPlaying()
                startNowPlayingLoop(track.title())
            } catch (e: Throwable) {
                MediaFeature.error("Audio session error for {}: ", e, identifier)
                if (!stopped) messageInRange(plugin.getMessages().prefixedComponent("error.play.while-playing"))
                this@AudioEngine.stop(uuid)
            }
        }

        private fun startNowPlayingLoop(title: String) {
            val nowPlaying = plugin.getMessages().component("now-playing", Component.text(title))
            val previouslyInRange = HashSet<UUID>()
            val showTicksRemaining = HashMap<UUID, Int>()
            object : BukkitRunnable() {
                    override fun run() {
                        if (stopped) {
                            cancel()
                            return
                        }
                        val api = VoiceMediaRegistry.getInstance().serverApi()
                        val currentlyInRange = HashSet<UUID>()
                        for (sp in api.getPlayersInRange(api.fromServerLevel(block.world), pos, distance.toDouble())) {
                            val bukkitPlayer = sp.player as Player
                            val playerId = bukkitPlayer.uniqueId
                            currentlyInRange.add(playerId)
                            if (!previouslyInRange.contains(playerId)) showTicksRemaining[playerId] = 5
                            if (showTicksRemaining.containsKey(playerId)) {
                                bukkitPlayer.sendActionBar(nowPlaying)
                                val remaining = showTicksRemaining[playerId]!! - 1
                                if (remaining <= 0) showTicksRemaining.remove(playerId)
                                else showTicksRemaining[playerId] = remaining
                            }
                        }
                        previouslyInRange.removeAll(currentlyInRange)
                        for (left in previouslyInRange) showTicksRemaining.remove(left)
                        previouslyInRange.clear()
                        previouslyInRange.addAll(currentlyInRange)
                    }
                }
                .runTaskTimer(plugin.getJavaPlugin(), 1L, 20L)
        }

        private fun messageInRange(message: Component) = messagePlayers(playersAtStart, message)

        fun rejectBusy() {
            if (!markStopped()) return
            messageInRange(plugin.getMessages().prefixedComponent("error.play.busy"))
        }

        @Synchronized
        private fun markStopped(): Boolean {
            if (stopped) return false
            stopped = true
            sessionLimiter.release()
            return true
        }

        fun stop() {
            if (!markStopped()) return
            try {
                player?.stopPlaying()
            } catch (_: Exception) {}
            stream?.close()
            try {
                encoder?.close()
            } catch (_: Exception) {}
            // Audio may finish asynchronously; only jukebox interaction returns to the server thread.
            Bukkit.getScheduler()
                .runTaskLater(plugin.getJavaPlugin(), Runnable { (block.state as? Jukebox)?.stopPlaying() }, 1L)
        }
    }

    /** Entity-following, capped horn playback with no jukebox timer or action bar. */
    private inner class HornSession(
        private val playerId: UUID,
        private val identifier: String,
        private val channel: EntityAudioChannel,
        private val volume: Float,
        private val maxSeconds: Int,
    ) {
        @Volatile private var stream: FfmpegPcmStream? = null
        @Volatile private var encoder: OpusEncoder? = null
        @Volatile private var player: AudioPlayer? = null
        @Volatile private var stopped = false

        fun startAudio() {
            if (stopped) return
            try {
                val api = VoiceMediaRegistry.getInstance().serverApi()
                val cached = hornCacheIfEnabled()?.read(hornCacheKey(identifier, volume))
                val frames: Supplier<ShortArray?>
                if (!cached.isNullOrEmpty()) frames = framesFrom(cached)
                else {
                    val track = resolver!!.resolve(identifier)
                    if (track == null || stopped) {
                        if (!stopped) messagePlayer(plugin.getMessages().prefixedComponent("error.play.no-matches"))
                        this@AudioEngine.stopHorn(playerId)
                        return
                    }
                    fillHornCacheAsync(identifier, volume)
                    stream =
                        FfmpegPcmStream(
                            binaries!!.getFfmpegPath()!!,
                            track.streamUrl(),
                            volume,
                            destinationPolicy!!,
                            policyProxy!!,
                            maxSeconds,
                        )
                    frames = stream!!.frames()
                }
                encoder = api.createEncoder(OpusEncoderMode.AUDIO)
                player = api.createAudioPlayer(channel, encoder, frames)
                if (stopped) {
                    try {
                        player!!.stopPlaying()
                    } catch (_: Exception) {}
                    stream?.close()
                    try {
                        encoder!!.close()
                    } catch (_: Exception) {}
                    return
                }
                player!!.setOnStopped { if (!stopped) this@AudioEngine.stopHorn(playerId) }
                player!!.startPlaying()
            } catch (e: Throwable) {
                MediaFeature.error("Horn audio session error for {}: ", e, identifier)
                if (!stopped) messagePlayer(plugin.getMessages().prefixedComponent("error.play.while-playing"))
                this@AudioEngine.stopHorn(playerId)
            }
        }

        private fun messagePlayer(message: Component) {
            plugin.getServer().getPlayer(playerId)?.let { MediaFeature.sendMessage(it, message) }
        }

        fun rejectBusy() {
            if (!markStopped()) return
            messagePlayer(plugin.getMessages().prefixedComponent("error.play.busy"))
        }

        @Synchronized
        private fun markStopped(): Boolean {
            if (stopped) return false
            stopped = true
            sessionLimiter.release()
            return true
        }

        fun stop() {
            if (!markStopped()) return
            try {
                player?.stopPlaying()
            } catch (_: Exception) {}
            stream?.close()
            try {
                encoder?.close()
            } catch (_: Exception) {}
        }
    }

    class SessionLimiter(maximumSessions: Int) {
        private val permits: Semaphore

        init {
            require(maximumSessions >= 1) { "maximumSessions must be positive" }
            permits = Semaphore(maximumSessions)
        }

        fun tryAcquire() = permits.tryAcquire()

        fun release() = permits.release()

        fun availablePermits() = permits.availablePermits()
    }

    companion object {
        private val AUDIO_START_THREADS = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors()))
        const val AUDIO_WORK_QUEUE_CAPACITY = 16
        const val MAX_ACTIVE_AUDIO_SESSIONS = 8
        private var instance: AudioEngine? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): AudioEngine {
            if (instance == null) instance = AudioEngine()
            return instance!!
        }

        private fun audioThreadFactory(): ThreadFactory {
            val nextThread = AtomicInteger(1)
            return ThreadFactory { r ->
                Thread(r, "CD-AudioEngine-" + nextThread.getAndIncrement()).apply { isDaemon = true }
            }
        }

        @JvmStatic
        fun createAudioExecutor() =
            ThreadPoolExecutor(
                AUDIO_START_THREADS,
                AUDIO_START_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(AUDIO_WORK_QUEUE_CAPACITY),
                audioThreadFactory(),
                ThreadPoolExecutor.AbortPolicy(),
            )

        @JvmStatic
        fun executeIfCapacity(executor: ExecutorService, task: Runnable): Boolean =
            try {
                executor.execute(task)
                true
            } catch (_: RejectedExecutionException) {
                false
            }

        private fun hornCacheKey(identifier: String, effectiveVolume: Float) =
            identifier + "|" + Math.round(effectiveVolume * 1000)

        private fun framesFrom(frames: List<ShortArray>): Supplier<ShortArray?> {
            var index = 0
            return Supplier { if (index < frames.size) frames[index++] else null }
        }

        private fun messagePlayers(players: Collection<ServerPlayer>, message: Component) {
            for (serverPlayer in players) (serverPlayer.player as? Player)?.let {
                MediaFeature.sendMessage(it, message)
            }
        }
    }
}
