package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
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
import java.util.logging.Logger;

public class CrabVoicechatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "crabutilities";

    private final CrabUtilities plugin;
    private final Logger logger;

    private final boolean crossServerEnabled;
    private final String thisBackend;
    private final List<String> persistentGroupNames;

    private RedisVoiceBus bus;
    private MembershipTracker membership;
    private AudioRelay audioRelay;
    private RosterTracker roster;
    private SvcPacketSender svcPackets;
    private SkinSyncer skinSyncer;
    private BukkitTask heartbeatTask;
    private Set<UUID> crossServerGroupIds = Set.of();

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
        reg.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        reg.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup);
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        reg.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnect);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();

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
        startBridge(api);
    }

    private void startBridge(VoicechatServerApi api) {
        this.bus = new RedisVoiceBus(plugin);
        this.membership = new MembershipTracker();
        this.audioRelay = new AudioRelay(plugin, bus, membership, crossServerGroupIds,
                thisBackend, logger);
        audioRelay.setApi(api);
        this.svcPackets = new SvcPacketSender(plugin);
        this.skinSyncer = new SkinSyncer(plugin);
        this.roster = new RosterTracker(plugin, membership, svcPackets, skinSyncer,
                crossServerGroupIds, thisBackend, logger);

        boolean ok = bus.start(audioRelay::onAudioFrame, roster::onLifecycleMessage);
        if (!ok) {
            logger.warning("Voice bridge Redis connection failed — cross-server voice DISABLED");
            this.bus = null;
            return;
        }

        for (UUID id : crossServerGroupIds) {
            bus.subscribeAudio(id);
        }

        audioRelay.start();

        // Heartbeat: every 30s broadcast our current local roster so other
        // backends can reconcile and drop stale entries from us if we crash.
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> bus.publishRoster(roster.buildOurHeartbeat()),
                20L * 30L, 20L * 30L);

        logger.info("Cross-server voice bridge started (backend='" + thisBackend + "', "
                + crossServerGroupIds.size() + " groups, skins="
                + (skinSyncer.isAvailable() ? "real" : "default") + ")");
    }

    private void onJoinGroup(JoinGroupEvent event) {
        if (membership == null) return;
        Group group = event.getGroup();
        if (group == null || event.getConnection() == null) return;

        membership.onJoinGroupEvent(event);

        if (!crossServerGroupIds.contains(group.getId())) return;

        UUID playerId = event.getConnection().getPlayer().getUuid();
        Player bukkitPlayer = Bukkit.getPlayer(playerId);
        if (bukkitPlayer == null) return;

        // 1) Tell other backends we have a new local member of this group.
        ProfileCodec.Snapshot snapshot = ProfileCodec.capture(bukkitPlayer);
        bus.publishRoster(VoiceMessages.encodeRosterJoin(group.getId(), playerId,
                thisBackend, ProfileCodec.encode(snapshot)));

        // 2) Catch the new joiner up with the existing remote roster on
        //    the next tick (event currently runs on a non-main thread).
        Bukkit.getScheduler().runTask(plugin,
                () -> roster.catchUpNewLocalJoiner(group.getId(), bukkitPlayer));
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        if (membership == null) return;
        Group group = event.getGroup();
        if (group != null && event.getConnection() != null
                && crossServerGroupIds.contains(group.getId())) {
            UUID playerId = event.getConnection().getPlayer().getUuid();
            bus.publishRoster(VoiceMessages.encodeRosterLeave(
                    group.getId(), playerId, thisBackend));
        }
        membership.onLeaveGroupEvent(event);
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
        }
        if (membership != null) membership.onPlayerDisconnect(event);
        if (audioRelay != null) audioRelay.onPlayerDisconnect(event);
    }

    /** Public hook so {@link CrabUtilities#onDisable()} can release resources. */
    public void shutdown() {
        if (heartbeatTask != null) {
            try { heartbeatTask.cancel(); } catch (Exception ignored) {}
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
