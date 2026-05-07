package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Cross-server audio relay for the fixed set of "global" persistent
 * voice groups. User-created GUI groups stay local on whichever
 * backend they were created on.
 *
 * <p>Outbound: when a local speaker's group is on the cross-server
 * whitelist, publish the opus frame on Redis under
 * {@link VoiceMessages#audioChannel}. SVC's own routing continues to
 * deliver audio to local group members in parallel — we never cancel
 * the event.
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
    private final Set<UUID> crossServerGroupIds;
    private final String thisBackend;
    private final Logger logger;

    private VoicechatServerApi api;

    private final Map<UUID, ChannelEntry> channels = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerHomeCache> playerHomeCache = new ConcurrentHashMap<>();

    /** Speakers we've successfully relayed at least one frame for — used
     *  to log a single INFO line on first relay so the operator can see
     *  the cross-server audio path is working without per-frame spam. */
    private final Set<UUID> firstRelayLogged = ConcurrentHashMap.newKeySet();

    /** Speakers we've already complained about a null-channel for, so we
     *  don't spam the log on every retry. Cleared on successful create. */
    private final Set<UUID> nullChannelLogged = ConcurrentHashMap.newKeySet();

    /** Speakers we've already logged an origin-mismatch drop for, again
     *  for log-spam control. Cleared on first matching frame. */
    private final Set<UUID> originMismatchLogged = ConcurrentHashMap.newKeySet();

    private BukkitTask evictionTask;

    AudioRelay(CrabUtilities plugin, RedisVoiceBus bus, MembershipTracker membership,
               Set<UUID> crossServerGroupIds, String thisBackend, Logger logger) {
        this.plugin = plugin;
        this.bus = bus;
        this.membership = membership;
        this.crossServerGroupIds = crossServerGroupIds;
        this.thisBackend = thisBackend;
        this.logger = logger;
    }

    void setApi(VoicechatServerApi api) {
        this.api = api;
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
    }

    /**
     * Outbound: forward a local speaker's opus frame to other backends.
     * Only the fixed cross-server group whitelist gets bridged — user-
     * created GUI groups stay local. We never cancel the SVC event so
     * native local-server group routing keeps working unchanged.
     */
    void onMicrophonePacketEvent(MicrophonePacketEvent event) {
        VoicechatConnection conn = event.getSenderConnection();
        if (conn == null) return;
        var group = conn.getGroup();
        if (group == null) return;
        UUID groupId = group.getId();
        if (!crossServerGroupIds.contains(groupId)) return;

        MicrophonePacket packet = event.getPacket();
        if (packet == null) return;
        byte[] opus = packet.getOpusEncodedData();
        if (opus == null || opus.length == 0) return;

        UUID speakerId = conn.getPlayer().getUuid();
        byte[] frame = VoiceMessages.encodeAudioFrame(thisBackend, speakerId,
                packet.isWhispering(), opus);
        bus.publishAudio(groupId, frame);
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
        if (thisBackend.equals(frame.homeBackend())) return;
        // Single-writer guarantee: only the speaker's actual current
        // home backend (per Velocity) is allowed to publish for them.
        if (!isAuthoritativeOrigin(frame.speaker(), frame.homeBackend())) {
            if (originMismatchLogged.add(frame.speaker())) {
                PlayerHomeCache cached = playerHomeCache.get(frame.speaker());
                String actual = cached == null ? "null" : cached.homeBackend();
                logger.warning("Dropping audio frame: speaker " + frame.speaker()
                        + " claims home='" + frame.homeBackend()
                        + "' but Velocity says home='" + actual
                        + "'. (Likely cause: voicechat.cross-server.this-backend on the "
                        + "speaker's backend doesn't match its name in velocity.toml. "
                        + "Names are case-sensitive.)");
            }
            return;
        }
        // Re-arm the mismatch logger so we'd warn again if it starts flapping.
        originMismatchLogged.remove(frame.speaker());

        Set<UUID> localTargets = membership.getLocalMembers(groupId);
        if (localTargets.isEmpty()) return;

        // Get-or-create channel without caching null. If createChannel
        // fails (e.g. the API rejects the speaker's UUID for some reason),
        // we fall through and try again on the next frame instead of
        // silently dropping audio for 60s.
        ChannelEntry entry = channels.get(frame.speaker());
        if (entry == null || entry.channel == null) {
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

        // Refresh targets every frame — cheap, and avoids stale state when
        // a local listener leaves the group mid-conversation.
        syncTargets(entry, localTargets);
        entry.lastUsed = System.currentTimeMillis();
        try {
            entry.channel.send(frame.opus());
            if (firstRelayLogged.add(frame.speaker())) {
                logger.info("Now relaying audio from " + frame.speaker()
                        + " (backend='" + frame.homeBackend() + "') to "
                        + localTargets.size() + " local listener(s)");
            }
        } catch (Exception e) {
            logger.warning("Audio send failed for " + frame.speaker() + ": " + e.getMessage());
        }
    }

    /**
     * Discard the cached per-speaker channel — called when we learn the
     * speaker has disconnected or left their group.
     */
    void invalidateSpeaker(UUID speakerId) {
        ChannelEntry entry = channels.remove(speakerId);
        if (entry != null && entry.channel != null) {
            try { entry.channel.clearTargets(); } catch (Exception ignored) {}
            try { entry.channel.flush(); } catch (Exception ignored) {}
        }
        playerHomeCache.remove(speakerId);
        firstRelayLogged.remove(speakerId);
        nullChannelLogged.remove(speakerId);
        originMismatchLogged.remove(speakerId);
    }

    void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        invalidateSpeaker(event.getPlayerUuid());
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
        Set<UUID> have = entry.currentTargets;
        if (have.equals(wantedPlayerIds)) return;
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
        // Track only the targets actually added. If api.getConnectionOf
        // returned null for a wanted listener (transient — happens during
        // the brief SVC bookkeeping window around a peer's hop), leaving
        // them out here means have != wantedPlayerIds on the next frame
        // and we'll retry. Caching wantedPlayerIds verbatim made the
        // failure permanent: every subsequent frame short-circuited at
        // the equals() check above and the channel sat with no targets
        // until the 60s idle-eviction recreated it.
        entry.currentTargets = Set.copyOf(added);
    }

    private boolean isAuthoritativeOrigin(UUID speakerId, String claimedBackend) {
        long now = System.currentTimeMillis();
        PlayerHomeCache cached = playerHomeCache.get(speakerId);
        if (cached == null || (now - cached.fetchedAt) > PLAYER_HOME_CACHE_MS) {
            String home = bus.fetchPlayerHome(speakerId);
            cached = new PlayerHomeCache(home, now);
            playerHomeCache.put(speakerId, cached);
        }
        // If Velocity hasn't recorded a home for this player yet, accept the frame
        // (cold-start tolerance). The TTL on the Redis key keeps stale data bounded.
        if (cached.homeBackend == null) return true;
        return cached.homeBackend.equals(claimedBackend);
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

    private record PlayerHomeCache(String homeBackend, long fetchedAt) {}
}
