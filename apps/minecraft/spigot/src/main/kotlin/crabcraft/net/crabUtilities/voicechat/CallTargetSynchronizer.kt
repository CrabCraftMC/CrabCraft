package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.VoicechatServerApi
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer
import java.util.function.Function
import java.util.logging.Logger

/** Applies accepted call targets from authoritative Redis records after rechecking the local voice session. */
class CallTargetSynchronizer(private val plugin: CrabUtilities, private val api: VoicechatServerApi, private val bus: RedisVoiceBus,
                             private val groups: GroupSynchronizer, private val backend: String,
                             private val sessionLookup: Function<UUID, Long?>, private val routeLookup: Function<UUID, String?>,
                             private val cancelRestore: Consumer<UUID>, private val reconcileMembership: Consumer<UUID>, private val logger: Logger) : AutoCloseable {
    private val requestGenerations = ConcurrentHashMap<UUID, Long>()
    private val activeTargets = ConcurrentHashMap<UUID, VoiceMessages.CallTarget>()
    private val suppressedTargets = ConcurrentHashMap<UUID, VoiceMessages.CallTarget>()
    private val hintedTargets = ConcurrentHashMap<UUID, VoiceMessages.CallTarget>()
    private val manualOverrides = ConcurrentHashMap.newKeySet<UUID>()
    private val applying = ConcurrentHashMap.newKeySet<UUID>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "CrabUtilities-CallTargets").apply { isDaemon = true } }
    @Volatile private var closed = false

    fun onJoinHint(join: VoiceMessages.CallJoin) {
        if (closed || sessionLookup.apply(join.playerId()) == null) return
        hintedTargets[join.playerId()] = join.target()
        request(setOf(join.playerId()))
    }
    fun reconcile(playerIds: Set<UUID>) { if (!closed && playerIds.isNotEmpty()) request(playerIds) }
    fun onConnect(playerId: UUID) {
        invalidateRequests(playerId)
        activeTargets.remove(playerId)
        suppressedTargets.remove(playerId)
        hintedTargets.remove(playerId)
        manualOverrides.remove(playerId)
    }
    fun onRouteReady(playerId: UUID) { if (!closed && sessionLookup.apply(playerId) != null) request(setOf(playerId)) }
    fun isApplying(playerId: UUID): Boolean = applying.contains(playerId)

    /** Prevents a missed or delayed join from undoing an explicit local group choice. */
    fun onManualGroupChange(playerId: UUID) {
        if (closed) return
        invalidateRequests(playerId)
        manualOverrides.add(playerId)
        val hinted = hintedTargets.remove(playerId)
        val active = activeTargets.remove(playerId)
        val target = manualSuppressionTarget(active, hinted)
        if (target != null) {
            suppressedTargets[playerId] = target
            clearTarget(playerId, target, true)
        }
        request(setOf(playerId))
    }

    fun onMembershipReconciled(playerId: UUID, groupId: UUID?) {
        val target = activeTargets[playerId] ?: return
        if (target.groupId() != groupId) { onManualGroupChange(playerId); return }
        // The refreshed player-group lease owns membership and backend-hop recovery.
        clearTarget(playerId, target, false)
    }

    fun onDisconnect(playerId: UUID) {
        invalidateRequests(playerId)
        activeTargets.remove(playerId)
        suppressedTargets.remove(playerId)
        hintedTargets.remove(playerId)
        manualOverrides.remove(playerId)
        applying.remove(playerId)
    }

    private fun request(playerIds: Set<UUID>) {
        if (closed) return
        val requests = HashMap<UUID, Request>()
        for (playerId in playerIds) {
            val session = sessionLookup.apply(playerId) ?: continue
            val generation = requestGenerations.merge(playerId, 1L, Long::plus)!!
            requests[playerId] = Request(session, generation)
        }
        if (requests.isEmpty()) return
        try { recoveryExecutor.execute { read(requests) } } catch (_: RejectedExecutionException) { /* Plugin is stopping. */ }
    }

    private fun read(requests: Map<UUID, Request>) {
        if (closed) return
        val read = bus.fetchCallTargets(requests.keys)
        if (!read.succeeded()) return
        val definitions = bus.fetchGroups() ?: return
        try { Bukkit.getScheduler().runTask(plugin, Runnable { applyRead(requests, read.value()!!, definitions) }) }
        catch (_: Exception) { /* Plugin is stopping. */ }
    }

    private fun applyRead(requests: Map<UUID, Request>, targets: Map<UUID, VoiceMessages.CallTarget>, definitions: Map<UUID, VoiceMessages.GroupDefinition>) {
        if (closed) return
        for ((playerId, request) in requests) {
            val target = targets[playerId]
            if (!isCurrent(playerId, request)) continue
            if (manualOverrides.remove(playerId)) {
                activeTargets.remove(playerId)
                val hinted = hintedTargets[playerId]
                val suppressed = suppressedTargets[playerId]
                if (isNewAcceptedTarget(target, hinted, suppressed)) {
                    // Only an authoritative target accepted after the manual choice may win.
                } else if (target != null) {
                    suppressedTargets[playerId] = target
                    clearTarget(playerId, target, true)
                    continue
                } else {
                    suppressedTargets.remove(playerId)
                    hintedTargets.remove(playerId)
                    continue
                }
            }
            if (target == null) {
                activeTargets.remove(playerId)
                suppressedTargets.remove(playerId)
                hintedTargets.remove(playerId)
                continue
            }
            val suppressed = suppressedTargets[playerId]
            if (target == suppressed) { clearTarget(playerId, target, true); continue }
            if (suppressed != null) suppressedTargets.remove(playerId, suppressed)
            val definition = definitions[target.groupId()]
            if (!isCallGroup(definition)) continue
            applyTarget(playerId, request, target, definition!!)
        }
    }

    private fun applyTarget(playerId: UUID, request: Request, target: VoiceMessages.CallTarget, definition: VoiceMessages.GroupDefinition) {
        if (!isCurrent(playerId, request)) return
        val player = Bukkit.getPlayer(playerId)
        val connection = api.getConnectionOf(playerId)
        val route = routeLookup.apply(playerId)
        if (player == null || !player.isOnline() || connection == null || !connection.isInstalled() || !connection.isConnected() || backend != VoiceMessages.routeBackend(route)) return
        val current = connection.getGroup()
        if (current != null && target.groupId() == current.getId()) { confirm(playerId, request, target); return }
        val callGroup = groups.findLocal(target.groupId()) ?: groups.apply(definition)
        if (callGroup == null || !isCurrent(playerId, request)) return
        cancelRestore.accept(playerId)
        applying.add(playerId)
        try { connection.setGroup(callGroup) } catch (e: Exception) {
            logger.warning("Could not connect " + playerId + " to a private voice call")
            return
        } finally { applying.remove(playerId) }
        try { Bukkit.getScheduler().runTask(plugin, Runnable { confirm(playerId, request, target) }) }
        catch (_: Exception) { /* Plugin is stopping. */ }
    }

    private fun confirm(playerId: UUID, request: Request, target: VoiceMessages.CallTarget) {
        if (!isCurrent(playerId, request)) return
        val connection = api.getConnectionOf(playerId)
        val group = connection?.getGroup()
        if (connection == null || !connection.isConnected() || backend != VoiceMessages.routeBackend(routeLookup.apply(playerId)) || group == null || target.groupId() != group.getId()) return
        activeTargets[playerId] = target
        hintedTargets.remove(playerId, target)
        reconcileMembership.accept(playerId)
    }
    private fun isCurrent(playerId: UUID, request: Request): Boolean = !closed && sameSession(playerId, request) && requestGenerations[playerId] == request.generation
    private fun sameSession(playerId: UUID, request: Request): Boolean = sessionLookup.apply(playerId) == request.session
    private fun clearTarget(playerId: UUID, target: VoiceMessages.CallTarget, reconcileAfter: Boolean) {
        bus.clearCallTarget(playerId, target, routeLookup.apply(playerId), if (reconcileAfter) Runnable { reconcileMembership.accept(playerId) } else Runnable {})
    }
    private fun invalidateRequests(playerId: UUID) { requestGenerations.merge(playerId, 1L, Long::plus) }

    override fun close() {
        closed = true
        recoveryExecutor.shutdownNow()
        requestGenerations.clear()
        activeTargets.clear()
        suppressedTargets.clear()
        hintedTargets.clear()
        manualOverrides.clear()
        applying.clear()
    }
    private data class Request(val session: Long, val generation: Long)
    companion object {
        const val CALL_GROUP_NAME = "Private Call"
        @JvmStatic fun isCallGroup(definition: VoiceMessages.GroupDefinition?): Boolean = definition != null && CALL_GROUP_NAME == definition.name() && !definition.password().isNullOrBlank() && definition.type() == Group.Type.OPEN && definition.hidden() && !definition.permanent()
        @JvmStatic fun isNewAcceptedTarget(target: VoiceMessages.CallTarget?, hinted: VoiceMessages.CallTarget?, suppressed: VoiceMessages.CallTarget?): Boolean = target != null && target == hinted && target != suppressed
        @JvmStatic fun manualSuppressionTarget(active: VoiceMessages.CallTarget?, hinted: VoiceMessages.CallTarget?): VoiceMessages.CallTarget? = hinted ?: active
    }
}
