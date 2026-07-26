package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Keeps Redis group definitions and SVC's local group manager converged.
 * Replicas are locally persistent; Redis membership decides when a
 * non-permanent group is removed network-wide.
 */
final class GroupSynchronizer {

    private final CrabUtilities plugin;
    private final VoicechatServerApi api;
    private final RedisVoiceBus bus;
    private final Logger logger;
    private final Consumer<UUID> reconcileCreator;
    private final Map<UUID, VoiceMessages.GroupDefinition> known = new ConcurrentHashMap<>();
    private final Set<UUID> applying = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingWrites = ConcurrentHashMap.newKeySet();
    private final AtomicLong registryRevision = new AtomicLong();
    private final ExecutorService registryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CrabUtilities-VoiceGroups");
        thread.setDaemon(true);
        return thread;
    });

    GroupSynchronizer(CrabUtilities plugin, VoicechatServerApi api, RedisVoiceBus bus,
                      Logger logger, Consumer<UUID> reconcileCreator) {
        this.plugin = plugin;
        this.api = api;
        this.bus = bus;
        this.logger = logger;
        this.reconcileCreator = reconcileCreator;
    }

    void seedPermanent(Group group) {
        VoiceMessages.GroupDefinition definition = definitionOf(group, null, true);
        known.put(group.getId(), definition);
        persist(definition);
    }

    void onCreateGroup(CreateGroupEvent event) {
        Group group = event.getGroup();
        if (event.isCancelled() || group == null || applying.contains(group.getId())) return;

        String password;
        try {
            password = passwordOf(group);
        } catch (ReflectiveOperationException e) {
            event.cancel();
            warnUnsupportedPasswordGroup(event, group);
            return;
        }

        VoiceMessages.GroupDefinition definition =
                definitionOf(group, password, group.isPersistent());
        UUID creator = event.getConnection() == null
                ? null : event.getConnection().getPlayer().getUuid();

        // CreateGroupEvent is a cancellable pre-event. Apply only after SVC
        // has committed the group and any later event listener has run.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || findLocal(definition.id()) == null) return;
            if (apply(definition) == null) return;
            if (creator == null) {
                persist(definition);
            } else {
                reconcileCreator.accept(creator);
            }
        });
    }

    boolean onLifecycleMessage(String message) {
        UUID groupId = VoiceMessages.decodeGroupChanged(message);
        if (groupId == null) return false;
        refresh(groupId);
        return true;
    }

    void reconcileRegistry() {
        bus.pruneGroups();
        submitRegistryRead(() -> {
            long revision = registryRevision.get();
            Map<UUID, VoiceMessages.GroupDefinition> definitions = bus.fetchGroups();
            if (definitions == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (revision != registryRevision.get()) return;
                definitions.values().forEach(this::apply);
                for (Map.Entry<UUID, VoiceMessages.GroupDefinition> entry
                        : Map.copyOf(known).entrySet()) {
                    if (definitions.containsKey(entry.getKey())) continue;
                    VoiceMessages.GroupDefinition local = entry.getValue();
                    if (local.permanent() || pendingWrites.contains(entry.getKey())) {
                        persist(local);
                    } else {
                        removeLocal(entry.getKey());
                    }
                }
            });
        });
    }

    VoiceMessages.GroupDefinition fetch(UUID groupId) {
        return bus.fetchGroup(groupId);
    }

    VoiceMessages.GroupDefinition definition(UUID groupId) {
        return known.get(groupId);
    }

    void onRegistryWrite(boolean definitionChanged) {
        if (definitionChanged) registryRevision.incrementAndGet();
    }

    Group findLocal(UUID groupId) {
        for (Group group : api.getGroups()) {
            if (groupId.equals(group.getId())) return group;
        }
        return null;
    }

    Group apply(VoiceMessages.GroupDefinition definition) {
        VoiceMessages.GroupDefinition current = known.get(definition.id());
        Group existing = findLocal(definition.id());
        if (definition.equals(current) && existing != null && existing.isPersistent()) {
            return existing;
        }

        applying.add(definition.id());
        try {
            api.groupBuilder()
                    .setId(definition.id())
                    .setName(definition.name())
                    .setPassword(definition.password())
                    .setType(definition.type())
                    .setHidden(definition.hidden())
                    .setPersistent(true)
                    .build();
            Group group = findLocal(definition.id());
            if (group == null || !group.isPersistent()) {
                logger.warning("Could not install synced voice group '" + definition.name()
                        + "' (" + definition.id() + ")");
                return null;
            }
            known.put(definition.id(), definition);
            return group;
        } finally {
            applying.remove(definition.id());
        }
    }

    private void refresh(UUID groupId) {
        submitRegistryRead(() -> {
            long revision = registryRevision.get();
            VoiceMessages.GroupDefinition definition = bus.fetchGroup(groupId);
            if (definition == null) {
                Map<UUID, VoiceMessages.GroupDefinition> snapshot = bus.fetchGroups();
                if (snapshot == null) return;
                definition = snapshot.get(groupId);
            }
            VoiceMessages.GroupDefinition fetched = definition;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (revision != registryRevision.get()) return;
                if (fetched != null) {
                    apply(fetched);
                    return;
                }
                VoiceMessages.GroupDefinition local = known.get(groupId);
                if (local != null && (local.permanent() || pendingWrites.contains(groupId))) {
                    persist(local);
                    return;
                }
                removeLocal(groupId);
            });
        });
    }

    private void persist(VoiceMessages.GroupDefinition definition) {
        pendingWrites.add(definition.id());
        bus.upsertGroup(definition, succeeded -> {
            if (succeeded) {
                registryRevision.incrementAndGet();
                pendingWrites.remove(definition.id());
            }
        });
    }

    private void submitRegistryRead(Runnable task) {
        try {
            registryExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Plugin is stopping.
        }
    }

    void shutdown() {
        registryExecutor.shutdownNow();
    }

    private void removeLocal(UUID groupId) {
        if (findLocal(groupId) == null || api.removeGroup(groupId)) {
            known.remove(groupId);
        }
    }

    static String passwordOf(Group group) throws ReflectiveOperationException {
        if (!group.hasPassword()) return null;
        Method getInternalGroup = group.getClass().getMethod("getGroup");
        Object internalGroup = getInternalGroup.invoke(group);
        if (internalGroup == null) throw new ReflectiveOperationException("missing internal group");
        Method getPassword = internalGroup.getClass().getMethod("getPassword");
        Object password = getPassword.invoke(internalGroup);
        if (password instanceof String value) return value;
        throw new ReflectiveOperationException("missing group password");
    }

    private static VoiceMessages.GroupDefinition definitionOf(
            Group group, String password, boolean permanent) {
        return new VoiceMessages.GroupDefinition(group.getId(), group.getName(), password,
                group.getType(), group.isHidden(), permanent);
    }

    private void warnUnsupportedPasswordGroup(CreateGroupEvent event, Group group) {
        logger.severe("Blocked password-protected voice group '" + group.getName()
                + "': this Simple Voice Chat runtime does not expose the password needed "
                + "to secure replicas on every backend");
        if (event.getConnection() == null) return;
        Object player = event.getConnection().getPlayer().getPlayer();
        if (player instanceof Player bukkitPlayer) {
            bukkitPlayer.sendMessage("Could not create that voice group because its password "
                    + "could not be secured across servers. Please contact an administrator.");
        }
    }

}
