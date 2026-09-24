package crabcraft.net.crabUtilities.voicechat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Tracks one cross-server group/route per player and publishes native SVC roster packets. */
open class RosterTracker(
    private val plugin: Plugin,
    private val svcPackets: SvcPacketSender,
    private val thisBackend: String,
    private val logger: Logger,
    private val invalidateSpeaker: Consumer<UUID>,
) {
    constructor(
        plugin: crabcraft.net.crabUtilities.CrabUtilities,
        svcPackets: SvcPacketSender,
        thisBackend: String,
        logger: Logger,
        invalidateSpeaker: Consumer<UUID>,
    ) : this(plugin as Plugin, svcPackets, thisBackend, logger, invalidateSpeaker)

    companion object {
        const val ENTRY_TIMEOUT_MS = 90_000L
    }

    @Volatile private var closed = false
    private val remoteMembers = ConcurrentHashMap<UUID, RemoteMember>()

    fun onLifecycleMessage(message: String?) {
        if (closed || message.isNullOrEmpty()) return
        // Mutate state and send packets in one ordered task so delayed removal cannot erase a new join.
        Bukkit.getScheduler().runTask(plugin, Runnable { applyLifecycleMessage(message) })
    }

    private fun applyLifecycleMessage(message: String) {
        if (closed) return
        when (message.substringBefore(VoiceMessages.SEP)) {
            VoiceMessages.OP_ROSTER_JOIN -> handleJoin(message)
            VoiceMessages.OP_ROSTER_LEAVE -> handleLeave(message)
        }
    }

    private fun handleJoin(message: String) {
        val join = VoiceMessages.decodeRosterJoin(message) ?: return
        if (thisBackend == join.backend()) return
        if (Bukkit.getPlayer(join.playerId) != null) {
            onLocalConnect(join.playerId)
            return
        }
        val member = RemoteMember(join.playerId, join.groupId, join.name, join.route, System.currentTimeMillis())
        val existing = remoteMembers.put(join.playerId, member)
        if (existing != null) {
            val moved = existing.groupId != member.groupId || existing.route != member.route
            if (moved) invalidateSpeaker.accept(join.playerId)
            else if (existing.name == member.name) return // Heartbeat only refreshes the timestamp.
        }
        logger.info(
            "Roster join: ${join.name} (${join.playerId}) in group ${join.groupId} from backend '${join.backend()}'"
        )
        for (player in Bukkit.getOnlinePlayers()) sendMemberTo(player, member)
    }

    private fun handleLeave(message: String) {
        val leave = VoiceMessages.decodeRosterLeave(message) ?: return
        if (thisBackend == leave.backend()) return
        // Only the owning route may remove an entry; an old backend's leave can arrive after a hop.
        val removed = remoteMembers[leave.playerId] ?: return
        if (
            removed.groupId != leave.groupId ||
                removed.route != leave.route ||
                !remoteMembers.remove(leave.playerId, removed)
        )
            return
        invalidateSpeaker.accept(leave.playerId)
        removeMemberFromLocalListeners(removed)
    }

    fun sweepStaleEntries() {
        val now = System.currentTimeMillis()
        for (removed in remoteMembers.values) {
            if (now - removed.lastSeenAt <= ENTRY_TIMEOUT_MS || !remoteMembers.remove(removed.uuid, removed)) continue
            logger.info(
                "Roster sweep: dropping stale ${removed.name} (${removed.uuid}) from group ${removed.groupId}" +
                    " — no re-broadcast from backend '${removed.backend()}' in ${ENTRY_TIMEOUT_MS / 1000}s"
            )
            invalidateSpeaker.accept(removed.uuid)
            removeMemberFromLocalListeners(removed)
        }
    }

    /** Native SVC now owns this player. */
    fun onLocalConnect(playerId: UUID) {
        remoteMembers.remove(playerId)
    }

    fun catchUpNewLocalConnection(joiner: Player) {
        remoteMembers.values.forEach { sendMemberTo(joiner, it) }
    }

    private fun removeMemberFromLocalListeners(member: RemoteMember) {
        if (Bukkit.getPlayer(member.uuid) != null) return
        for (player in Bukkit.getOnlinePlayers()) svcPackets.sendRemove(player, member.uuid)
    }

    private fun sendMemberTo(recipient: Player, member: RemoteMember) {
        if (Bukkit.getPlayer(member.uuid) == null)
            svcPackets.sendState(recipient, member.uuid, member.name, member.groupId)
    }

    fun shutdown() {
        closed = true
        remoteMembers.values.forEach(::removeMemberFromLocalListeners)
        remoteMembers.clear()
    }

    data class RemoteMember(
        val uuid: UUID,
        val groupId: UUID,
        val name: String?,
        val route: String,
        val lastSeenAt: Long,
    ) {
        fun uuid() = uuid

        fun groupId() = groupId

        fun name() = name

        fun route() = route

        fun lastSeenAt() = lastSeenAt

        fun backend() = VoiceMessages.routeBackend(route)!!
    }
}
