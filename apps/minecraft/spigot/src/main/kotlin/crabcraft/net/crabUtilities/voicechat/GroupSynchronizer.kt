package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.CreateGroupEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.logging.Logger

/** Keeps Redis group definitions and SVC's local group manager converged. */
class GroupSynchronizer(private val plugin: CrabUtilities, private val api: VoicechatServerApi, private val bus: RedisVoiceBus,
                        private val logger: Logger, private val reconcileCreator: Consumer<UUID>) {
    private val known = ConcurrentHashMap<UUID, VoiceMessages.GroupDefinition>()
    private val applying = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingWrites = ConcurrentHashMap.newKeySet<UUID>()
    private val registryRevision = AtomicLong()
    private val registryExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "CrabUtilities-VoiceGroups").apply { isDaemon = true } }

    fun seedPermanent(group: Group) {
        val definition = definitionOf(group, null, true)
        known[group.getId()] = definition
        persist(definition)
    }

    fun onCreateGroup(event: CreateGroupEvent) {
        val group = event.getGroup()
        if (event.isCancelled() || group == null || applying.contains(group.getId())) return
        val password = try { passwordOf(group) } catch (e: ReflectiveOperationException) {
            event.cancel()
            warnUnsupportedPasswordGroup(event, group)
            return
        }
        val definition = definitionOf(group, password, group.isPersistent())
        val creator = event.getConnection()?.getPlayer()?.getUuid()
        // CreateGroupEvent is a cancellable pre-event; wait for SVC to commit it.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (event.isCancelled() || findLocal(definition.id()) == null) return@Runnable
            if (apply(definition) == null) return@Runnable
            if (creator == null) persist(definition) else reconcileCreator.accept(creator)
        })
    }

    fun onLifecycleMessage(message: String): Boolean {
        val groupId = VoiceMessages.decodeGroupChanged(message) ?: return false
        refresh(groupId)
        return true
    }

    fun reconcileRegistry() {
        bus.pruneGroups()
        submitRegistryRead {
            val revision = registryRevision.get()
            val definitions = bus.fetchGroups() ?: return@submitRegistryRead
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (revision != registryRevision.get()) return@Runnable
                definitions.values.forEach { apply(it) }
                for ((id, local) in java.util.Map.copyOf(known)) {
                    if (definitions.containsKey(id)) continue
                    if (local.permanent() || pendingWrites.contains(id)) persist(local) else removeLocal(id)
                }
            })
        }
    }

    fun fetch(groupId: UUID): VoiceMessages.GroupDefinition? = bus.fetchGroup(groupId)
    fun definition(groupId: UUID): VoiceMessages.GroupDefinition? = known[groupId]
    fun onRegistryWrite(definitionChanged: Boolean) { if (definitionChanged) registryRevision.incrementAndGet() }
    fun findLocal(groupId: UUID): Group? {
        for (group in api.getGroups()) if (groupId == group.getId()) return group
        return null
    }

    fun apply(definition: VoiceMessages.GroupDefinition): Group? {
        val current = known[definition.id()]
        val existing = findLocal(definition.id())
        if (definition == current && existing != null && existing.isPersistent()) return existing
        applying.add(definition.id())
        try {
            api.groupBuilder().setId(definition.id()).setName(definition.name()).setPassword(definition.password())
                .setType(definition.type()).setHidden(definition.hidden()).setPersistent(true).build()
            val group = findLocal(definition.id())
            if (group == null || !group.isPersistent()) {
                logger.warning("Could not install synced voice group '" + definition.name() + "' (" + definition.id() + ")")
                return null
            }
            known[definition.id()] = definition
            return group
        } finally { applying.remove(definition.id()) }
    }

    private fun refresh(groupId: UUID) {
        submitRegistryRead {
            val revision = registryRevision.get()
            var definition = bus.fetchGroup(groupId)
            if (definition == null) {
                val snapshot = bus.fetchGroups() ?: return@submitRegistryRead
                definition = snapshot[groupId]
            }
            val fetched = definition
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (revision != registryRevision.get()) return@Runnable
                if (fetched != null) { apply(fetched); return@Runnable }
                val local = known[groupId]
                if (local != null && (local.permanent() || pendingWrites.contains(groupId))) { persist(local); return@Runnable }
                removeLocal(groupId)
            })
        }
    }

    private fun persist(definition: VoiceMessages.GroupDefinition) {
        pendingWrites.add(definition.id())
        bus.upsertGroup(definition) { succeeded ->
            if (succeeded) {
                registryRevision.incrementAndGet()
                pendingWrites.remove(definition.id())
            }
        }
    }

    private fun submitRegistryRead(task: Runnable) {
        try { registryExecutor.execute(task) } catch (_: RejectedExecutionException) { /* Plugin is stopping. */ }
    }
    fun shutdown() { registryExecutor.shutdownNow() }
    private fun removeLocal(groupId: UUID) { if (findLocal(groupId) == null || api.removeGroup(groupId)) known.remove(groupId) }

    private fun warnUnsupportedPasswordGroup(event: CreateGroupEvent, group: Group) {
        logger.severe("Blocked password-protected voice group '" + group.getName() + "': this Simple Voice Chat runtime does not expose the password needed " + "to secure replicas on every backend")
        val player = event.getConnection()?.getPlayer()?.getPlayer() ?: return
        if (player is Player) player.sendMessage("Could not create that voice group because its password could not be secured across servers. Please contact an administrator.")
    }

    companion object {
        @JvmStatic @Throws(ReflectiveOperationException::class) fun passwordOf(group: Group): String? {
            if (!group.hasPassword()) return null
            val getInternalGroup = group.javaClass.getMethod("getGroup")
            val internalGroup = getInternalGroup.invoke(group) ?: throw ReflectiveOperationException("missing internal group")
            val getPassword = internalGroup.javaClass.getMethod("getPassword")
            val password = getPassword.invoke(internalGroup)
            if (password is String) return password
            throw ReflectiveOperationException("missing group password")
        }
        private fun definitionOf(group: Group, password: String?, permanent: Boolean) = VoiceMessages.GroupDefinition(group.getId(), group.getName(), password, group.getType(), group.isHidden(), permanent)
    }
}
