package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.NicknameComponentResolver;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class CrabVoicechatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "crabutilities";

    /** TTL on the {@code crabcraft:svc:player-group:&lt;uuid&gt;} key. Slightly
     *  longer than 3x the rebroadcast interval so a routine server hop
     *  doesn't expire before the player reconnects. */
    private static final long PLAYER_GROUP_TTL_SECONDS = 90L;
    // SVC also uses this label in recording filenames; 48 code points keeps
    // even four-byte UTF-8 names below common per-file limits.
    private static final int MAX_ROSTER_NAME_CODE_POINTS = 48;
    private static final Pattern UNSAFE_ROSTER_NAME = Pattern.compile("[\\p{Cc}/\\\\:*?\"<>|]");

    private final CrabUtilities plugin;
    private final Logger logger;

    private final boolean crossServerEnabled;
    private final String thisBackend;
    private final List<String> persistentGroupNames;

    private VoicechatServerApi api;
    private RedisVoiceBus bus;
    private MembershipTracker membership;
    private AudioRelay audioRelay;
    private RosterTracker roster;
    private SvcPacketSender svcPackets;
    private BukkitTask rosterRebroadcastTask;
    private BukkitTask sweepTask;
    private Set<UUID> crossServerGroupIds = Set.of();

    /** Velocity backend names we've already warned about mismatching this-backend. */
    private final Set<String> homeMismatchWarned = ConcurrentHashMap.newKeySet();

    public CrabVoicechatPlugin(CrabUtilities plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.crossServerEnabled = plugin.getConfig().getBoolean("voicechat.cross-server.enabled", true);
        this.thisBackend = plugin.getConfig().getString("voicechat.cross-server.this-backend", "");
        List<String> configured = plugin.getConfig().getStringList("voicechat.cross-server.persistent-groups");
        this.persistentGroupNames = configured.isEmpty()
                ? List.of("Global #1", "Global #2", "Global #3")
                : configured;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Nothing to initialize before the server is up.
    }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        if (!crossServerEnabled) return;
        reg.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        reg.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        reg.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup);
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        reg.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnect);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        this.api = event.getVoicechat();

        Set<UUID> ids = new HashSet<>();
        for (String name : persistentGroupNames) {
            UUID id = deterministicGroupId(name);
            ids.add(id);
            Group group = api.groupBuilder()
                    .setId(id)
                    .setName(name)
                    .setType(Group.Type.OPEN)
                    .setPersistent(true)
                    .build();
            logger.info("Created persistent voice chat group '" + name + "' (" + group.getId() + ")");
        }
        this.crossServerGroupIds = Set.copyOf(ids);

        if (!crossServerEnabled) return;
        if (thisBackend == null || thisBackend.isEmpty()) {
            logger.warning("voicechat.cross-server.enabled=true but this-backend is empty in config — "
                    + "cross-server voice DISABLED (set voicechat.cross-server.this-backend to this backend's "
                    + "Velocity server name)");
            return;
        }
        startBridge();
    }

    private void startBridge() {
        this.bus = new RedisVoiceBus(plugin);
        this.membership = new MembershipTracker();
        this.audioRelay = new AudioRelay(plugin, bus, membership, crossServerGroupIds,
                thisBackend, logger);
        audioRelay.setApi(api);
        this.svcPackets = new SvcPacketSender(plugin);
        this.roster = new RosterTracker(plugin, svcPackets,
                crossServerGroupIds, thisBackend, logger);

        bus.start(audioRelay::onAudioFrame, roster::onLifecycleMessage);

        for (UUID id : crossServerGroupIds) {
            bus.subscribeAudio(id);
        }

        audioRelay.start();

        // Re-broadcast the local roster every 30 s. Each entry's
        // lastSeenAt timestamp is bumped on every receive — a missing
        // re-broadcast for 90 s causes the receiver to sweep the entry
        // out of its local GUI. This catches both the cold-start case
        // (a backend that came online late learns about existing members
        // within 30 s) and the ghost case (a backend that crashed has
        // its members swept within 90 s).
        rosterRebroadcastTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::rebroadcastLocalRoster,
                20L * 30L, 20L * 30L);

        sweepTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> roster.sweepStaleEntries(),
                20L * 60L, 20L * 30L);

        logger.info("Cross-server voice bridge started (backend='" + thisBackend + "', "
                + crossServerGroupIds.size() + " groups; relying on tab-list sync for skins)");
    }

    /** Re-publish a {@code ROSTER_JOIN} for every local member of every cross-server group. */
    private void rebroadcastLocalRoster() {
        if (bus == null || membership == null) return;
        for (UUID groupId : crossServerGroupIds) {
            for (UUID localId : membership.getLocalMembers(groupId)) {
                Player p = Bukkit.getPlayer(localId);
                if (p == null || !p.isOnline()) continue;
                bus.publishRoster(VoiceMessages.encodeRosterJoin(
                        groupId, localId, voicechatName(p), thisBackend));
                // Refresh the auto-rejoin TTL so a player who stays in
                // a group keeps their persistence record alive.
                bus.writePlayerGroup(localId, groupId, PLAYER_GROUP_TTL_SECONDS);
            }
        }
    }

    /**
     * On voice connect (first SVC connect AND every server hop):
     * <ol>
     *   <li>Send the full current cross-server roster so the join-group
     *       GUI shows other backends' members <em>before</em> the player
     *       joins any group.</li>
     *   <li>Auto-rejoin their last group via Redis if known.</li>
     * </ol>
     */
    private void onPlayerConnected(PlayerConnectedEvent event) {
        if (api == null || bus == null) return;
        VoicechatConnection conn = event.getConnection();
        if (conn == null) return;
        UUID playerId = conn.getPlayer().getUuid();

        // If this player was previously heard through a relay on this backend,
        // its next-sequence stop marker resets listeners before native audio starts.
        audioRelay.invalidateSpeaker(playerId);

        // Catch-up roster on the main thread — purely local sends, no
        // Redis read. Doing it eagerly (not waiting on the auto-rejoin
        // Redis round-trip) means the GUI is correct as soon as the
        // player's voice connection comes up.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                roster.catchUpNewLocalConnection(p);
            }
        });

        // Read Redis on a worker thread; the actual setGroup call must
        // run on the SVC server thread (the API is thread-safe, but we
        // route through the connection which expects normal scheduling).
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Self-check: Velocity just wrote this player's home key with
            // THIS backend's name from velocity.toml. If it doesn't match
            // our configured this-backend, every other backend will drop
            // the audio frames we publish — surface the config error here
            // instead of failing near-silently on the listeners' side.
            String velocityName = bus.fetchPlayerHome(playerId);
            if (velocityName != null && !velocityName.equals(thisBackend)
                    && homeMismatchWarned.add(velocityName)) {
                logger.severe("voicechat.cross-server.this-backend is '" + thisBackend
                        + "' but Velocity calls this backend '" + velocityName
                        + "'. Other backends will DROP all voice frames published by "
                        + "this server — fix config.yml (names are case-sensitive).");
            }

            String groupIdStr = bus.fetchPlayerGroup(playerId);
            if (groupIdStr == null) return;
            UUID groupId;
            try { groupId = UUID.fromString(groupIdStr); } catch (Exception e) { return; }
            if (!crossServerGroupIds.contains(groupId)) return;

            Group group = api.getGroup(groupId);
            if (group == null) return;

            // setGroup fires JoinGroupEvent which our onJoinGroup
            // handles (records local membership, publishes ROSTER_JOIN).
            try {
                conn.setGroup(group);
                logger.info("Auto-rejoined " + playerId + " to group '" + group.getName() + "'");
            } catch (Exception e) {
                logger.warning("Auto-rejoin failed for " + playerId + ": " + e.getMessage());
            }
        });
    }

    private void onJoinGroup(JoinGroupEvent event) {
        if (membership == null) return;
        Group group = event.getGroup();
        if (group == null || event.getConnection() == null) return;

        membership.onJoinGroupEvent(event);

        if (!crossServerGroupIds.contains(group.getId())) return;

        UUID playerId = event.getConnection().getPlayer().getUuid();
        UUID groupId = group.getId();

        // Hop to the main thread: Bukkit player lookup + name access
        // is safer there than on SVC's network thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player bukkitPlayer = Bukkit.getPlayer(playerId);
            if (bukkitPlayer == null) return;

            String name = voicechatName(bukkitPlayer);
            bus.publishRoster(VoiceMessages.encodeRosterJoin(
                    groupId, playerId, name, thisBackend));
            bus.writePlayerGroup(playerId, groupId, PLAYER_GROUP_TTL_SECONDS);
            logger.info("Roster published: " + name + " (" + playerId + ") joined " + groupId);

            roster.catchUpNewLocalJoiner(groupId, bukkitPlayer);
        });
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        if (membership == null) return;
        Group group = event.getGroup();
        if (group != null && event.getConnection() != null
                && crossServerGroupIds.contains(group.getId())) {
            UUID playerId = event.getConnection().getPlayer().getUuid();
            UUID groupId = group.getId();
            // Decide on the next tick whether this was an explicit leave: a
            // GUI leave still has the player online then, while a leave the
            // SVC server fires as part of a disconnect (server hop) resolves
            // to offline. For hops, onPlayerDisconnect already publishes the
            // roster leave, and deleting the player-group key here would
            // break auto-rejoin on the destination backend.
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p == null || !p.isOnline()) return;
                    bus.publishRoster(VoiceMessages.encodeRosterLeave(
                            groupId, playerId, thisBackend));
                    // Explicit leave clears the auto-rejoin record so the player
                    // doesn't get put back into the group on their next server hop.
                    bus.deletePlayerGroup(playerId);
                });
            } catch (Exception ignored) {
                // Plugin is disabling; shutdown() broadcasts leaves for all
                // local members anyway.
            }
        }
        membership.onLeaveGroupEvent(event);
    }

    private String voicechatName(Player player) {
        return safeRosterName(
                NicknameComponentResolver.plainNicknameOrName(plugin.getEssentials(), player),
                player.getName());
    }

    static String safeRosterName(String name, String fallback) {
        String safe = UNSAFE_ROSTER_NAME.matcher(name).replaceAll("_");
        if (safe.codePointCount(0, safe.length()) > MAX_ROSTER_NAME_CODE_POINTS) {
            safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_ROSTER_NAME_CODE_POINTS));
        }
        return safe.isBlank() ? fallback : safe;
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (audioRelay != null) audioRelay.onMicrophonePacketEvent(event);
    }

    private void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        if (membership != null && bus != null) {
            for (UUID groupId : membership.getLocalGroupsOf(playerId)) {
                if (!crossServerGroupIds.contains(groupId)) continue;
                bus.publishRoster(VoiceMessages.encodeRosterLeave(
                        groupId, playerId, thisBackend));
            }
            // Leave the player-group key in place — disconnects from a
            // server hop should auto-rejoin on the new backend. The TTL
            // (90 s) handles real long-disconnects so a player who logs
            // off for hours doesn't get auto-rejoined when they return.
        }
        if (audioRelay != null) audioRelay.onPlayerDisconnect(event);
        if (membership != null) membership.onPlayerDisconnect(event);
    }

    /** Public hook so {@link CrabUtilities#onDisable()} can release resources. */
    public void shutdown() {
        for (BukkitTask task : new BukkitTask[]{rosterRebroadcastTask, sweepTask}) {
            if (task != null) {
                try { task.cancel(); } catch (Exception ignored) {}
            }
        }
        // Tell other backends our local players are leaving (best-effort).
        if (bus != null && membership != null) {
            for (UUID groupId : crossServerGroupIds) {
                for (UUID localId : membership.getLocalMembers(groupId)) {
                    bus.publishRoster(VoiceMessages.encodeRosterLeave(
                            groupId, localId, thisBackend));
                }
            }
        }
        if (roster != null) roster.shutdown();
        if (audioRelay != null) audioRelay.shutdown();
        if (bus != null) bus.shutdown();
    }

    /**
     * Stable UUID derived from the group name so all backends produce
     * the same UUID without needing to coordinate.
     */
    static UUID deterministicGroupId(String name) {
        return UUID.nameUUIDFromBytes(("crabcraft:svc:global:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
