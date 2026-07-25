package crabcraft.net.crabUtilities.media.audio;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry;
import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.item.MediaItemCodec;
import crabcraft.net.crabUtilities.media.util.RemoteMediaSecurity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AudioEngine {
  private static final int AUDIO_START_THREADS = Math.max(2, Math.min(4,
    Runtime.getRuntime().availableProcessors()));
  static final int AUDIO_WORK_QUEUE_CAPACITY = 16;
  static final int MAX_ACTIVE_AUDIO_SESSIONS = 8;
  private static AudioEngine instance;

  private final MediaFeature plugin = MediaFeature.get();
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, HornSession> hornSessions = new ConcurrentHashMap<>(); // keyed by player UUID
  private final Set<UUID> startingSessions = ConcurrentHashMap.newKeySet();
  private final Set<String> fillingHornCache = ConcurrentHashMap.newKeySet(); // de-dupes background cache fills
  private final ThreadPoolExecutor executor = createAudioExecutor();
  private final SessionLimiter sessionLimiter = new SessionLimiter(MAX_ACTIVE_AUDIO_SESSIONS);
  private volatile BinaryProvisioner binaries;
  private volatile TrackResolver resolver;
  private volatile HornAudioCache hornCache;
  private volatile MediaDestinationPolicy destinationPolicy;
  private volatile MediaPolicyProxy policyProxy;
  private volatile TrackResolver lofiResolver;
  private volatile MediaDestinationPolicy lofiDestinationPolicy;
  private volatile MediaPolicyProxy lofiPolicyProxy;

  private static ThreadFactory audioThreadFactory() {
    AtomicInteger nextThread = new AtomicInteger(1);
    return r -> {
      Thread t = new Thread(r, "CD-AudioEngine-" + nextThread.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
  }

  static ThreadPoolExecutor createAudioExecutor() {
    return new ThreadPoolExecutor(
      AUDIO_START_THREADS,
      AUDIO_START_THREADS,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(AUDIO_WORK_QUEUE_CAPACITY),
      audioThreadFactory(),
      new ThreadPoolExecutor.AbortPolicy()
    );
  }

  static boolean executeIfCapacity(ExecutorService executor, Runnable task) {
    try {
      executor.execute(task);
      return true;
    } catch (RejectedExecutionException ignored) {
      return false;
    }
  }

  private boolean submitAudioWork(Runnable task, String description) {
    if (executeIfCapacity(executor, task)) return true;
    MediaFeature.warn("Audio work queue full; rejected {}", description);
    return false;
  }

  public static synchronized AudioEngine getInstance() {
    if (instance == null) instance = new AudioEngine();
    return instance;
  }

  /** Locates/downloads yt-dlp + ffmpeg. Safe to call multiple times; heavy, call off the main thread. */
  public synchronized void provision() {
    if (binaries != null) return;
    BinaryProvisioner b = new BinaryProvisioner();
    MediaDestinationPolicy policy = new MediaDestinationPolicy();
    MediaDestinationPolicy trustedLofiPolicy =
      MediaDestinationPolicy.forTrustedLofiConfiguration();
    MediaPolicyProxy proxy = null;
    MediaPolicyProxy trustedLofiProxy = null;
    try {
      proxy = new MediaPolicyProxy(policy, plugin.getMediaConfig().getYtDlpProxy());
      trustedLofiProxy = new MediaPolicyProxy(
        trustedLofiPolicy, plugin.getMediaConfig().getYtDlpProxy());
    } catch (IOException e) {
      if (proxy != null) proxy.close();
      if (trustedLofiProxy != null) trustedLofiProxy.close();
      MediaFeature.error("Could not start the media destination policy: {}", e.getMessage());
      return;
    }
    this.destinationPolicy = policy;
    this.policyProxy = proxy;
    this.resolver = new TrackResolver(b, policy, proxy);
    this.lofiDestinationPolicy = trustedLofiPolicy;
    this.lofiPolicyProxy = trustedLofiProxy;
    this.lofiResolver = new TrackResolver(b, trustedLofiPolicy, trustedLofiProxy);
    this.hornCache = new HornAudioCache(new File(plugin.getDataFolder(), "horn-cache"));
    this.binaries = b;
  }

  /** Applies reloadable media-network settings without changing the loopback proxy address. */
  public synchronized boolean reloadMediaPolicy() {
    MediaPolicyProxy currentProxy = policyProxy;
    MediaPolicyProxy currentLofiProxy = lofiPolicyProxy;
    BinaryProvisioner currentBinaries = binaries;
    if (currentProxy == null || currentLofiProxy == null || currentBinaries == null) return true;

    MediaDestinationPolicy newPolicy = new MediaDestinationPolicy();
    MediaDestinationPolicy newLofiPolicy =
      MediaDestinationPolicy.forTrustedLofiConfiguration();
    try {
      currentProxy.reconfigure(newPolicy, plugin.getMediaConfig().getYtDlpProxy());
      currentLofiProxy.reconfigure(newLofiPolicy, plugin.getMediaConfig().getYtDlpProxy());
    } catch (IOException e) {
      MediaFeature.error("Could not reload the media destination policy: {}", e.getMessage());
      return false;
    }
    destinationPolicy = newPolicy;
    resolver = new TrackResolver(currentBinaries, newPolicy, currentProxy);
    lofiDestinationPolicy = newLofiPolicy;
    lofiResolver = new TrackResolver(currentBinaries, newLofiPolicy, currentLofiProxy);
    return true;
  }

  public boolean isReady() {
    return binaries != null && binaries.isReady() && policyProxy != null && lofiPolicyProxy != null;
  }

  /**
   * Opens a non-spatial PCM stream using the same resolver, proxy and binaries
   * as media discs. The caller owns and must close the returned stream.
   */
  public OpenedStream openStream(String identifier, float volume, boolean forceRefresh) throws IOException {
    TrackResolver currentResolver = resolver;
    BinaryProvisioner currentBinaries = binaries;
    if (currentResolver == null || currentBinaries == null || !currentBinaries.isReady()) return null;
    TrackResolver.ResolvedTrack track = currentResolver.resolve(identifier, forceRefresh);
    if (track == null) return null;
    FfmpegPcmStream pcm = new FfmpegPcmStream(currentBinaries.getFfmpegPath(), track.streamUrl(),
      volume, destinationPolicy, policyProxy);
    return new OpenedStream(track, pcm);
  }

  /**
   * Opens the administrator-configured lofi stream. Unlike player-provided media, this source may
   * resolve to a protected network because only {@code voicechat.lofi.youtube-url} can reach it.
   */
  public OpenedStream openLofiStream(String identifier, float volume, boolean forceRefresh)
      throws IOException {
    TrackResolver currentResolver = lofiResolver;
    BinaryProvisioner currentBinaries = binaries;
    MediaDestinationPolicy currentPolicy = lofiDestinationPolicy;
    MediaPolicyProxy currentProxy = lofiPolicyProxy;
    if (currentResolver == null || currentBinaries == null || !currentBinaries.isReady()
        || currentPolicy == null || currentProxy == null) {
      return null;
    }
    TrackResolver.ResolvedTrack track = currentResolver.resolve(identifier, forceRefresh);
    if (track == null) return null;
    FfmpegPcmStream pcm = new FfmpegPcmStream(currentBinaries.getFfmpegPath(), track.streamUrl(),
      volume, currentPolicy, currentProxy);
    return new OpenedStream(track, pcm);
  }

  public record OpenedStream(TrackResolver.ResolvedTrack track, FfmpegPcmStream pcm)
    implements AutoCloseable {
    @Override public void close() { pcm.close(); }
  }

  public void shutdown() {
    stopPlayingAll();
    executor.shutdownNow();
    MediaPolicyProxy proxy = policyProxy;
    policyProxy = null;
    if (proxy != null) proxy.close();
    MediaPolicyProxy trustedLofiProxy = lofiPolicyProxy;
    lofiPolicyProxy = null;
    if (trustedLofiProxy != null) trustedLofiProxy.close();
  }

  public void play(@NotNull Block block, @NotNull String identifier) {
    play(block, identifier, 1.0f, 0);
  }

  public void play(@NotNull Block block, @NotNull String identifier, float volume, int discDistance) {
    if (!RemoteMediaSecurity.isValidVolume(volume)) {
      MediaFeature.warn("Rejected disc with invalid volume at {}", block.getLocation());
      return;
    }
    UUID uuid = MediaItemCodec.playbackId(block);
    if (!startingSessions.add(uuid)) return;
    try {
      if (sessions.containsKey(uuid)) return;
      if (!isReady()) {
        MediaFeature.warn("Cannot play {}: yt-dlp/ffmpeg unavailable", identifier);
        return;
      }

      VoicechatServerApi api = VoiceMediaRegistry.getInstance().serverApi();
      Position pos = api.createPosition(
        block.getLocation().getX() + 0.5d,
        block.getLocation().getY() + 0.5d,
        block.getLocation().getZ() + 0.5d);
      // discDistance > 0 means the disc carries its own range; older discs use the configured default.
      int distance = RemoteMediaSecurity.playbackDistance(
        discDistance,
        plugin.getMediaConfig().getDiscRangeDefault(),
        plugin.getMediaConfig().getDiscRangeMin(),
        plugin.getMediaConfig().getDiscRangeMax());
      Collection<ServerPlayer> playersAtStart = api.getPlayersInRange(
        api.fromServerLevel(block.getWorld()), pos, distance);
      if (!sessionLimiter.tryAcquire()) {
        messagePlayers(playersAtStart, plugin.getMessages().prefixedComponent("error.play.busy"));
        MediaFeature.warn("Media session limit ({}) reached; rejected disc at {}",
          MAX_ACTIVE_AUDIO_SESSIONS, block.getLocation());
        return;
      }

      boolean permitOwnedByCaller = true;
      try {
        LocationalAudioChannel channel = api.createLocationalAudioChannel(
          UUID.randomUUID(), api.fromServerLevel(block.getWorld()), pos);
        if (channel == null) return;
        channel.setCategory(VoiceMediaRegistry.MUSIC_DISC_CATEGORY);
        channel.setDistance(distance);

        Session session = new Session(block, uuid, identifier, channel, playersAtStart, distance, pos, volume);
        if (sessions.putIfAbsent(uuid, session) != null) return;
        permitOwnedByCaller = false;
        if (!submitAudioWork(session::startAudio, "disc playback")) {
          sessions.remove(uuid, session);
          session.rejectBusy();
          return;
        }

        new BukkitRunnable() {
          @Override
          public void run() {
            if (!sessions.containsKey(uuid)) {
              cancel();
              return;
            }
            if (block.getState() instanceof Jukebox jukebox) {
              jukebox.stopPlaying();
              jukebox.startPlaying();
            }
          }
        }.runTaskTimer(plugin.getJavaPlugin(), 20L * 5L, 20L * 5L);
      } finally {
        if (permitOwnedByCaller) sessionLimiter.release();
      }
    } finally {
      startingSessions.remove(uuid);
    }
  }

  public void stopPlaying(@NotNull Block block) {
    stop(MediaItemCodec.playbackId(block));
  }

  private void stop(UUID uuid) {
    Session session = sessions.remove(uuid);
    if (session != null) session.stop();
  }

  public void stopPlayingAll() {
    Set.copyOf(sessions.keySet()).forEach(this::stop);
    Set.copyOf(hornSessions.keySet()).forEach(this::stopHorn);
  }

  /**
   * Plays a custom goat-horn sound at the player's location. Unlike discs this is not tied to a
   * block: it is keyed by player UUID, capped to {@code horn.max-length-seconds}, and a fresh blow
   * replaces any still-playing horn for that player.
   */
  public void playHorn(@NotNull Player player, @NotNull String identifier, float volume) {
    if (!RemoteMediaSecurity.isValidVolume(volume)) {
      MediaFeature.warn("Rejected horn with invalid volume for {}", player.getUniqueId());
      return;
    }
    if (!isReady()) {
      MediaFeature.warn("Cannot play horn {}: yt-dlp/ffmpeg unavailable", identifier);
      return;
    }
    UUID playerId = player.getUniqueId();
    HornSession previous = hornSessions.remove(playerId);
    if (previous != null) previous.stop();
    if (!sessionLimiter.tryAcquire()) {
      MediaFeature.sendMessage(player, plugin.getMessages().prefixedComponent("error.play.busy"));
      MediaFeature.warn("Media session limit ({}) reached; rejected horn for {}",
        MAX_ACTIVE_AUDIO_SESSIONS, playerId);
      return;
    }

    boolean permitOwnedByCaller = true;
    try {
      VoicechatServerApi api = VoiceMediaRegistry.getInstance().serverApi();
      // Entity-bound channel so the sound follows the player as they walk/fly away, like vanilla
      // (the vanilla horn is an entity-tracked sound, not one pinned to the blow location).
      EntityAudioChannel channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(player));
      if (channel == null) return;
      channel.setCategory(VoiceMediaRegistry.GOAT_HORN_CATEGORY);
      channel.setDistance(plugin.getMediaConfig().getHornRange()); // fixed range for every horn

      HornSession session = new HornSession(playerId, identifier, channel,
        plugin.getMediaConfig().getHornVolume() * volume, plugin.getMediaConfig().getHornMaxLengthSeconds());
      hornSessions.put(playerId, session);
      permitOwnedByCaller = false;
      if (!submitAudioWork(session::startAudio, "horn playback")) {
        hornSessions.remove(playerId, session);
        session.rejectBusy();
      }
    } finally {
      if (permitOwnedByCaller) sessionLimiter.release();
    }
  }

  public void stopHorn(@NotNull UUID playerId) {
    HornSession session = hornSessions.remove(playerId);
    if (session != null) session.stop();
  }

  /**
   * Off-thread, at horn creation: resolves the track, decodes the first {@code horn.max-length-seconds}
   * to the on-disk cache (so the first blow plays instantly), then hands the resolved track (or
   * {@code null}) to {@code consumer} for the over-length warning.
   */
  public void prewarmHornAsync(@NotNull String identifier, float effectiveVolume,
                               @NotNull java.util.function.Consumer<TrackResolver.ResolvedTrack> consumer) {
    if (!isReady()) return;
    Runnable work = () -> {
      TrackResolver.ResolvedTrack track = null;
      try {
        track = resolver.resolve(identifier);
        HornAudioCache cache = hornCacheIfEnabled();
        String key = hornCacheKey(identifier, effectiveVolume);
        if (cache != null && track != null && !cache.has(key)) {
          cache.write(key, decodeFrames(track.streamUrl(), effectiveVolume,
            plugin.getMediaConfig().getHornMaxLengthSeconds()));
        }
      } catch (Throwable t) {
        MediaFeature.warn("Horn pre-warm failed for {}: {}", identifier, t.getMessage());
      } finally {
        consumer.accept(track);
      }
    };
    if (!submitAudioWork(work, "horn pre-warm")) consumer.accept(null);
  }

  /** Returns the cache if enabled and ready (applying the configured size), else {@code null}. */
  private HornAudioCache hornCacheIfEnabled() {
    HornAudioCache cache = hornCache;
    if (cache == null || !plugin.getMediaConfig().isHornCacheEnabled()) return null;
    cache.setMaxFiles(plugin.getMediaConfig().getHornCacheSize());
    return cache;
  }

  private static String hornCacheKey(String identifier, float effectiveVolume) {
    return identifier + "|" + Math.round(effectiveVolume * 1000);
  }

  /** Decodes the first {@code maxSeconds} of a stream to 960-sample PCM frames (blocking). */
  private List<short[]> decodeFrames(String streamUrl, float volume, int maxSeconds) throws java.io.IOException {
    FfmpegPcmStream pcm = new FfmpegPcmStream(binaries.getFfmpegPath(), streamUrl, volume,
      destinationPolicy, policyProxy, maxSeconds);
    try {
      java.util.function.Supplier<short[]> supplier = pcm.frames();
      int limit = Math.max(1, maxSeconds) * 50 + 25; // 50 frames/s, with a little headroom
      List<short[]> frames = new ArrayList<>();
      short[] frame;
      while (frames.size() < limit && (frame = supplier.get()) != null) frames.add(frame);
      return frames;
    } finally {
      pcm.close();
    }
  }

  /** A fresh frame supplier over an already-decoded clip (cache hit), null-terminated at the end. */
  private static java.util.function.Supplier<short[]> framesFrom(List<short[]> frames) {
    int[] index = {0};
    return () -> index[0] < frames.size() ? frames.get(index[0]++) : null;
  }

  /** After a cache miss, decodes + caches the clip in the background so the next blow is a hit. */
  private void fillHornCacheAsync(String identifier, float effectiveVolume) {
    HornAudioCache cache = hornCacheIfEnabled();
    if (cache == null) return;
    String key = hornCacheKey(identifier, effectiveVolume);
    if (cache.has(key) || !fillingHornCache.add(key)) return;
    Runnable work = () -> {
      try {
        TrackResolver.ResolvedTrack track = resolver.resolve(identifier);
        if (track != null) {
          cache.write(key, decodeFrames(track.streamUrl(), effectiveVolume,
            plugin.getMediaConfig().getHornMaxLengthSeconds()));
        }
      } catch (Throwable t) {
        MediaFeature.warn("Horn cache fill failed for {}: {}", identifier, t.getMessage());
      } finally {
        fillingHornCache.remove(key);
      }
    };
    if (!submitAudioWork(work, "horn cache fill")) fillingHornCache.remove(key);
  }

  public boolean isPlaying(@NotNull Block block) {
    return sessions.containsKey(MediaItemCodec.playbackId(block));
  }

  private static void messagePlayers(Collection<ServerPlayer> players, Component message) {
    for (ServerPlayer serverPlayer : players) {
      if (serverPlayer.getPlayer() instanceof Player bukkitPlayer) {
        MediaFeature.sendMessage(bukkitPlayer, message);
      }
    }
  }

  private class Session {
    private final Block block;
    private final UUID uuid;
    private final String identifier;
    private final LocationalAudioChannel channel;
    private final Collection<ServerPlayer> playersAtStart;
    private final int distance;
    private final Position pos;
    private final float volume;

    private volatile FfmpegPcmStream stream;
    private volatile OpusEncoder encoder;
    private volatile AudioPlayer player;
    private volatile boolean stopped = false;

    Session(Block block, UUID uuid, String identifier, LocationalAudioChannel channel,
            Collection<ServerPlayer> playersAtStart, int distance, Position pos, float volume) {
      this.block = block;
      this.uuid = uuid;
      this.identifier = identifier;
      this.channel = channel;
      this.playersAtStart = playersAtStart;
      this.distance = distance;
      this.pos = pos;
      this.volume = volume;
    }

    void startAudio() {
      if (stopped) return;
      try {
        TrackResolver.ResolvedTrack track = resolver.resolve(identifier);
        if (track == null || stopped) {
          if (!stopped) messageInRange(plugin.getMessages().prefixedComponent("error.play.no-matches"));
          AudioEngine.this.stop(uuid);
          return;
        }

        VoicechatServerApi api = VoiceMediaRegistry.getInstance().serverApi();
        this.stream = new FfmpegPcmStream(binaries.getFfmpegPath(), track.streamUrl(),
          plugin.getMediaConfig().getMusicDiscVolume() * volume, destinationPolicy, policyProxy);
        this.encoder = api.createEncoder(OpusEncoderMode.AUDIO);
        this.player = api.createAudioPlayer(channel, encoder, stream.frames());
        if (stopped) {
          // stop() ran while we were resolving/spawning, so tear down what we just made.
          try { player.stopPlaying(); } catch (Exception ignored) {}
          stream.close();
          try { encoder.close(); } catch (Exception ignored) {}
          return;
        }
        this.player.setOnStopped(() -> { if (!stopped) AudioEngine.this.stop(uuid); });
        this.player.startPlaying();

        startNowPlayingLoop(track.title());
      } catch (Throwable e) {
        MediaFeature.error("Audio session error for {}: ", e, identifier);
        if (!stopped) messageInRange(plugin.getMessages().prefixedComponent("error.play.while-playing"));
        AudioEngine.this.stop(uuid);
      }
    }

    private void startNowPlayingLoop(String title) {
      Component nowPlaying = plugin.getMessages().component("now-playing", Component.text(title));
      Set<UUID> previouslyInRange = new HashSet<>();
      Map<UUID, Integer> showTicksRemaining = new HashMap<>();
      new BukkitRunnable() {
        @Override
        public void run() {
          if (stopped) {
            cancel();
            return;
          }
          VoicechatServerApi api = VoiceMediaRegistry.getInstance().serverApi();
          Set<UUID> currentlyInRange = new HashSet<>();
          for (ServerPlayer sp : api.getPlayersInRange(api.fromServerLevel(block.getWorld()), pos, distance)) {
            UUID playerId = ((Player) sp.getPlayer()).getUniqueId();
            currentlyInRange.add(playerId);
            if (!previouslyInRange.contains(playerId)) showTicksRemaining.put(playerId, 5);
            if (showTicksRemaining.containsKey(playerId)) {
              ((Player) sp.getPlayer()).sendActionBar(nowPlaying);
              int remaining = showTicksRemaining.get(playerId) - 1;
              if (remaining <= 0) showTicksRemaining.remove(playerId);
              else showTicksRemaining.put(playerId, remaining);
            }
          }
          previouslyInRange.removeAll(currentlyInRange);
          for (UUID left : previouslyInRange) showTicksRemaining.remove(left);
          previouslyInRange.clear();
          previouslyInRange.addAll(currentlyInRange);
        }
      }.runTaskTimer(plugin.getJavaPlugin(), 1L, 20L);
    }

    private void messageInRange(Component message) {
      messagePlayers(playersAtStart, message);
    }

    void rejectBusy() {
      if (!markStopped()) return;
      messageInRange(plugin.getMessages().prefixedComponent("error.play.busy"));
    }

    private synchronized boolean markStopped() {
      if (stopped) return false;
      stopped = true;
      sessionLimiter.release();
      return true;
    }

    void stop() {
      if (!markStopped()) return;

      // Kill audio resources immediately on whatever thread called stop() (may be SVC's async
      // setOnStopped callback), so they are not Bukkit-thread-bound.
      if (player != null) try { player.stopPlaying(); } catch (Exception ignored) {}
      if (stream != null) stream.close();
      if (encoder != null) try { encoder.close(); } catch (Exception ignored) {}

      // Return to the server thread because stop() may run from the voice-chat
      // player's asynchronous completion callback.
      Bukkit.getScheduler().runTaskLater(plugin.getJavaPlugin(), () -> {
        if (block.getState() instanceof Jukebox jukebox) {
          jukebox.stopPlaying();
        }
      }, 1L);
    }
  }

  /**
   * A custom goat-horn playback. A trimmed {@link Session}: no jukebox/block coupling, no keep-alive
   * timer and no now-playing action bar (horn sounds are capped to a few seconds). Self-removes from
   * {@link #hornSessions} when the audio ends.
   */
  private class HornSession {
    private final UUID playerId;
    private final String identifier;
    private final EntityAudioChannel channel;
    private final float volume;
    private final int maxSeconds;

    private volatile FfmpegPcmStream stream;
    private volatile OpusEncoder encoder;
    private volatile AudioPlayer player;
    private volatile boolean stopped = false;

    HornSession(UUID playerId, String identifier, EntityAudioChannel channel, float volume, int maxSeconds) {
      this.playerId = playerId;
      this.identifier = identifier;
      this.channel = channel;
      this.volume = volume;
      this.maxSeconds = maxSeconds;
    }

    void startAudio() {
      if (stopped) return;
      try {
        VoicechatServerApi api = VoiceMediaRegistry.getInstance().serverApi();

        // Fast path: play straight from the on-disk cache - no yt-dlp, no ffmpeg, no network.
        HornAudioCache cache = hornCacheIfEnabled();
        List<short[]> cached = cache == null ? null : cache.read(hornCacheKey(identifier, volume));
        if (cached != null && !cached.isEmpty()) {
          this.encoder = api.createEncoder(OpusEncoderMode.AUDIO);
          this.player = api.createAudioPlayer(channel, encoder, framesFrom(cached));
          if (stopped) {
            try { player.stopPlaying(); } catch (Exception ignored) {}
            try { encoder.close(); } catch (Exception ignored) {}
            return;
          }
          this.player.setOnStopped(() -> { if (!stopped) AudioEngine.this.stopHorn(playerId); });
          this.player.startPlaying();
          return;
        }

        // Cache miss: stream as usual and fill the cache in the background for next time.
        TrackResolver.ResolvedTrack track = resolver.resolve(identifier);
        if (track == null || stopped) {
          if (!stopped) messagePlayer(plugin.getMessages().prefixedComponent("error.play.no-matches"));
          AudioEngine.this.stopHorn(playerId);
          return;
        }
        AudioEngine.this.fillHornCacheAsync(identifier, volume);

        this.stream = new FfmpegPcmStream(binaries.getFfmpegPath(), track.streamUrl(),
          volume, destinationPolicy, policyProxy, maxSeconds);
        this.encoder = api.createEncoder(OpusEncoderMode.AUDIO);
        this.player = api.createAudioPlayer(channel, encoder, stream.frames());
        if (stopped) {
          try { player.stopPlaying(); } catch (Exception ignored) {}
          stream.close();
          try { encoder.close(); } catch (Exception ignored) {}
          return;
        }
        this.player.setOnStopped(() -> { if (!stopped) AudioEngine.this.stopHorn(playerId); });
        this.player.startPlaying();
      } catch (Throwable e) {
        MediaFeature.error("Horn audio session error for {}: ", e, identifier);
        if (!stopped) messagePlayer(plugin.getMessages().prefixedComponent("error.play.while-playing"));
        AudioEngine.this.stopHorn(playerId);
      }
    }

    private void messagePlayer(Component message) {
      Player bukkitPlayer = plugin.getServer().getPlayer(playerId);
      if (bukkitPlayer != null) MediaFeature.sendMessage(bukkitPlayer, message);
    }

    void rejectBusy() {
      if (!markStopped()) return;
      messagePlayer(plugin.getMessages().prefixedComponent("error.play.busy"));
    }

    private synchronized boolean markStopped() {
      if (stopped) return false;
      stopped = true;
      sessionLimiter.release();
      return true;
    }

    void stop() {
      if (!markStopped()) return;
      if (player != null) try { player.stopPlaying(); } catch (Exception ignored) {}
      if (stream != null) stream.close();
      if (encoder != null) try { encoder.close(); } catch (Exception ignored) {}
    }
  }

  static final class SessionLimiter {
    private final Semaphore permits;

    SessionLimiter(int maximumSessions) {
      if (maximumSessions < 1) throw new IllegalArgumentException("maximumSessions must be positive");
      this.permits = new Semaphore(maximumSessions);
    }

    boolean tryAcquire() { return permits.tryAcquire(); }
    void release() { permits.release(); }
    int availablePermits() { return permits.availablePermits(); }
  }
}
