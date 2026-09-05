package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Cross-server audio relay for every voice group.
 *
 * <p>Outbound: publish grouped opus frames on Redis under
 * {@link VoiceMessages#audioChannel}. SVC's own routing continues to
 * deliver normal audio to local group members in parallel. Only packets
 * racing a quit are cancelled so they cannot reopen an old client
 * channel after its sequence reset.
 *
 * <p>Inbound: an opus frame received from another backend is pushed
 * into a {@link StaticAudioChannel} keyed by the speaker's UUID, with
 * targets set to the local players in that group. Using the speaker's
 * UUID as the channel ID makes the SVC client render the talking
 * indicator against the correct head, even though the speaker isn't a
 * local MC player.
 */
class AudioRelay {

    /** Idle channel TTL in milliseconds. */
    private static final long CHANNEL_IDLE_MS = 60_000L;

    /** Per-speaker player-home cache TTL (~1Hz refresh). */
    private static final long PLAYER_HOME_CACHE_MS = 1_000L;

    private final CrabUtilities plugin;
    private final RedisVoiceBus bus;
    private final MembershipTracker membership;
    private final String thisBackend;
    private final Logger logger;
    private final Function<UUID, String> localRoute;
    private final UUID attenuatedGroupId;
    private final double attenuatedGroupGain;

    private VoicechatServerApi api;
    private OpusVolumeScaler remoteVolumeScaler;

    private final Map<UUID, ChannelEntry> channels = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRouteCache> playerHomeCache = new ConcurrentHashMap<>();
    private final Set<UUID> playerHomeRefreshes = ConcurrentHashMap.newKeySet();
    private final Map<UUID, StaticSoundPacket> lastNativePackets = new ConcurrentHashMap<>();
    private final Set<UUID> quittingSpeakers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DisconnectReset> disconnectResets = new ConcurrentHashMap<>();

    /** Speakers we've successfully relayed at least one frame for — used
     *  to log a single INFO line on first relay so the operator can see
     *  the cross-server audio path is working without per-frame spam. */
    private final Set<UUID> firstRelayLogged = ConcurrentHashMap.newKeySet();

    /** Speakers we've already complained about a null-channel for, so we
     *  don't spam the log on every retry. Cleared on successful create. */
    private final Set<UUID> nullChannelLogged = ConcurrentHashMap.newKeySet();

    private BukkitTask evictionTask;

    AudioRelay(CrabUtilities plugin, RedisVoiceBus bus, MembershipTracker membership,
               String thisBackend, Logger logger, Function<UUID, String> localRoute,
               UUID attenuatedGroupId, double attenuatedGroupGain) {
        this.plugin = plugin;
        this.bus = bus;
        this.membership = membership;
        this.thisBackend = thisBackend;
        this.logger = logger;
        this.localRoute = localRoute;
        this.attenuatedGroupId = attenuatedGroupId;
        this.attenuatedGroupGain = attenuatedGroupGain;
    }

    void setApi(VoicechatServerApi api) {
        this.api = api;
        if (attenuatedGroupId != null && attenuatedGroupGain < 1D) {
            this.remoteVolumeScaler = new OpusVolumeScaler(api, attenuatedGroupGain);
        }
    }

    void start() {
        // Evict idle channels every 30s
        evictionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::evictIdle, 20L * 30L, 20L * 30L);
    }

    void shutdown() {
        if (evictionTask != null) {
            try { evictionTask.cancel(); } catch (Exception ignored) {}
        }
        for (ChannelEntry entry : channels.values()) {
            try { entry.channel.flush(); } catch (Exception ignored) {}
        }
        channels.clear();
        lastNativePackets.clear();
        quittingSpeakers.clear();
        disconnectResets.clear();
        playerHomeRefreshExecutor.shutdownNow();
        if (remoteVolumeScaler != null) remoteVolumeScaler.close();
    }

    /**
     * Outbound: forward a local speaker's opus frame to other backends.
     * Normal SVC events are not cancelled, so native local-server group
     * routing keeps working unchanged.
     */
    void onMicrophonePacketEvent(MicrophonePacketEvent event) {
        VoicechatConnection conn = event.getSenderConnection();
        if (conn == null) return;
        var group = conn.getGroup();
        if (group == null) return;
        UUID groupId = group.getId();

        MicrophonePacket packet = event.getPacket();
        if (packet == null) return;
        UUID speakerId = conn.getPlayer().getUuid();
        try {
            lastNativePackets.put(speakerId, packet.staticSoundPacketBuilder()
                    .channelId(speakerId)
                    .build());
        } catch (Exception e) {
            logger.fine(() -> "Could not read microphone sequence for " + speakerId
                    + ": " + e.getMessage());
        }
        if (stopQuittingPacket(event, speakerId)) return;

        byte[] opus = packet.getOpusEncodedData();
        // Empty Opus is SVC's stop/reset marker, so it must cross backends too.
        if (!isRelayPayload(opus)) return;

        String route = localRoute.apply(speakerId);
        if (!thisBackend.equals(VoiceMessages.routeBackend(route))) return;
        byte[] frame = VoiceMessages.encodeAudioFrame(route, speakerId,
                packet.isWhispering(), opus);
        if (stopQuittingPacket(event, speakerId)) return;
        bus.publishAudio(groupId, speakerId, frame, opus.length == 0);
    }

    private boolean stopQuittingPacket(
            MicrophonePacketEvent event, UUID speakerId) {
        if (!quittingSpeakers.contains(speakerId)) return false;
        event.cancel();
        try {
            resetSpeaker(speakerId);
        } catch (Exception e) {
            logger.warning("Failed to reset late microphone packet for " + speakerId
                    + ": " + e.getMessage());
        }
        return true;
    }

    static boolean isRelayPayload(byte[] opus) {
        return opus != null;
    }

    /**
     * Inbound: a frame arrived on {@code crabcraft:svc:audio:<groupId>}
     * from some other backend. Decode, validate origin, push into a
     * StaticAudioChannel for local listeners.
     */
    void onAudioFrame(UUID groupId, byte[] data) {
        if (api == null) return;
        VoiceMessages.AudioFrame frame;
        try {
            frame = VoiceMessages.decodeAudioFrame(data);
        } catch (Exception e) {
            logger.fine(() -> "Skipping malformed audio frame on " + groupId + ": " + e.getMessage());
            return;
        }
        // Don't replay our own frames
        if (thisBackend.equals(VoiceMessages.routeBackend(frame.route()))) return;
        // A locally connected player is routed natively. This also prevents a
        // late frame from their previous backend re-poisoning the sequence.
        if (api.getConnectionOf(frame.speaker()) != null) return;
        Set<UUID> localTargets = membership.getLocalMembers(groupId);
        if (localTargets.isEmpty()) return;
        // Single-writer guarantee: only the speaker's actual current
        // home backend (per Velocity) is allowed to publish for them.
        if (!isAuthoritativeOrigin(frame.speaker(), frame.route())) return;

        // Get-or-create channel without caching null. If createChannel
        // fails (e.g. the API rejects the speaker's UUID for some reason),
        // we fall through and try again on the next frame instead of
        // silently dropping audio for 60s.
        ChannelEntry entry = channels.get(frame.speaker());
        if (entry == null) {
            StaticAudioChannel ch = createChannel(frame.speaker());
            if (ch == null) {
                if (nullChannelLogged.add(frame.speaker())) {
                    logger.warning("StaticAudioChannel.create returned null for speaker "
                            + frame.speaker() + " — audio for this player will be dropped "
                            + "until the API accepts the channel ID. Will retry every frame.");
                }
                return;
            }
            nullChannelLogged.remove(frame.speaker());
            entry = new ChannelEntry(ch);
            channels.put(frame.speaker(), entry);
        }

        synchronized (entry) {
            // Re-check after taking the channel lock so a local arrival cannot
            // race its reset marker with one final frame from the old backend.
            if (api.getConnectionOf(frame.speaker()) != null
                    || channels.get(frame.speaker()) != entry) return;

            // Refresh targets every frame — cheap, and avoids stale state when
            // a local listener leaves the group mid-conversation.
            syncTargets(entry, localTargets);
            entry.lastUsed = System.currentTimeMillis();
            try {
                byte[] opus = frame.opus();
                if (remoteVolumeScaler != null && groupId.equals(attenuatedGroupId)) {
                    opus = remoteVolumeScaler.scaleNext(frame.speaker(), opus);
                }
                entry.channel.send(opus);
                if (firstRelayLogged.add(frame.speaker())) {
                    logger.info("Now relaying audio from " + frame.speaker()
                            + " (backend='" + VoiceMessages.routeBackend(frame.route())
                            + "') to "
                            + localTargets.size() + " local listener(s)");
                }
            } catch (Exception e) {
                logger.warning("Audio send failed for " + frame.speaker() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Stop a remote roster entry without restarting its packet sequence.
     * Listeners who already left its targets will miss the flush; retaining the
     * channel lets them accept audio immediately if they hear this speaker again.
     * The normal idle eviction still releases channels after 60 seconds.
     */
    void stopRemoteSpeaker(UUID speakerId) {
        resetRelayChannel(speakerId, channels.get(speakerId));
    }

    /**
     * Flush and discard a cached relay channel at a route handoff or eviction.
     * Flushing first gives current targets an accepted next-sequence stop marker.
     */
    void invalidateSpeaker(UUID speakerId) {
        resetRelayChannel(speakerId, channels.remove(speakerId));
    }

    private void resetRelayChannel(UUID speakerId, ChannelEntry entry) {
        if (entry != null) {
            synchronized (entry) {
                try { entry.channel.flush(); } catch (Exception ignored) {}
                try { entry.channel.clearTargets(); } catch (Exception ignored) {}
                entry.currentTargets = Set.of();
            }
        }
        playerHomeCache.remove(speakerId);
        firstRelayLogged.remove(speakerId);
        nullChannelLogged.remove(speakerId);
        if (remoteVolumeScaler != null) remoteVolumeScaler.remove(speakerId);
    }

    void beforePlayerQuit(UUID speakerId) {
        quittingSpeakers.add(speakerId);
        try {
            resetSpeaker(speakerId);
        } catch (Exception e) {
            logger.warning("Failed to send pre-quit voice stop marker for " + speakerId
                    + ": " + e.getMessage());
        }
    }

    void onPlayerConnect(UUID speakerId) {
        DisconnectReset pending = disconnectResets.remove(speakerId);
        if (pending != null) {
            try {
                sendLocalReset(speakerId, pending.targets());
            } catch (Exception e) {
                logger.warning("Failed to reset voice before reconnect for " + speakerId
                        + ": " + e.getMessage());
            }
        }
        quittingSpeakers.remove(speakerId);
        lastNativePackets.remove(speakerId);
        invalidateSpeaker(speakerId);
    }

    void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        UUID speakerId = event.getPlayerUuid();
        quittingSpeakers.add(speakerId);
        UUID groupId = membership.getLocalGroupOf(speakerId);
        Set<UUID> targets = groupId == null
                ? Set.of()
                : membership.getLocalMembers(groupId);
        DisconnectReset reset = new DisconnectReset(targets);
        disconnectResets.put(speakerId, reset);
        try {
            // Repeat the early reset using the latest observed packet. A mic
            // frame may already have been in SVC's processing thread when the
            // Bukkit quit event ran.
            resetSpeaker(speakerId);
        } catch (Exception e) {
            logger.warning("Failed to send native stop marker for " + speakerId
                    + ": " + e.getMessage());
        } finally {
            invalidateSpeaker(speakerId);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> finishDisconnectReset(speakerId, reset, false), 1L);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> finishDisconnectReset(speakerId, reset, true), 2L);
        }
    }

    private void finishDisconnectReset(
            UUID speakerId, DisconnectReset reset, boolean finalPass) {
        if (disconnectResets.get(speakerId) != reset) return;
        try {
            // Bounded delayed resets close the small gap for a mic packet
            // already past SVC's connection lookup when disconnect began.
            if (api.getConnectionOf(speakerId) == null) {
                sendLocalReset(speakerId, reset.targets());
            }
        } catch (Exception e) {
            logger.warning("Failed to send final voice stop marker for " + speakerId
                    + ": " + e.getMessage());
        } finally {
            if (finalPass && disconnectResets.remove(speakerId, reset)) {
                lastNativePackets.remove(speakerId);
                quittingSpeakers.remove(speakerId);
            }
        }
    }

    private void resetSpeaker(UUID speakerId) throws ReflectiveOperationException {
        UUID groupId = membership.getLocalGroupOf(speakerId);
        if (groupId == null) return;

        sendLocalReset(speakerId, membership.getLocalMembers(groupId));

        String route = localRoute.apply(speakerId);
        if (thisBackend.equals(VoiceMessages.routeBackend(route))) {
            bus.publishAudio(groupId, speakerId,
                    VoiceMessages.encodeAudioFrame(
                            route, speakerId, false, new byte[0]),
                    true);
        }
    }

    private void sendLocalReset(UUID speakerId, Set<UUID> targets)
            throws ReflectiveOperationException {
        StaticSoundPacket lastPacket = lastNativePackets.get(speakerId);
        if (lastPacket == null) return;
        StaticSoundPacket reset = nextSequenceStop(lastPacket);
        for (UUID targetId : targets) {
            if (speakerId.equals(targetId)) continue;
            VoicechatConnection target = api.getConnectionOf(targetId);
            if (target != null) api.sendStaticSoundPacketTo(target, reset);
        }
    }

    /**
     * SVC 2.6.13 exposes packet sequence reads but not writes. Its packet
     * builder still carries the sequence field, while 2.6.20+ exposes a setter;
     * support both so listeners receive an accepted N+1 reset.
     */
    static StaticSoundPacket nextSequenceStop(StaticSoundPacket lastPacket)
            throws ReflectiveOperationException {
        StaticSoundPacket.Builder<?> builder = lastPacket.staticSoundPacketBuilder()
                .channelId(lastPacket.getChannelId())
                .opusEncodedData(new byte[0]);
        long nextSequence = lastPacket.getSequenceNumber() + 1L;
        try {
            builder.getClass().getMethod("sequenceNumber", long.class)
                    .invoke(builder, nextSequence);
        } catch (NoSuchMethodException e) {
            Class<?> type = builder.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("sequenceNumber");
                    field.setAccessible(true);
                    field.setLong(builder, nextSequence);
                    return builder.build();
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw e;
        }
        return builder.build();
    }

    private StaticAudioChannel createChannel(UUID speakerId) {
        try {
            StaticAudioChannel channel = api.createStaticAudioChannel(speakerId);
            if (channel != null) {
                channel.setBypassGroupIsolation(true);
            }
            return channel;
        } catch (Exception e) {
            logger.warning("Failed to create static audio channel for " + speakerId + ": " + e.getMessage());
            return null;
        }
    }

    private void syncTargets(ChannelEntry entry, Set<UUID> wantedPlayerIds) {
        if (entry.currentTargets.equals(wantedPlayerIds)) return;
        Set<UUID> added = new java.util.HashSet<>();
        try {
            entry.channel.clearTargets();
            for (UUID id : wantedPlayerIds) {
                VoicechatConnection conn = api.getConnectionOf(id);
                if (conn == null) continue;
                entry.channel.addTarget(conn);
                added.add(id);
            }
        } catch (Exception e) {
            logger.warning("Failed to sync audio targets: " + e.getMessage());
        }
        // Track only successful additions so a transient null lookup is
        // retried on the next frame.
        entry.currentTargets = Set.copyOf(added);
    }

    private boolean isAuthoritativeOrigin(UUID speakerId, String claimedRoute) {
        long now = System.currentTimeMillis();
        PlayerRouteCache cached = playerHomeCache.get(speakerId);
        if (cached == null || cached.route == null) {
            refreshPlayerHome(speakerId);
            return false;
        }
        if (!cached.route.equals(claimedRoute)) {
            // Once a new hop token appears, fail closed instead of accepting
            // delayed frames from the cached old hop while Redis catches up.
            playerHomeCache.remove(speakerId, cached);
            refreshPlayerHome(speakerId);
            return false;
        }
        if (now - cached.fetchedAt > PLAYER_HOME_CACHE_MS) {
            // Refresh matching routes in the background without adding a
            // one-frame gap to every active speaker each second.
            refreshPlayerHome(speakerId);
        }
        return true;
    }

    private void refreshPlayerHome(UUID speakerId) {
        if (!playerHomeRefreshes.add(speakerId)) return;
        try {
            playerHomeRefreshExecutor.execute(() -> {
                try {
                    playerHomeCache.put(speakerId,
                            new PlayerRouteCache(bus.fetchPlayerHome(speakerId),
                                    System.currentTimeMillis()));
                } finally {
                    playerHomeRefreshes.remove(speakerId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            playerHomeRefreshes.remove(speakerId);
        }
    }

    private void evictIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, ChannelEntry> e : channels.entrySet()) {
            if ((now - e.getValue().lastUsed) > CHANNEL_IDLE_MS) {
                invalidateSpeaker(e.getKey());
            }
        }
        // Also evict stale player-home cache entries
        playerHomeCache.entrySet().removeIf(
                e -> (now - e.getValue().fetchedAt) > (PLAYER_HOME_CACHE_MS * 60));
    }

    private static final class ChannelEntry {
        final StaticAudioChannel channel;
        volatile Set<UUID> currentTargets = Set.of();
        volatile long lastUsed = System.currentTimeMillis();

        ChannelEntry(StaticAudioChannel channel) {
            this.channel = channel;
        }
    }

    private record PlayerRouteCache(String route, long fetchedAt) {}

    private record DisconnectReset(Set<UUID> targets) {}

    private final java.util.concurrent.ThreadPoolExecutor playerHomeRefreshExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(64),
                    r -> {
                        Thread thread = new Thread(r, "CrabUtilities-VoiceRoute");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
}
