package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    private GroupMirror groupMirror;
    private MembershipTracker membership;
    private AudioRelay audioRelay;

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
        reg.registerEvent(CreateGroupEvent.class, this::onCreateGroup);
        reg.registerEvent(RemoveGroupEvent.class, this::onRemoveGroup);
        reg.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        reg.registerEvent(LeaveGroupEvent.class, this::onLeaveGroup);
        reg.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        reg.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnect);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();

        // Create persistent open groups with deterministic UUIDs FIRST,
        // before any cross-server publishing is wired up. Every backend
        // creates the same UUIDs so the groups appear in every player's
        // GUI list without needing Redis sync.
        for (String name : persistentGroupNames) {
            UUID id = deterministicGroupId(name);
            Group group = api.groupBuilder()
                    .setId(id)
                    .setName(name)
                    .setType(Group.Type.OPEN)
                    .setPersistent(true)
                    .build();
            logger.info("Created persistent voice chat group '" + name + "' (" + group.getId() + ")");
        }

        // Now start the bridge — only user-created GUI groups go through
        // the registry from this point on.
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
        this.membership = new MembershipTracker(bus, thisBackend);
        this.groupMirror = new GroupMirror(bus, thisBackend, logger);
        this.audioRelay = new AudioRelay(plugin, bus, membership, thisBackend, logger);

        groupMirror.setApi(api);
        audioRelay.setApi(api);

        // When local members of a group change, we may need to subscribe
        // or unsubscribe from that group's audio channel.
        membership.setOnGroupChanged(this::syncAudioSubscription);

        boolean ok = bus.start(this::handleLifecycle, audioRelay::onAudioFrame);
        if (!ok) {
            logger.warning("Voice bridge Redis connection failed — cross-server voice DISABLED");
            this.bus = null;
            return;
        }

        // Pull the current group registry so we know about any groups
        // that were created by other backends while we were offline.
        Map<String, String> registry = bus.fetchGroupsRegistry();
        for (Map.Entry<String, String> entry : registry.entrySet()) {
            try {
                handleLifecycle(entry.getValue());
            } catch (Exception ignored) {
                // best-effort
            }
        }

        audioRelay.start();
        logger.info("Cross-server voice bridge started (backend='" + thisBackend + "', " +
                registry.size() + " mirrored groups)");
    }

    private void handleLifecycle(String message) {
        if (message == null) return;
        String[] parts = message.split(VoiceMessages.SEP, -1);
        if (parts.length == 0) return;
        switch (parts[0]) {
            case VoiceMessages.OP_GROUP_CREATE -> {
                if (parts.length < 6) return;
                UUID id = parseUuid(parts[1]);
                if (id == null) return;
                String name = parts[2];
                String password = parts[3].isEmpty() ? null : parts[3];
                Group.Type type = VoiceMessages.typeFromString(parts[4]);
                if (groupMirror != null) groupMirror.applyCreate(id, name, password, type);
            }
            case VoiceMessages.OP_GROUP_REMOVE -> {
                if (parts.length < 3) return;
                UUID id = parseUuid(parts[1]);
                if (id == null) return;
                if (groupMirror != null) groupMirror.applyRemove(id);
            }
            case VoiceMessages.OP_MEMBER_JOIN -> {
                if (parts.length < 4) return;
                UUID gid = parseUuid(parts[1]);
                UUID pid = parseUuid(parts[2]);
                if (gid == null || pid == null) return;
                if (membership != null) membership.applyMemberJoin(gid, pid, parts[3]);
            }
            case VoiceMessages.OP_MEMBER_LEAVE -> {
                if (parts.length < 4) return;
                UUID gid = parseUuid(parts[1]);
                UUID pid = parseUuid(parts[2]);
                if (gid == null || pid == null) return;
                if (membership != null) membership.applyMemberLeave(gid, pid);
            }
            case VoiceMessages.OP_SPEAKER_LEFT -> {
                if (parts.length < 3) return;
                UUID pid = parseUuid(parts[1]);
                if (pid == null) return;
                if (audioRelay != null) audioRelay.invalidateSpeaker(pid);
            }
            default -> {}
        }
    }

    private void syncAudioSubscription(UUID groupId) {
        if (bus == null || membership == null) return;
        if (membership.getLocalMembers(groupId).isEmpty()) {
            bus.unsubscribeAudio(groupId);
        } else {
            bus.subscribeAudio(groupId);
        }
    }

    private void onCreateGroup(CreateGroupEvent event) {
        if (groupMirror != null) groupMirror.onCreateGroupEvent(event);
    }

    private void onRemoveGroup(RemoveGroupEvent event) {
        if (groupMirror != null) groupMirror.onRemoveGroupEvent(event);
    }

    private void onJoinGroup(JoinGroupEvent event) {
        if (membership != null) membership.onJoinGroupEvent(event);
    }

    private void onLeaveGroup(LeaveGroupEvent event) {
        if (membership != null) membership.onLeaveGroupEvent(event);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (audioRelay != null) audioRelay.onMicrophonePacketEvent(event);
    }

    private void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        if (membership != null) membership.onPlayerDisconnect(event);
        if (audioRelay != null) audioRelay.onPlayerDisconnect(event);
    }

    /** Public hook so {@link CrabUtilities#onDisable()} can release resources. */
    public void shutdown() {
        if (audioRelay != null) audioRelay.shutdown();
        if (bus != null) bus.shutdown();
    }

    /**
     * Stable UUID derived from the group name so all backends produce
     * the same UUID for "Global #1" without needing to coordinate.
     */
    static UUID deterministicGroupId(String name) {
        return UUID.nameUUIDFromBytes(("crabcraft:svc:global:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
