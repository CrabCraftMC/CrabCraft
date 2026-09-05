package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.NicknameComponentResolver;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class CrabVoicechatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "crabutilities";
    static final String LOFI_GROUP_NAME = "Lofi 24/7 CrabFM";
    private static final String LOFI_GROUP_ID_SEED = "24/7 Lofi";

    /** TTL on the {@code crabcraft:svc:player-group:&lt;uuid&gt;} key. Slightly
     *  longer than 3x the rebroadcast interval so a routine server hop
     *  doesn't expire before the player reconnects. */
    private static final long PLAYER_GROUP_TTL_SECONDS = 90L;
    private static final int FAST_RESTORE_ATTEMPTS = 8;
    private static final int MAX_RESTORE_ATTEMPTS = 26;
    private static final long FAST_RESTORE_RETRY_TICKS = 10L;
    private static final long SLOW_RESTORE_RETRY_TICKS = 20L * 5L;
    // SVC also uses this label in recording filenames; 48 code points keeps
    // even four-byte UTF-8 names below common per-file limits.
    private static final int MAX_ROSTER_NAME_CODE_POINTS = 48;
    private static final Pattern UNSAFE_ROSTER_NAME = Pattern.compile("[\\p{Cc}/\\\\:*?\"<>|]");

    private final CrabUtilities plugin;
    private final Logger logger;

    private final boolean crossServerEnabled;
    private final String thisBackend;
    private final List<String> persistentGroupNames;
    private final boolean lofiEnabled;
    private final String lofiUrl;
    private final float lofiMusicVolume;
    private final double lofiPlayerVolume;
    private final UUID lofiGroupId;

    private VoicechatServerApi api;
    private RedisVoiceBus bus;
    private MembershipTracker membership;
    private AudioRelay audioRelay;
    private RosterTracker roster;
    private SvcPacketSender svcPackets;
    private BukkitTask rosterRebroadcastTask;
    private BukkitTask sweepTask;
    private BukkitTask groupReconcileTask;
    private BukkitTask routeRefreshTask;
    private BukkitTask callTargetReconcileTask;
    private GroupSynchronizer groupSynchronizer;
    private CallTargetSynchronizer callTargets;
    private CallRingtonePlayer callRingtones;
    private LofiStreamPlayer lofiStreamPlayer;
    private GroupSpeechAttenuator groupSpeechAttenuator;
    private final AtomicLong sessionSequence = new AtomicLong();
    private final Map<UUID, Long> voiceSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> restoreSessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> voiceRoutes = new ConcurrentHashMap<>();
    private final Set<UUID> applyingRestore = ConcurrentHashMap.newKeySet();

    /** Velocity backend names we've already warned about mismatching this-backend. */
    private final Set<String> homeMismatchWarned = ConcurrentHashMap.newKeySet();

    public CrabVoicechatPlugin(CrabUtilities plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.crossServerEnabled = plugin.getConfig().getBoolean("voicechat.cross-server.enabled", true);
        this.thisBackend = plugin.getConfig().getString("voicechat.cross-server.this-backend", "");
        this.lofiEnabled = plugin.getConfig().getBoolean("voicechat.lofi.enabled", true);
        this.lofiUrl = plugin.getConfig().getString("voicechat.lofi.youtube-url", "");
        this.lofiMusicVolume = (float) plugin.getConfig().getDouble("voicechat.lofi.music-volume", 0.5D);
        this.lofiPlayerVolume = plugin.getConfig().getDouble("voicechat.lofi.player-volume", 0.25D);
        // Keep the original seed so renaming the group does not invalidate
        // cross-server membership or saved auto-rejoin data.
        this.lofiGroupId = deterministicGroupId(LOFI_GROUP_ID_SEED);
        List<String> configured = plugin.getConfig().getStringList("voicechat.cross-server.persistent-groups");
        this.persistentGroupNames = persistentGroupNames(configured, lofiEnabled);
    }

    static List<String> persistentGroupNames(List<String> configured, boolean lofiEnabled) {
        List<String> names = new ArrayList<>(configured.isEmpty()
                ? List.of("Global #1", "Global #2", "Global #3")
                : configured);
        names.removeIf(name -> LOFI_GROUP_ID_SEED.equalsIgnoreCase(name)
                || LOFI_GROUP_NAME.equalsIgnoreCase(name));
        if (lofiEnabled) names.add(LOFI_GROUP_NAME);
        return List.copyOf(names);
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
        if (lofiEnabled) reg.registerEvent(StaticSoundPacketEvent.class, this::onStaticSoundPacket);
        if (crossServerEnabled || lofiEnabled) {
            reg.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
            reg.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
            reg.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup);
            reg.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnect);
        }
        if (!crossServerEnabled) return;
        reg.registerEvent(CreateGroupEvent.class, this::onCreateGroup, Integer.MIN_VALUE);
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        this.api = event.getVoicechat();
        VoiceMediaRegistry.getInstance().attach(api, lofiEnabled);
        try {
            this.callRingtones = new CallRingtonePlayer(api, logger);
        } catch (IOException e) {
            logger.warning("Call ringtones are unavailable: " + e.getMessage());
        }

        List<Group> permanentGroups = new ArrayList<>();
        for (String name : persistentGroupNames) {
            UUID id = LOFI_GROUP_NAME.equals(name) ? lofiGroupId : deterministicGroupId(name);
            Group group = api.groupBuilder()
                    .setId(id)
                    .setName(name)
                    .setType(Group.Type.OPEN)
                    .setPersistent(true)
                    .build();
            permanentGroups.add(group);
            logger.info("Created persistent voice chat group '" + name + "' (" + group.getId() + ")");
        }

        if (lofiEnabled) {
            this.groupSpeechAttenuator = new GroupSpeechAttenuator(
                    api, lofiGroupId, lofiPlayerVolume, logger);
            if (lofiUrl == null || lofiUrl.isBlank()) {
                logger.warning("voicechat.lofi.enabled=true but youtube-url is empty; lofi playback disabled");
            } else {
                this.lofiStreamPlayer = new LofiStreamPlayer(
                        api, lofiGroupId, lofiUrl, lofiMusicVolume, logger);
                lofiStreamPlayer.start();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    lofiStreamPlayer.reconcileTarget(api.getConnectionOf(player.getUniqueId()));
                }
            }
        }

        if (!crossServerEnabled) return;
        if (thisBackend == null || thisBackend.isEmpty()) {
            logger.warning("voicechat.cross-server.enabled=true but this-backend is empty in config — "
                    + "cross-server voice DISABLED (set voicechat.cross-server.this-backend to this backend's "
                    + "Velocity server name)");
            return;
        }
        startBridge(permanentGroups);
    }

    private void startBridge(List<Group> permanentGroups) {
        this.bus = new RedisVoiceBus(plugin);
        this.membership = new MembershipTracker();
        this.audioRelay = new AudioRelay(plugin, bus, membership,
                thisBackend, logger, voiceRoutes::get,
                lofiEnabled ? lofiGroupId : null, lofiPlayerVolume);
        audioRelay.setApi(api);
        this.svcPackets = new SvcPacketSender(plugin);
        this.roster = new RosterTracker(plugin, svcPackets, thisBackend, logger,
                audioRelay::invalidateSpeaker);
        this.groupSynchronizer = new GroupSynchronizer(
                plugin, api, bus, logger, this::reconcileMembership);
        this.callTargets = new CallTargetSynchronizer(
                plugin, api, bus, groupSynchronizer, thisBackend,
                voiceSessions::get, voiceRoutes::get,
                playerId -> restoreSessions.remove(playerId),
                this::scheduleMembershipReconciliation, logger);

        bus.start(audioRelay::onAudioFrame, this::onLifecycleMessage);
        permanentGroups.forEach(groupSynchronizer::seedPermanent);
        groupSynchronizer.reconcileRegistry();

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

        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> roster.sweepStaleEntries(),
                20L * 60L, 20L * 30L);

        groupReconcileTask = Bukkit.getScheduler().runTaskTimer(plugin,
                groupSynchronizer::reconcileRegistry,
                20L * 30L, 20L * 30L);

        routeRefreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                this::refreshLocalRoutes, 20L * 5L, 20L * 5L);

        callTargetReconcileTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> callTargets.reconcile(Set.copyOf(voiceSessions.keySet())),
                20L * 2L, 20L * 5L);

        logger.info("Cross-server voice bridge started (backend='" + thisBackend + "', "
                + permanentGroups.size() + " permanent groups plus synced player groups; "
                + "relying on tab-list sync for skins)");
    }

    /** Re-publish a {@code ROSTER_JOIN} for every local member of every cross-server group. */
    private void rebroadcastLocalRoster() {
        if (bus == null || membership == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            synchronized (membership) {
                // Disconnect removes the session before taking this lock. That
                // prevents an in-flight heartbeat from replaying a roster join
                // after the disconnect has published its leave.
                if (!voiceSessions.containsKey(playerId)) continue;
                UUID groupId = membership.getLocalGroupOf(playerId);
                String route = voiceRoutes.get(playerId);
                if (route == null) continue;
                if (groupId == null) {
                    if (!restoreSessions.containsKey(playerId)) {
                        bus.deletePlayerGroup(playerId, null, route,
                                groupSynchronizer::onRegistryWrite);
                    }
                    continue;
                }
                VoiceMessages.GroupDefinition group = groupSynchronizer.definition(groupId);
                if (group == null) continue;
                // Refresh the auto-rejoin TTL and the network membership lease.
                bus.writePlayerGroup(playerId, group, PLAYER_GROUP_TTL_SECONDS, route,
                        groupSynchronizer::onRegistryWrite);
                if (callTargets != null) callTargets.onMembershipReconciled(playerId, groupId);
                bus.publishRoster(VoiceMessages.encodeRosterJoin(
                        groupId, playerId, voicechatName(player), route),
                        playerId, route);
            }
        }
    }

    private void onLifecycleMessage(String message) {
        if (groupSynchronizer != null && groupSynchronizer.onLifecycleMessage(message)) return;
        VoiceMessages.CallRingStart ringStart = VoiceMessages.decodeCallRingStart(message);
        if (ringStart != null) {
            runVoiceControl(() -> {
                if (callRingtones != null) callRingtones.start(ringStart);
            });
            return;
        }
        VoiceMessages.CallRingStop ringStop = VoiceMessages.decodeCallRingStop(message);
        if (ringStop != null) {
            runVoiceControl(() -> {
                if (callRingtones != null) callRingtones.stop(ringStop);
            });
            return;
        }
        VoiceMessages.CallJoin callJoin = VoiceMessages.decodeCallJoin(message);
        if (callJoin != null) {
            if (callTargets != null) callTargets.onJoinHint(callJoin);
            return;
        }
        if (roster != null) roster.onLifecycleMessage(message);
    }

    private void runVoiceControl(Runnable control) {
        try {
            Bukkit.getScheduler().runTask(plugin, control);
        } catch (Exception ignored) {
            // Plugin is stopping.
        }
    }

    private void refreshLocalRoutes() {
        if (bus == null || voiceSessions.isEmpty()) return;
        Map<UUID, Long> sessions = Map.copyOf(voiceSessions);
        Map<UUID, String> routes = bus.fetchPlayerHomes(sessions.keySet());
        if (routes == null) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<UUID, Long> entry : sessions.entrySet()) {
                    UUID playerId = entry.getKey();
                    if (!Objects.equals(voiceSessions.get(playerId), entry.getValue())) continue;
                    String route = routes.get(playerId);
                    if (!thisBackend.equals(VoiceMessages.routeBackend(route))) {
                        voiceRoutes.remove(playerId);
                        continue;
                    }
                    String previous = voiceRoutes.put(playerId, route);
                    if (!Objects.equals(previous, route)) {
                        scheduleMembershipReconciliation(playerId);
                        if (callTargets != null) callTargets.onRouteReady(playerId);
                    }
                }
            });
        } catch (Exception ignored) {
            // Plugin is stopping.
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
        VoicechatConnection connection = event.getConnection();
        if (connection == null) return;
        if (lofiStreamPlayer != null) lofiStreamPlayer.reconcileTarget(connection);
        if (api == null || bus == null) return;
        UUID playerId = connection.getPlayer().getUuid();
        long session = sessionSequence.incrementAndGet();
        voiceSessions.put(playerId, session);
        restoreSessions.put(playerId, session);
        voiceRoutes.remove(playerId);
        if (callRingtones != null) callRingtones.removePlayer(playerId);
        if (callTargets != null) callTargets.onConnect(playerId);

        // If this player was previously heard through a relay on this backend,
        // its next-sequence stop marker resets listeners before native audio starts.
        audioRelay.onPlayerConnect(playerId);

        // Catch-up roster on the main thread — purely local sends, no
        // Redis read. Doing it eagerly (not waiting on the auto-rejoin
        // Redis round-trip) means the GUI is correct as soon as the
        // player's voice connection comes up.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                roster.onLocalConnect(playerId);
                roster.catchUpNewLocalConnection(p);
            }
        });

        scheduleRestoreAttempt(playerId, session, 0);
    }

    private void scheduleRestoreAttempt(UUID playerId, long session, int attempt) {
        if (attempt >= MAX_RESTORE_ATTEMPTS && voiceRoutes.containsKey(playerId)) {
            restoreSessions.remove(playerId, session);
            return;
        }
        Runnable read = () -> {
            if (!Objects.equals(voiceSessions.get(playerId), session)) return;
            String velocityRoute = bus.fetchPlayerHome(playerId);
            String velocityName = VoiceMessages.routeBackend(velocityRoute);
            // A previous backend is normal while a hop is being committed.
            if (attempt >= FAST_RESTORE_ATTEMPTS
                    && velocityName != null && !velocityName.equals(thisBackend)
                    && homeMismatchWarned.add(velocityName)) {
                logger.severe("voicechat.cross-server.this-backend is '" + thisBackend
                        + "' but Velocity calls this backend '" + velocityName
                        + "'. Other backends will DROP all voice frames published by "
                        + "this server — fix modules/voicechat.yml (names are case-sensitive).");
            }

            boolean restoring = Objects.equals(restoreSessions.get(playerId), session);
            RedisVoiceBus.ReadResult<String> groupRead = restoring
                    ? bus.fetchPlayerGroup(playerId)
                    : new RedisVoiceBus.ReadResult<>(true, null);
            UUID groupId = parseUuid(groupRead.value());
            VoiceMessages.GroupDefinition definition =
                    groupId == null ? null : groupSynchronizer.fetch(groupId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!Objects.equals(voiceSessions.get(playerId), session)) return;
                Player player = Bukkit.getPlayer(playerId);
                VoicechatConnection current = api.getConnectionOf(playerId);
                if (player == null || !player.isOnline() || current == null || !current.isConnected()) {
                    scheduleRestoreAttempt(playerId, session, attempt + 1);
                    return;
                }
                if (!thisBackend.equals(velocityName)) {
                    scheduleRestoreAttempt(playerId, session, attempt + 1);
                    return;
                }
                voiceRoutes.put(playerId, velocityRoute);
                if (!Objects.equals(restoreSessions.get(playerId), session)) {
                    scheduleMembershipReconciliation(playerId);
                    return;
                }
                if (!groupRead.succeeded()) {
                    scheduleRestoreAttempt(playerId, session, attempt + 1);
                    return;
                }
                if (groupId == null) {
                    restoreSessions.remove(playerId, session);
                    return;
                }

                Group group = groupSynchronizer.findLocal(groupId);
                if (group == null && definition != null) {
                    group = groupSynchronizer.apply(definition);
                }
                if (group == null) {
                    scheduleRestoreAttempt(playerId, session, attempt + 1);
                    return;
                }

                Group currentGroup = current.getGroup();
                if (currentGroup == null || !groupId.equals(currentGroup.getId())) {
                    applyingRestore.add(playerId);
                    try {
                        current.setGroup(group);
                    } catch (Exception e) {
                        logger.warning("Auto-rejoin failed for " + playerId
                                + ": " + e.getMessage());
                        scheduleRestoreAttempt(playerId, session, attempt + 1);
                        return;
                    } finally {
                        applyingRestore.remove(playerId);
                    }
                }
                UUID restoredGroupId = groupId;
                Bukkit.getScheduler().runTask(plugin,
                        () -> confirmRestore(playerId, session, restoredGroupId, attempt));
            });
        };

        try {
            if (attempt == 0) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, read);
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(
                        plugin, read, attempt < FAST_RESTORE_ATTEMPTS
                                ? FAST_RESTORE_RETRY_TICKS
                                : SLOW_RESTORE_RETRY_TICKS);
            }
        } catch (Exception ignored) {
            // Plugin is stopping.
        }
    }

    private void confirmRestore(UUID playerId, long session, UUID groupId, int attempt) {
        if (!Objects.equals(voiceSessions.get(playerId), session)
                || !Objects.equals(restoreSessions.get(playerId), session)) return;
        VoicechatConnection current = api.getConnectionOf(playerId);
        Group committed = current == null ? null : current.getGroup();
        if (committed == null || !groupId.equals(committed.getId())) {
            scheduleRestoreAttempt(playerId, session, attempt + 1);
            return;
        }
        restoreSessions.remove(playerId, session);
        logger.info("Auto-rejoined " + playerId + " to group '" + committed.getName() + "'");
        scheduleMembershipReconciliation(playerId);
    }

    private void onCreateGroup(CreateGroupEvent event) {
        if (!event.isCancelled() && event.getConnection() != null) {
            UUID playerId = event.getConnection().getPlayer().getUuid();
            restoreSessions.remove(playerId);
        }
        if (groupSynchronizer != null) groupSynchronizer.onCreateGroup(event);
    }

    private void onJoinGroup(JoinGroupEvent event) {
        if (event.getConnection() == null) return;
        UUID playerId = event.getConnection().getPlayer().getUuid();
        if (applyingRestore.contains(playerId)
                || callTargets != null && callTargets.isApplying(playerId)) return;
        restoreSessions.remove(playerId);
        if (callTargets != null) callTargets.onManualGroupChange(playerId);
        scheduleMembershipReconciliation(playerId);
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        if (event.getConnection() == null) return;
        UUID playerId = event.getConnection().getPlayer().getUuid();
        if (applyingRestore.contains(playerId)
                || callTargets != null && callTargets.isApplying(playerId)) return;
        restoreSessions.remove(playerId);
        if (callTargets != null) callTargets.onManualGroupChange(playerId);
        scheduleMembershipReconciliation(playerId);
    }

    private void scheduleMembershipReconciliation(UUID playerId) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> reconcileMembership(playerId));
        } catch (Exception ignored) {
            // Plugin is stopping.
        }
    }

    private void reconcileMembership(UUID playerId) {
        if (api == null || membership == null || bus == null) return;
        synchronized (membership) {
            // A queued join/leave reconciliation can run after SVC's
            // disconnect event. Treat the session map as the cancellation
            // token while holding the same lock used by disconnect cleanup.
            if (!voiceSessions.containsKey(playerId)) return;
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;

            VoicechatConnection connection = api.getConnectionOf(playerId);
            Group group = connection == null ? null : connection.getGroup();
            UUID groupId = group == null ? null : group.getId();

            if (lofiStreamPlayer != null && connection != null) {
                lofiStreamPlayer.updateTarget(connection, groupId);
            }

            UUID previousGroupId = membership.setLocalGroup(playerId, groupId);
            boolean changed = !Objects.equals(previousGroupId, groupId);
            String route = voiceRoutes.get(playerId);
            if (route == null) return;

            if (changed && previousGroupId != null) {
                bus.publishRoster(VoiceMessages.encodeRosterLeave(
                        previousGroupId, playerId, route), playerId, route);
            }
            if (groupId == null) {
                if (restoreSessions.containsKey(playerId)) return;
                bus.deletePlayerGroup(playerId, previousGroupId, route,
                        groupSynchronizer::onRegistryWrite);
                return;
            }

            VoiceMessages.GroupDefinition definition = groupSynchronizer.definition(groupId);
            if (definition == null) {
                logger.warning("Not publishing membership for unknown voice group " + groupId);
                return;
            }
            String name = voicechatName(player);
            bus.writePlayerGroup(playerId, definition, PLAYER_GROUP_TTL_SECONDS, route,
                    groupSynchronizer::onRegistryWrite);
            if (callTargets != null) callTargets.onMembershipReconciled(playerId, groupId);
            bus.publishRoster(VoiceMessages.encodeRosterJoin(
                    groupId, playerId, name, route), playerId, route);
            if (changed) {
                logger.info("Roster published: " + name + " (" + playerId
                        + ") joined " + groupId);
            }
        }
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

    private void onStaticSoundPacket(StaticSoundPacketEvent event) {
        if (groupSpeechAttenuator != null) groupSpeechAttenuator.onStaticSoundPacket(event);
    }

    /**
     * Runs at LOWEST Bukkit quit priority, before SVC removes the player's
     * state and group. The native N+1 stop marker is what lets listeners
     * accept the relay channel's sequence after a backend hop.
     */
    void beforePlayerQuit(UUID playerId) {
        if (audioRelay != null) audioRelay.beforePlayerQuit(playerId);
    }

    private void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        String route = voiceRoutes.get(playerId);
        voiceSessions.remove(playerId);
        restoreSessions.remove(playerId);
        applyingRestore.remove(playerId);
        if (callRingtones != null) callRingtones.removePlayer(playerId);
        if (callTargets != null) callTargets.onDisconnect(playerId);
        if (lofiStreamPlayer != null) lofiStreamPlayer.removeTarget(playerId);
        if (groupSpeechAttenuator != null) groupSpeechAttenuator.remove(playerId);
        if (audioRelay != null) audioRelay.onPlayerDisconnect(event);
        if (membership != null) {
            synchronized (membership) {
                if (bus != null) {
                    UUID groupId = membership.getLocalGroupOf(playerId);
                    if (groupId != null && route != null) {
                        bus.publishRoster(VoiceMessages.encodeRosterLeave(
                                groupId, playerId, route), playerId, route);
                    }
                    // Leave the player-group key in place — disconnects from a
                    // server hop or short relog should auto-rejoin. Its 90 s lease
                    // bounds how long a fully offline player retains membership.
                }
                membership.onPlayerDisconnect(event);
            }
        }
        voiceRoutes.remove(playerId);
    }

    /** Public hook so {@link CrabUtilities#onDisable()} can release resources. */
    public void shutdown() {
        if (callRingtones != null) callRingtones.close();
        if (callTargets != null) callTargets.close();
        if (lofiStreamPlayer != null) lofiStreamPlayer.close();
        if (groupSpeechAttenuator != null) groupSpeechAttenuator.close();
        voiceSessions.clear();
        restoreSessions.clear();
        for (BukkitTask task : new BukkitTask[]{
                rosterRebroadcastTask, sweepTask, groupReconcileTask, routeRefreshTask,
                callTargetReconcileTask}) {
            if (task != null) {
                try { task.cancel(); } catch (Exception ignored) {}
            }
        }
        // Tell other backends our local players are leaving (best-effort).
        if (bus != null && membership != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerId = player.getUniqueId();
                synchronized (membership) {
                    UUID groupId = membership.getLocalGroupOf(playerId);
                    String route = voiceRoutes.get(playerId);
                    if (groupId == null || route == null) continue;
                    bus.publishRoster(VoiceMessages.encodeRosterLeave(
                            groupId, playerId, route), playerId, route);
                }
            }
        }
        voiceRoutes.clear();
        if (groupSynchronizer != null) groupSynchronizer.shutdown();
        if (bus != null) bus.shutdown();
        if (roster != null) roster.shutdown();
        if (audioRelay != null) audioRelay.shutdown();
        VoiceMediaRegistry.getInstance().detach();
    }

    /**
     * Stable UUID derived from the group name so all backends produce
     * the same UUID without needing to coordinate.
     */
    static UUID deterministicGroupId(String name) {
        return UUID.nameUUIDFromBytes(("crabcraft:svc:global:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
