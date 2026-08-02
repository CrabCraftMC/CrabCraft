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
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import net.crabcraft.customdiscs.CDVoiceAddon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long CALL_METADATA_TTL_SECONDS = 180L;
    static final long DYNAMIC_CALL_IDLE_MS = 120_000L;
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
    private BukkitTask callReconcileTask;
    /** Shared live whitelist observed directly by AudioRelay and RosterTracker. */
    private final Set<UUID> crossServerGroupIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> persistentGroupIds = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, Long> dynamicCallLastHeartbeat = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> desiredGroupVersions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong desiredGroupSequence =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean callReconcileRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final Object callGroupCreationLock = new Object();
    private LofiStreamPlayer lofiStreamPlayer;
    private GroupSpeechAttenuator groupSpeechAttenuator;
    private CallRingtonePlayer callRingtonePlayer;

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
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        this.api = event.getVoicechat();
        CDVoiceAddon.getInstance().register(api, lofiEnabled);

        for (String name : persistentGroupNames) {
            UUID id = LOFI_GROUP_NAME.equals(name) ? lofiGroupId : deterministicGroupId(name);
            persistentGroupIds.add(id);
            crossServerGroupIds.add(id);
            Group group = api.groupBuilder()
                    .setId(id)
                    .setName(name)
                    .setType(Group.Type.OPEN)
                    .setPersistent(true)
                    .build();
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
        startBridge();
    }

    private void startBridge() {
        this.bus = new RedisVoiceBus(plugin);
        this.membership = new MembershipTracker();
        this.audioRelay = new AudioRelay(plugin, bus, membership, crossServerGroupIds,
                thisBackend, logger, lofiEnabled ? lofiGroupId : null, lofiPlayerVolume);
        audioRelay.setApi(api);
        this.svcPackets = new SvcPacketSender(plugin);
        this.roster = new RosterTracker(plugin, svcPackets,
                crossServerGroupIds, thisBackend, logger);
        try {
            this.callRingtonePlayer = new CallRingtonePlayer(api, logger);
        } catch (Exception e) {
            logger.warning("Could not load the bundled call ringtones; ringtone playback is disabled: "
                    + e.getMessage());
        }

        bus.start(audioRelay::onAudioFrame, this::onVoiceLifecycleMessage);

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
                this::sweepVoiceState,
                20L * 60L, 20L * 30L);

        // Pub/sub is deliberately backed by a short-lived durable target key.
        // Poll it so an already-connected player still joins if this backend's
        // roster subscriber was reconnecting when the accept event arrived.
        callReconcileTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::queueAcceptedCallReconciliation,
                20L * 5L, 20L * 5L);

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
                if (dynamicCallLastHeartbeat.replace(groupId, System.currentTimeMillis()) != null) {
                    bus.refreshCallMetadata(groupId, CALL_METADATA_TTL_SECONDS);
                }
            }
        }
    }

    /** Dispatch Redis lifecycle messages, including dynamic call joins. */
    private void onVoiceLifecycleMessage(String message) {
        if (message == null || message.isEmpty()) return;
        int separator = message.indexOf(VoiceMessages.SEP);
        String operation = separator < 0 ? message : message.substring(0, separator);
        if (VoiceMessages.OP_CALL_RING_START.equals(operation)) {
            VoiceMessages.CallRingStart ringStart = VoiceMessages.decodeCallRingStart(message);
            CallRingtonePlayer ringtonePlayer = callRingtonePlayer;
            if (ringStart == null || ringtonePlayer == null) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> ringtonePlayer.start(ringStart));
            } catch (Exception ignored) {
                // The plugin is shutting down.
            }
            return;
        }
        if (VoiceMessages.OP_CALL_RING_STOP.equals(operation)) {
            VoiceMessages.CallRingStop ringStop = VoiceMessages.decodeCallRingStop(message);
            CallRingtonePlayer ringtonePlayer = callRingtonePlayer;
            if (ringStop == null || ringtonePlayer == null) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> ringtonePlayer.stop(ringStop));
            } catch (Exception ignored) {
                // The plugin is shutting down.
            }
            return;
        }
        if (VoiceMessages.OP_CALL_JOIN.equals(operation)) {
            VoiceMessages.CallJoin callJoin = VoiceMessages.decodeCallJoin(message);
            if (callJoin == null) return;
            if (!activateDynamicCall(callJoin.groupId())) return;
            bus.writeCallMetadata(callJoin.groupId(), callJoin.password(),
                    CALL_METADATA_TTL_SECONDS);
            try {
                Bukkit.getScheduler().runTask(plugin,
                        () -> requestAndJoinLocalCall(callJoin, true));
            } catch (Exception ignored) {
                // The plugin is shutting down.
            }
            return;
        }

        if (VoiceMessages.OP_ROSTER_JOIN.equals(operation)) {
            VoiceMessages.RosterJoin join = VoiceMessages.decodeRosterJoin(message);
            if (join != null) {
                UUID groupId = join.groupId();
                if (!persistentGroupIds.contains(groupId)
                        && (!crossServerGroupIds.contains(groupId)
                        || !dynamicCallLastHeartbeat.containsKey(groupId))) {
                    // Recover calls missed while this backend or its roster
                    // subscriber was offline, as well as a heartbeat racing
                    // idle cleanup. The heartbeat contains no secret; Redis
                    // metadata is the authority.
                    String callPassword = bus.fetchCallMetadata(groupId);
                    if (VoiceMessages.isValidCallPassword(callPassword)) {
                        activateDynamicCall(groupId);
                    }
                }
                touchDynamicCall(groupId);
            }
        }
        roster.onLifecycleMessage(message);
    }

    private boolean activateDynamicCall(UUID groupId) {
        if (persistentGroupIds.contains(groupId)) return false;
        dynamicCallLastHeartbeat.put(groupId, System.currentTimeMillis());
        crossServerGroupIds.add(groupId);
        bus.subscribeAudio(groupId);
        return true;
    }

    private void touchDynamicCall(UUID groupId) {
        dynamicCallLastHeartbeat.computeIfPresent(groupId,
                (id, ignored) -> System.currentTimeMillis());
    }

    private void queueAcceptedCallReconciliation() {
        if (!callReconcileRunning.compareAndSet(false, true)) return;
        List<DesiredGroupCheck> checks = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            checks.add(new DesiredGroupCheck(playerId, currentDesiredGroup(playerId)));
        }
        if (checks.isEmpty()) {
            callReconcileRunning.set(false);
            return;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<RecoveredCall> recovered = new ArrayList<>();
                try {
                    java.util.Map<UUID, String> targets = bus.fetchCallTargets(
                            checks.stream().map(DesiredGroupCheck::playerId).toList());
                    for (DesiredGroupCheck check : checks) {
                        if (!isCurrentDesiredGroup(check.playerId(), check.version())) continue;
                        String groupValue = targets.get(check.playerId());
                        if (groupValue == null) continue;
                        UUID groupId;
                        try {
                            groupId = UUID.fromString(groupValue);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        String password = bus.fetchCallMetadata(groupId);
                        if (!VoiceMessages.isValidCallPassword(password)) continue;
                        recovered.add(new RecoveredCall(
                                new VoiceMessages.CallJoin(groupId, check.playerId(), password),
                                check.version()));
                    }
                    if (!recovered.isEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            for (RecoveredCall call : recovered) {
                                if (!isCurrentDesiredGroup(
                                        call.join().playerId(), call.version())) continue;
                                activateDynamicCall(call.join().groupId());
                                requestAndJoinLocalCall(call.join(), false);
                            }
                        });
                    }
                } finally {
                    callReconcileRunning.set(false);
                }
            });
        } catch (Exception e) {
            callReconcileRunning.set(false);
        }
    }

    private void requestAndJoinLocalCall(VoiceMessages.CallJoin callJoin,
                                         boolean notifyTransientFailure) {
        Player player = Bukkit.getPlayer(callJoin.playerId());
        if (player == null || !player.isOnline()) return;
        long version = requestDesiredGroup(callJoin.playerId());
        joinLocalCall(callJoin, notifyTransientFailure, version);
    }

    private long requestDesiredGroup(UUID playerId) {
        long version = desiredGroupSequence.incrementAndGet();
        desiredGroupVersions.put(playerId, version);
        return version;
    }

    private long currentDesiredGroup(UUID playerId) {
        return desiredGroupVersions.computeIfAbsent(playerId,
                ignored -> desiredGroupSequence.incrementAndGet());
    }

    private boolean isCurrentDesiredGroup(UUID playerId, long version) {
        return desiredGroupVersions.getOrDefault(playerId, -1L) == version;
    }

    /** Only the backend which currently owns the requested player joins them. */
    private void joinLocalCall(VoiceMessages.CallJoin callJoin,
                               boolean notifyTransientFailure, long version) {
        if (!isCurrentDesiredGroup(callJoin.playerId(), version)) return;
        Player player = Bukkit.getPlayer(callJoin.playerId());
        if (player == null || !player.isOnline()) return;
        if (!groupsEnabled()) {
            sendCallJoinFailure(player, "Voice calls are unavailable because voice-chat groups are disabled.");
            clearAcceptedCall(callJoin.playerId(), callJoin.groupId());
            return;
        }

        VoicechatConnection connection = api.getConnectionOf(callJoin.playerId());
        if (connection == null) {
            // Keep the short-lived mapping: a pending PlayerConnectedEvent
            // can still complete this accepted join.
            if (notifyTransientFailure) {
                sendCallJoinFailure(player,
                        "Could not join the call because Simple Voice Chat is not connected.");
            }
            return;
        }
        if (!connection.isInstalled()) {
            sendCallJoinFailure(player, "Could not join the call because Simple Voice Chat is not installed.");
            clearAcceptedCall(callJoin.playerId(), callJoin.groupId());
            return;
        }
        if (!connection.isConnected()) {
            if (notifyTransientFailure) {
                sendCallJoinFailure(player,
                        "Could not join the call because Simple Voice Chat is not connected.");
            }
            return;
        }

        Group current = connection.getGroup();
        if (current != null && current.getId().equals(callJoin.groupId())) {
            bus.deleteCallTarget(callJoin.playerId(), callJoin.groupId());
            return;
        }

        Group group = ensureCallGroup(callJoin.groupId(), callJoin.password());
        if (group == null) {
            sendCallJoinFailure(player, "Could not create the private voice call.");
            clearAcceptedCall(callJoin.playerId(), callJoin.groupId());
            return;
        }
        try {
            connection.setGroup(group);
            bus.deleteCallTarget(callJoin.playerId(), callJoin.groupId());
            player.sendMessage(Component.text("Connected to the private voice call.",
                    NamedTextColor.GREEN));
        } catch (Exception e) {
            sendCallJoinFailure(player, "Could not join the private voice call.");
            clearAcceptedCall(callJoin.playerId(), callJoin.groupId());
            logger.warning("Dynamic call join failed for player " + callJoin.playerId()
                    + " in group " + callJoin.groupId());
        }
    }

    private void clearAcceptedCall(UUID playerId, UUID groupId) {
        bus.deletePlayerGroup(playerId, groupId);
        bus.deleteCallTarget(playerId, groupId);
    }

    private record DesiredGroupCheck(UUID playerId, long version) {}

    private record RecoveredCall(VoiceMessages.CallJoin join, long version) {}

    private boolean groupsEnabled() {
        return api != null && api.getServerConfig().getBoolean("enable_groups", true);
    }

    private void sendCallJoinFailure(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private Group ensureCallGroup(UUID groupId, String callPassword) {
        synchronized (callGroupCreationLock) {
            Group existing = api.getGroup(groupId);
            if (existing != null) {
                if (existing.hasPassword() && existing.isHidden()
                        && !existing.isPersistent() && existing.getType() == Group.Type.OPEN) {
                    return existing;
                }
                logger.warning("Refusing incompatible existing voice group for call " + groupId);
                return null;
            }
            try {
                return buildCallGroup(api, groupId, callPassword);
            } catch (Exception e) {
                // Never include the builder exception: implementations may
                // echo the rejected password in their diagnostic text.
                logger.warning("Failed to create dynamic call group " + groupId);
                return null;
            }
        }
    }

    static Group buildCallGroup(VoicechatServerApi api, UUID groupId, String callPassword) {
        return api.groupBuilder()
                .setId(groupId)
                .setName(callGroupName(groupId))
                .setPassword(callPassword)
                .setType(Group.Type.OPEN)
                .setHidden(true)
                .setPersistent(false)
                .build();
    }

    static String callGroupName(UUID groupId) {
        return "Call " + groupId.toString().substring(0, 8);
    }

    private void sweepVoiceState() {
        roster.sweepStaleEntries();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<UUID, Long> entry : dynamicCallLastHeartbeat.entrySet()) {
            UUID groupId = entry.getKey();
            Long lastHeartbeat = entry.getValue();
            if (now - lastHeartbeat <= DYNAMIC_CALL_IDLE_MS
                    || !dynamicCallLastHeartbeat.remove(groupId, lastHeartbeat)) continue;

            crossServerGroupIds.remove(groupId);
            bus.unsubscribeAudio(groupId);
            // Close the small window where a fresh CALL_JOIN can arrive
            // between removal from the timestamp map and removal from the
            // two live whitelists.
            if (dynamicCallLastHeartbeat.containsKey(groupId)) {
                crossServerGroupIds.add(groupId);
                bus.subscribeAudio(groupId);
                continue;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // A fresh CALL_JOIN may have reactivated the same ID while
                    // this main-thread cleanup was queued.
                    if (dynamicCallLastHeartbeat.containsKey(groupId)) return;
                    roster.clearGroup(groupId);
                });
            } catch (Exception ignored) {
                // The plugin is shutting down.
            }
            logger.info("Released inactive dynamic voice call " + groupId);
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
        VoicechatConnection conn = event.getConnection();
        if (conn == null) return;
        if (lofiStreamPlayer != null) lofiStreamPlayer.reconcileTarget(conn);
        if (api == null || bus == null) return;
        UUID playerId = conn.getPlayer().getUuid();
        long desiredVersion = requestDesiredGroup(playerId);

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

        // Read Redis on a worker thread, then route the group change back
        // through the server's main scheduler.
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
            String callPassword = null;
            if (!crossServerGroupIds.contains(groupId)
                    || (dynamicCallLastHeartbeat.containsKey(groupId) && api.getGroup(groupId) == null)) {
                callPassword = bus.fetchCallMetadata(groupId);
                if (!VoiceMessages.isValidCallPassword(callPassword)) return;
                if (!activateDynamicCall(groupId)) return;
            }

            String recoveredCallPassword = callPassword;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> autoRejoin(
                        playerId, groupId, recoveredCallPassword, desiredVersion));
            } catch (Exception e) {
                // The plugin is shutting down.
            }
        });
    }

    private void autoRejoin(UUID playerId, UUID groupId, String callPassword,
                            long desiredVersion) {
        if (!isCurrentDesiredGroup(playerId, desiredVersion)) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        if (!groupsEnabled()) {
            sendCallJoinFailure(player, "Could not rejoin the call because voice-chat groups are disabled.");
            clearAcceptedCall(playerId, groupId);
            return;
        }
        // The event connection is a snapshot and the Redis lookup happened
        // asynchronously, so always resolve the current connection again.
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection == null) {
            sendCallJoinFailure(player, "Could not rejoin the call because Simple Voice Chat is not connected.");
            return;
        }
        if (!connection.isInstalled()) {
            sendCallJoinFailure(player, "Could not rejoin the call because Simple Voice Chat is not installed.");
            clearAcceptedCall(playerId, groupId);
            return;
        }
        if (!connection.isConnected()) {
            sendCallJoinFailure(player, "Could not rejoin the call because Simple Voice Chat is not connected.");
            return;
        }

        Group group = api.getGroup(groupId);
        if (group == null && callPassword != null) {
            group = ensureCallGroup(groupId, callPassword);
        }
        if (group == null) {
            sendCallJoinFailure(player, "Could not restore the private voice call.");
            clearAcceptedCall(playerId, groupId);
            return;
        }

        // setGroup fires JoinGroupEvent which records local membership and
        // publishes the next roster heartbeat.
        try {
            if (!isCurrentDesiredGroup(playerId, desiredVersion)) return;
            connection.setGroup(group);
            logger.info("Auto-rejoined " + playerId + " to voice group " + groupId);
        } catch (Exception e) {
            sendCallJoinFailure(player, "Could not rejoin the private voice call.");
            clearAcceptedCall(playerId, groupId);
            logger.warning("Auto-rejoin failed for " + playerId + " in group " + groupId);
        }
    }

    private void onJoinGroup(JoinGroupEvent event) {
        Group group = event.getGroup();
        if (lofiStreamPlayer != null && event.getConnection() != null) {
            lofiStreamPlayer.updateTarget(
                    event.getConnection(), group == null ? null : group.getId());
        }
        if (bus != null && event.getConnection() != null) {
            // A real group change wins over any Redis lookup or durable call
            // reconciliation which was already in flight for this player.
            requestDesiredGroup(event.getConnection().getPlayer().getUuid());
        }
        if (membership == null) return;
        if (group == null || event.getConnection() == null) return;

        membership.onJoinGroupEvent(event);

        if (!crossServerGroupIds.contains(group.getId())) return;

        UUID playerId = event.getConnection().getPlayer().getUuid();
        UUID groupId = group.getId();
        if (dynamicCallLastHeartbeat.replace(groupId, System.currentTimeMillis()) != null) {
            bus.refreshCallMetadata(groupId, CALL_METADATA_TTL_SECONDS);
            bus.deleteCallTarget(playerId, groupId);
        }

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
        if (lofiStreamPlayer != null && event.getConnection() != null) {
            lofiStreamPlayer.updateTarget(event.getConnection(), null);
        }
        if (bus != null && event.getConnection() != null) {
            requestDesiredGroup(event.getConnection().getPlayer().getUuid());
        }
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
                    bus.deletePlayerGroup(playerId, groupId);
                    bus.deleteCallTarget(playerId, groupId);
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

    private void onStaticSoundPacket(StaticSoundPacketEvent event) {
        if (groupSpeechAttenuator != null) groupSpeechAttenuator.onStaticSoundPacket(event);
    }

    private void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        desiredGroupVersions.remove(playerId);
        if (callRingtonePlayer != null) callRingtonePlayer.removePlayer(playerId);
        if (lofiStreamPlayer != null) lofiStreamPlayer.removeTarget(playerId);
        if (groupSpeechAttenuator != null) groupSpeechAttenuator.remove(playerId);
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
        if (callRingtonePlayer != null) callRingtonePlayer.close();
        if (lofiStreamPlayer != null) lofiStreamPlayer.close();
        if (groupSpeechAttenuator != null) groupSpeechAttenuator.close();
        for (BukkitTask task : new BukkitTask[]{
                rosterRebroadcastTask, sweepTask, callReconcileTask}) {
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
        CDVoiceAddon.getInstance().clear();
    }

    /**
     * Stable UUID derived from the group name so all backends produce
     * the same UUID without needing to coordinate.
     */
    static UUID deterministicGroupId(String name) {
        return UUID.nameUUIDFromBytes(("crabcraft:svc:global:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
