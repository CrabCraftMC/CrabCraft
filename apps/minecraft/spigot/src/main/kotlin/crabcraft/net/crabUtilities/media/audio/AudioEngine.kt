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
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.Jukebox
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
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

class AudioEngine {
  private val plugin = MediaFeature.get()
  private val sessions = ConcurrentHashMap<UUID, Session>()
  private val hornSessions = ConcurrentHashMap<UUID, HornSession>() // keyed by player UUID
  private val startingSessions = ConcurrentHashMap.newKeySet<UUID>()
  private val fillingHornCache = ConcurrentHashMap.newKeySet<String>() // de-duplicates cache fills
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

  /** Locates/downloads yt-dlp and ffmpeg. Safe to repeat; call off the main thread. */
  @Synchronized fun provision() {
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

  /** Applies reloadable media-network settings without changing the loopback proxy address. */
  @Synchronized fun reloadMediaPolicy(): Boolean {
    val currentProxy = policyProxy ?: return true
    val currentLofiProxy = lofiPolicyProxy ?: return true
    val currentBinaries = binaries ?: return true
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

  fun isReady(): Boolean = binaries?.isReady() == true && policyProxy != null && lofiPolicyProxy != null

  /** Opens a non-spatial PCM stream. The caller owns and must close the returned stream. */
  @Throws(IOException::class)
  fun openStream(identifier: String, volume: Float, forceRefresh: Boolean): OpenedStream? {
    val currentResolver = resolver ?: return null
    val currentBinaries = binaries ?: return null
    if (!currentBinaries.isReady()) return null
    val track = currentResolver.resolve(identifier, forceRefresh) ?: return null
    val pcm = FfmpegPcmStream(currentBinaries.getFfmpegPath()!!, track.streamUrl(),
      volume, destinationPolicy!!, policyProxy!!)
    return OpenedStream(track, pcm)
  }

  /** Opens the administrator-configured lofi source, which may resolve to a protected network. */
  @Throws(IOException::class)
  fun openLofiStream(identifier: String, volume: Float, forceRefresh: Boolean): OpenedStream? {
    val currentResolver = lofiResolver ?: return null
    val currentBinaries = binaries ?: return null
    val currentPolicy = lofiDestinationPolicy ?: return null
    val currentProxy = lofiPolicyProxy ?: return null
    if (!currentBinaries.isReady()) return null
    val track = currentResolver.resolve(identifier, forceRefresh) ?: return null
    val pcm = FfmpegPcmStream(currentBinaries.getFfmpegPath()!!, track.streamUrl(), volume, currentPolicy, currentProxy)
    return OpenedStream(track, pcm)
  }

  data class OpenedStream(val track: TrackResolver.ResolvedTrack, val pcm: FfmpegPcmStream) : AutoCloseable {
    fun track() = track
    fun pcm() = pcm
    override fun close() { pcm.close() }
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

  fun play(block: Block, identifier: String) { play(block, identifier, 1.0f, 0) }

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
      // Older discs use the configured range; positive discDistance carries a disc-specific range.
      val distance = RemoteMediaSecurity.playbackDistance(discDistance,
        plugin.getMediaConfig().getDiscRangeDefault(), plugin.getMediaConfig().getDiscRangeMin(),
        plugin.getMediaConfig().getDiscRangeMax())
      val playersAtStart = api.getPlayersInRange(api.fromServerLevel(block.world), pos, distance.toDouble())
      if (!sessionLimiter.tryAcquire()) {
        messagePlayers(playersAtStart, plugin.getMessages().prefixedComponent("error.play.busy"))
        MediaFeature.warn("Media session limit ({}) reached; rejected disc at {}", MAX_ACTIVE_AUDIO_SESSIONS, block.location)
        return
      }
      var permitOwnedByCaller = true
      try {
        val channel = api.createLocationalAudioChannel(UUID.randomUUID(), api.fromServerLevel(block.world), pos) ?: return
        channel.setCategory(VoiceMediaRegistry.MUSIC_DISC_CATEGORY)
        channel.setDistance(distance.toFloat())
        val session = Session(block, uuid, identifier, channel, playersAtStart, distance, pos, volume)
        if (sessions.putIfAbsent(uuid, session) != null) return
        permitOwnedByCaller = false
        if (!submitAudioWork(Runnable(session::startAudio), "disc playback")) {
          sessions.remove(uuid, session)
          session.rejectBusy()
          return
        }
        object : BukkitRunnable() {
          override fun run() {
            if (!sessions.containsKey(uuid)) { cancel(); return }
            val jukebox = block.state as? Jukebox
            if (jukebox != null) { jukebox.stopPlaying(); jukebox.startPlaying() }
          }
        }.runTaskTimer(plugin.getJavaPlugin(), 20L * 5L, 20L * 5L)
      } finally {
        if (permitOwnedByCaller) sessionLimiter.release()
      }
    } finally { startingSessions.remove(uuid) }
  }

  fun stopPlaying(block: Block) { stop(MediaItemCodec.playbackId(block)) }
  private fun stop(uuid: UUID) { sessions.remove(uuid)?.stop() }

  fun stopPlayingAll() {
    sessions.keys.toSet().forEach(::stop)
    hornSessions.keys.toSet().forEach(::stopHorn)
  }

  /** A fresh goat-horn blow replaces any still-playing horn for that player. */
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
      MediaFeature.warn("Media session limit ({}) reached; rejected horn for {}", MAX_ACTIVE_AUDIO_SESSIONS, playerId)
      return
    }
    var permitOwnedByCaller = true
    try {
      val api = VoiceMediaRegistry.getInstance().serverApi()
      // Entity-bound sound follows the player, as a vanilla horn does.
      val channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(player)) ?: return
      channel.setCategory(VoiceMediaRegistry.GOAT_HORN_CATEGORY)
      channel.setDistance(plugin.getMediaConfig().getHornRange().toFloat())
      val session = HornSession(playerId, identifier, channel, plugin.getMediaConfig().getHornVolume() * volume,
        plugin.getMediaConfig().getHornMaxLengthSeconds())
      hornSessions[playerId] = session
      permitOwnedByCaller = false
      if (!submitAudioWork(Runnable(session::startAudio), "horn playback")) {
        hornSessions.remove(playerId, session)
        session.rejectBusy()
      }
    } finally { if (permitOwnedByCaller) sessionLimiter.release() }
  }

  fun stopHorn(playerId: UUID) { hornSessions.remove(playerId)?.stop() }

  /** Resolves and decodes the horn off-thread, then supplies the track for the over-length warning. */
  fun prewarmHornAsync(identifier: String, effectiveVolume: Float, consumer: Consumer<TrackResolver.ResolvedTrack?>) {
    if (!isReady()) return
    val work = Runnable {
      var track: TrackResolver.ResolvedTrack? = null
      try {
        track = resolver!!.resolve(identifier)
        val cache = hornCacheIfEnabled()
        val key = hornCacheKey(identifier, effectiveVolume)
        if (cache != null && track != null && !cache.has(key)) {
          cache.write(key, decodeFrames(track.streamUrl(), effectiveVolume, plugin.getMediaConfig().getHornMaxLengthSeconds()))
        }
      } catch (t: Throwable) {
        MediaFeature.warn("Horn pre-warm failed for {}: {}", identifier, t.message)
      } finally { consumer.accept(track) }
    }
    if (!submitAudioWork(work, "horn pre-warm")) consumer.accept(null)
  }

  private fun hornCacheIfEnabled(): HornAudioCache? {
    val cache = hornCache ?: return null
    if (!plugin.getMediaConfig().isHornCacheEnabled()) return null
    cache.setMaxFiles(plugin.getMediaConfig().getHornCacheSize())
    return cache
  }

  @Throws(IOException::class)
  private fun decodeFrames(streamUrl: String, volume: Float, maxSeconds: Int): List<ShortArray> {
    val pcm = FfmpegPcmStream(binaries!!.getFfmpegPath()!!, streamUrl, volume, destinationPolicy!!, policyProxy!!, maxSeconds)
    try {
      val supplier = pcm.frames()
      val limit = Math.max(1, maxSeconds) * 50 + 25 // 50 frames/s, with a little headroom
      val frames = ArrayList<ShortArray>()
      while (frames.size < limit) frames.add(supplier.get() ?: break)
      return frames
    } finally { pcm.close() }
  }

  /** After a cache miss, decodes and caches the clip in the background for the next blow. */
  private fun fillHornCacheAsync(identifier: String, effectiveVolume: Float) {
    val cache = hornCacheIfEnabled() ?: return
    val key = hornCacheKey(identifier, effectiveVolume)
    if (cache.has(key) || !fillingHornCache.add(key)) return
    val work = Runnable {
      try {
        val track = resolver!!.resolve(identifier)
        if (track != null) cache.write(key,
          decodeFrames(track.streamUrl(), effectiveVolume, plugin.getMediaConfig().getHornMaxLengthSeconds()))
      } catch (t: Throwable) {
        MediaFeature.warn("Horn cache fill failed for {}: {}", identifier, t.message)
      } finally { fillingHornCache.remove(key) }
    }
    if (!submitAudioWork(work, "horn cache fill")) fillingHornCache.remove(key)
  }

  fun isPlaying(block: Block): Boolean = sessions.containsKey(MediaItemCodec.playbackId(block))

  private inner class Session(
    private val block: Block,
    private val uuid: UUID,
    private val identifier: String,
    private val channel: LocationalAudioChannel,
    private val playersAtStart: Collection<ServerPlayer>,
    private val distance: Int,
    private val pos: Position,
    private val volume: Float
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
        val newStream = FfmpegPcmStream(binaries!!.getFfmpegPath()!!, track.streamUrl(),
          plugin.getMediaConfig().getMusicDiscVolume() * volume, destinationPolicy!!, policyProxy!!)
        stream = newStream
        val newEncoder = api.createEncoder(OpusEncoderMode.AUDIO)
        encoder = newEncoder
        val newPlayer = api.createAudioPlayer(channel, newEncoder, newStream.frames())
        player = newPlayer
        if (stopped) {
          // stop() ran while resolving/spawning; tear down the newly created resources.
          try { newPlayer.stopPlaying() } catch (_: Exception) {}
          newStream.close()
          try { newEncoder.close() } catch (_: Exception) {}
          return
        }
        newPlayer.setOnStopped { if (!stopped) this@AudioEngine.stop(uuid) }
        newPlayer.startPlaying()
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
          if (stopped) { cancel(); return }
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
              if (remaining <= 0) showTicksRemaining.remove(playerId) else showTicksRemaining[playerId] = remaining
            }
          }
          previouslyInRange.removeAll(currentlyInRange)
          for (left in previouslyInRange) showTicksRemaining.remove(left)
          previouslyInRange.clear()
          previouslyInRange.addAll(currentlyInRange)
        }
      }.runTaskTimer(plugin.getJavaPlugin(), 1L, 20L)
    }

    private fun messageInRange(message: Component) { messagePlayers(playersAtStart, message) }

    fun rejectBusy() {
      if (!markStopped()) return
      messageInRange(plugin.getMessages().prefixedComponent("error.play.busy"))
    }

    @Synchronized private fun markStopped(): Boolean {
      if (stopped) return false
      stopped = true
      sessionLimiter.release()
      return true
    }

    fun stop() {
      if (!markStopped()) return
      // Release audio resources on the calling thread, which may be SVC's async callback.
      try { player?.stopPlaying() } catch (_: Exception) {}
      stream?.close()
      try { encoder?.close() } catch (_: Exception) {}
      // Return to the server thread for the Bukkit block operation.
      Bukkit.getScheduler().runTaskLater(plugin.getJavaPlugin(), Runnable {
        (block.state as? Jukebox)?.stopPlaying()
      }, 1L)
    }
  }

  /** A capped goat-horn playback with no block coupling or now-playing action bar. */
  private inner class HornSession(
    private val playerId: UUID,
    private val identifier: String,
    private val channel: EntityAudioChannel,
    private val volume: Float,
    private val maxSeconds: Int
  ) {
    @Volatile private var stream: FfmpegPcmStream? = null
    @Volatile private var encoder: OpusEncoder? = null
    @Volatile private var player: AudioPlayer? = null
    @Volatile private var stopped = false

    fun startAudio() {
      if (stopped) return
      try {
        val api = VoiceMediaRegistry.getInstance().serverApi()
        // Cache hits avoid yt-dlp, ffmpeg and network access.
        val cached = hornCacheIfEnabled()?.read(hornCacheKey(identifier, volume))
        if (!cached.isNullOrEmpty()) {
          val newEncoder = api.createEncoder(OpusEncoderMode.AUDIO)
          encoder = newEncoder
          val newPlayer = api.createAudioPlayer(channel, newEncoder, framesFrom(cached))
          player = newPlayer
          if (stopped) {
            try { newPlayer.stopPlaying() } catch (_: Exception) {}
            try { newEncoder.close() } catch (_: Exception) {}
            return
          }
          newPlayer.setOnStopped { if (!stopped) this@AudioEngine.stopHorn(playerId) }
          newPlayer.startPlaying()
          return
        }
        // Stream a cache miss and fill the cache in the background for next time.
        val track = resolver!!.resolve(identifier)
        if (track == null || stopped) {
          if (!stopped) messagePlayer(plugin.getMessages().prefixedComponent("error.play.no-matches"))
          this@AudioEngine.stopHorn(playerId)
          return
        }
        fillHornCacheAsync(identifier, volume)
        val newStream = FfmpegPcmStream(binaries!!.getFfmpegPath()!!, track.streamUrl(), volume,
          destinationPolicy!!, policyProxy!!, maxSeconds)
        stream = newStream
        val newEncoder = api.createEncoder(OpusEncoderMode.AUDIO)
        encoder = newEncoder
        val newPlayer = api.createAudioPlayer(channel, newEncoder, newStream.frames())
        player = newPlayer
        if (stopped) {
          try { newPlayer.stopPlaying() } catch (_: Exception) {}
          newStream.close()
          try { newEncoder.close() } catch (_: Exception) {}
          return
        }
        newPlayer.setOnStopped { if (!stopped) this@AudioEngine.stopHorn(playerId) }
        newPlayer.startPlaying()
      } catch (e: Throwable) {
        MediaFeature.error("Horn audio session error for {}: ", e, identifier)
        if (!stopped) messagePlayer(plugin.getMessages().prefixedComponent("error.play.while-playing"))
        this@AudioEngine.stopHorn(playerId)
      }
    }

    private fun messagePlayer(message: Component) {
      val bukkitPlayer = plugin.getServer().getPlayer(playerId)
      if (bukkitPlayer != null) MediaFeature.sendMessage(bukkitPlayer, message)
    }

    fun rejectBusy() {
      if (!markStopped()) return
      messagePlayer(plugin.getMessages().prefixedComponent("error.play.busy"))
    }

    @Synchronized private fun markStopped(): Boolean {
      if (stopped) return false
      stopped = true
      sessionLimiter.release()
      return true
    }

    fun stop() {
      if (!markStopped()) return
      try { player?.stopPlaying() } catch (_: Exception) {}
      stream?.close()
      try { encoder?.close() } catch (_: Exception) {}
    }
  }

  class SessionLimiter(maximumSessions: Int) {
    private val permits: Semaphore
    init {
      require(maximumSessions >= 1) { "maximumSessions must be positive" }
      permits = Semaphore(maximumSessions)
    }
    fun tryAcquire(): Boolean = permits.tryAcquire()
    fun release() { permits.release() }
    fun availablePermits(): Int = permits.availablePermits()
  }

  companion object {
    private val AUDIO_START_THREADS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()))
    const val AUDIO_WORK_QUEUE_CAPACITY = 16
    const val MAX_ACTIVE_AUDIO_SESSIONS = 8
    private var instance: AudioEngine? = null

    private fun audioThreadFactory(): ThreadFactory {
      val nextThread = AtomicInteger(1)
      return ThreadFactory { r ->
        Thread(r, "CD-AudioEngine-" + nextThread.getAndIncrement()).apply { isDaemon = true }
      }
    }

    @JvmStatic fun createAudioExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
      AUDIO_START_THREADS, AUDIO_START_THREADS, 0L, TimeUnit.MILLISECONDS,
      ArrayBlockingQueue(AUDIO_WORK_QUEUE_CAPACITY), audioThreadFactory(), ThreadPoolExecutor.AbortPolicy())

    @JvmStatic fun executeIfCapacity(executor: ExecutorService, task: Runnable): Boolean {
      return try { executor.execute(task); true } catch (_: RejectedExecutionException) { false }
    }

    @JvmStatic @Synchronized fun getInstance(): AudioEngine {
      if (instance == null) instance = AudioEngine()
      return instance!!
    }

    private fun hornCacheKey(identifier: String, effectiveVolume: Float): String =
      identifier + "|" + Math.round(effectiveVolume * 1000)

    private fun framesFrom(frames: List<ShortArray>): Supplier<ShortArray?> {
      var index = 0
      return Supplier { if (index < frames.size) frames[index++] else null }
    }

    private fun messagePlayers(players: Collection<ServerPlayer>, message: Component) {
      for (serverPlayer in players) {
        val bukkitPlayer = serverPlayer.player as? Player
        if (bukkitPlayer != null) MediaFeature.sendMessage(bukkitPlayer, message)
      }
    }
  }
}
