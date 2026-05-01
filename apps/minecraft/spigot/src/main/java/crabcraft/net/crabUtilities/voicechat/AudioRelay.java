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
 * Cross-server audio relay.
 *
 * <p>Outbound: when a player on this backend speaks and is in a group
 * that has remote members, publish the opus frame on Redis under
 * {@link VoiceMessages#audioChannel}. We never cancel the SVC event so
 * native local-server group routing continues to deliver audio to local
 * group members through SVC's normal path.
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

    private VoicechatServerApi api;

    private final Map<UUID, ChannelEntry> channels = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerHomeCache> playerHomeCache = new ConcurrentHashMap<>();

    private BukkitTask evictionTask;

    AudioRelay(CrabUtilities plugin, RedisVoiceBus bus, MembershipTracker membership,
               String thisBackend, Logger logger) {
        this.plugin = plugin;
        this.bus = bus;
        this.membership = membership;
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
     * Outbound: forward a local speaker's opus frame to other backends if
     * they have group members. Always returns false from the perspective
     * of cancelling — we never block SVC's native processing.
     */
    void onMicrophonePacketEvent(MicrophonePacketEvent event) {
        VoicechatConnection conn = event.getSenderConnection();
        if (conn == null) return;
        var group = conn.getGroup();
        if (group == null) return;
        UUID groupId = group.getId();

        MicrophonePacket packet = event.getPacket();
        if (packet == null) return;
        byte[] opus = packet.getOpusEncodedData();
        if (opus == null || opus.length == 0) return;

        UUID speakerId = conn.getPlayer().getUuid();
        // Always publish — we can't skip on "no remote members" because a
        // backend that just came online won't yet know about pre-existing
        // remote members, and would silently drop their audio. Subscribers
        // that have no local listeners simply won't subscribe to this
        // group's audio channel, so wasted Redis bandwidth is bounded.
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
        if (!isAuthoritativeOrigin(frame.speaker(), frame.homeBackend())) return;

        Set<UUID> localTargets = membership.getLocalMembers(groupId);
        if (localTargets.isEmpty()) return;

        ChannelEntry entry = channels.computeIfAbsent(frame.speaker(),
                id -> new ChannelEntry(createChannel(id)));
        if (entry.channel == null) return;

        // Refresh targets every frame — cheap, and avoids stale state when
        // a local listener leaves the group mid-conversation.
        syncTargets(entry, localTargets);
        entry.lastUsed = System.currentTimeMillis();
        try {
            entry.channel.send(frame.opus());
        } catch (Exception e) {
            logger.fine(() -> "Audio send failed for " + frame.speaker() + ": " + e.getMessage());
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
        try {
            entry.channel.clearTargets();
            for (UUID id : wantedPlayerIds) {
                VoicechatConnection conn = api.getConnectionOf(id);
                if (conn != null) {
                    entry.channel.addTarget(conn);
                }
            }
        } catch (Exception e) {
            logger.fine(() -> "Failed to sync audio targets: " + e.getMessage());
        }
        entry.currentTargets = Set.copyOf(wantedPlayerIds);
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
