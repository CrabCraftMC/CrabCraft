package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.RemoveGroupEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Mirrors group lifecycle across backends.
 *
 * <p>When a player creates a group via the SVC GUI on this backend, we
 * publish {@code GROUP_CREATE} on Redis. Every other backend's listener
 * recreates the group locally with the SAME UUID and {@code persistent=true}
 * via {@link VoicechatServerApi#groupBuilder()}, so it appears in their
 * native group GUI list.
 *
 * <p>The mirror creation itself fires another {@link CreateGroupEvent} on
 * the receiving backend (via {@code GroupImpl.build()} → {@code
 * ServerGroupManager.addGroup} → {@code PluginManager.onCreateGroup}). To
 * avoid an infinite re-broadcast loop, we suppress events whose group ID
 * is in {@link #mirroringInProgress}, AND we ignore events with a null
 * connection (mirrored groups have no originating player).
 */
class GroupMirror {

    private final RedisVoiceBus bus;
    private final String thisBackend;
    private final Logger logger;
    private VoicechatServerApi api;

    /** UUIDs we're currently mirroring locally — don't echo their CreateGroupEvent. */
    private final Set<UUID> mirroringInProgress = ConcurrentHashMap.newKeySet();

    GroupMirror(RedisVoiceBus bus, String thisBackend, Logger logger) {
        this.bus = bus;
        this.thisBackend = thisBackend;
        this.logger = logger;
    }

    void setApi(VoicechatServerApi api) {
        this.api = api;
    }

    /** Returns the originator backend if known, else this backend. */
    String getOriginator(UUID groupId) {
        // Future: read from the registry hash. For now, the originator field
        // is informational — every backend treats the group identically.
        return thisBackend;
    }

    /** Should we suppress an in-process CreateGroupEvent for this UUID? */
    boolean isLocalMirror(UUID groupId) {
        return mirroringInProgress.contains(groupId);
    }

    void onCreateGroupEvent(CreateGroupEvent event) {
        Group group = event.getGroup();
        if (group == null) return;
        UUID id = group.getId();
        if (mirroringInProgress.contains(id)) return;
        // Mirrored groups have no originating player.
        if (event.getConnection() == null) return;
        // SVC's API exposes hasPassword() but not getPassword(), so we can't
        // safely mirror a password-protected group — the mirror would have
        // no password and other backends could join without authentication.
        // Leave password-protected groups local-only.
        if (group.hasPassword()) {
            logger.info("Group '" + group.getName() + "' has a password — skipping cross-server mirror");
            return;
        }
        publishCreate(group);
    }

    void onRemoveGroupEvent(RemoveGroupEvent event) {
        Group group = event.getGroup();
        if (group == null) return;
        if (mirroringInProgress.contains(group.getId())) return;
        bus.publishLifecycle(VoiceMessages.encodeGroupRemove(group.getId(), thisBackend));
        bus.deleteGroupRegistry(group.getId());
    }

    /** Publish a group-create for a locally-created group, and write the registry entry. */
    void publishCreate(Group group) {
        String encoded = VoiceMessages.encodeGroupCreate(
                group.getId(),
                group.getName(),
                null,
                group.getType(),
                thisBackend);
        bus.publishLifecycle(encoded);
        bus.writeGroupRegistry(group.getId(), encoded);
    }

    /**
     * Apply a {@code GROUP_CREATE} that arrived from another backend.
     * Idempotent — if the group already exists locally with the same UUID
     * (e.g. it's a deterministic-UUID persistent group), the SVC builder
     * will overwrite it cleanly.
     */
    void applyCreate(UUID id, String name, String password, Group.Type type) {
        if (api == null) return;
        if (api.getGroup(id) != null) return;
        mirroringInProgress.add(id);
        try {
            Group.Builder builder = api.groupBuilder()
                    .setId(id)
                    .setName(name)
                    .setType(type)
                    .setPersistent(true);
            if (password != null && !password.isEmpty()) {
                builder.setPassword(password);
            }
            builder.build();
            logger.fine(() -> "Mirrored group " + name + " (" + id + ") from another backend");
        } catch (Exception e) {
            logger.warning("Failed to mirror group " + id + ": " + e.getMessage());
        } finally {
            mirroringInProgress.remove(id);
        }
    }

    void applyRemove(UUID id) {
        if (api == null) return;
        if (api.getGroup(id) == null) return;
        mirroringInProgress.add(id);
        try {
            api.removeGroup(id);
        } catch (Exception e) {
            logger.warning("Failed to remove mirrored group " + id + ": " + e.getMessage());
        } finally {
            mirroringInProgress.remove(id);
        }
    }
}
