package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.logging.Logger

/** Tracks cross-server group members and publishes their SVC client roster state. */
class RosterTracker(private val plugin: CrabUtilities, private val svcPackets: SvcPacketSender,
                    private val thisBackend: String, private val logger: Logger, private val invalidateSpeaker: Consumer<UUID>) {
    @Volatile private var closed = false
    private val remoteByGroup = ConcurrentHashMap<UUID, MutableMap<UUID, RemoteMember>>()

    fun onLifecycleMessage(message: String?) {
        if (closed || message.isNullOrEmpty()) return
        // Mutations and their client packets must share an ordered task.
        Bukkit.getScheduler().runTask(plugin, Runnable { applyLifecycleMessage(message) })
    }

    private fun applyLifecycleMessage(message: String) {
        if (closed) return
        val sep = message.indexOf(VoiceMessages.SEP)
        val op = if (sep < 0) message else message.substring(0, sep)
        when (op) {
            VoiceMessages.OP_ROSTER_JOIN -> handleJoin(message)
            VoiceMessages.OP_ROSTER_LEAVE -> handleLeave(message)
        }
    }

    private fun handleJoin(message: String) {
        val join = VoiceMessages.decodeRosterJoin(message) ?: return
        if (thisBackend == join.backend()) return
        if (Bukkit.getPlayer(join.playerId()) != null) { onLocalConnect(join.playerId()); return }
        val now = System.currentTimeMillis()
        // Direct group moves replace the player's authoritative roster location.
        for ((groupId, members) in remoteByGroup) {
            if (groupId != join.groupId()) {
                if (members.remove(join.playerId()) != null) invalidateSpeaker.accept(join.playerId())
                if (members.isEmpty()) remoteByGroup.remove(groupId)
            }
        }
        val groupMap = remoteByGroup.computeIfAbsent(join.groupId()) { ConcurrentHashMap() }
        val existing = groupMap[join.playerId()]
        if (existing != null && existing.route() == join.route() && existing.name() == join.name()) {
            groupMap[join.playerId()] = existing.withTimestamp(now)
            return
        }
        val member = RemoteMember(join.playerId(), join.name(), join.route(), now)
        groupMap[join.playerId()] = member
        if (existing != null && existing.route() != member.route()) invalidateSpeaker.accept(join.playerId())
        logger.info("Roster join: " + join.name() + " (" + join.playerId() + ") in group " + join.groupId() + " from backend '" + join.backend() + "'")
        pushMemberToLocalListeners(join.groupId(), member)
    }

    private fun handleLeave(message: String) {
        val leave = VoiceMessages.decodeRosterLeave(message) ?: return
        if (thisBackend == leave.backend()) return
        val members = remoteByGroup[leave.groupId()] ?: return
        // A delayed origin leave must not remove the destination's newer route.
        val removed = members[leave.playerId()]
        if (removed == null || removed.route() != leave.route() || !members.remove(leave.playerId(), removed)) return
        if (members.isEmpty()) remoteByGroup.remove(leave.groupId())
        invalidateSpeaker.accept(leave.playerId())
        removeMemberFromLocalListeners(removed)
    }

    /** Expires members whose backend stopped refreshing their roster lease. */
    fun sweepStaleEntries() {
        val now = System.currentTimeMillis()
        for ((groupId, members) in remoteByGroup) {
            for ((playerId, removed) in members) {
                if (now - removed.lastSeenAt() <= ENTRY_TIMEOUT_MS || !members.remove(playerId, removed)) continue
                logger.info("Roster sweep: dropping stale " + removed.name() + " (" + removed.uuid() + ") from group " + groupId + " — no re-broadcast from backend '" + removed.backend() + "' in " + (ENTRY_TIMEOUT_MS / 1000) + "s")
                invalidateSpeaker.accept(removed.uuid())
                removeMemberFromLocalListeners(removed)
            }
            if (members.isEmpty()) remoteByGroup.remove(groupId)
        }
    }

    /** Native SVC now owns this player; discard their previous remote entry. */
    fun onLocalConnect(playerId: UUID) {
        for ((groupId, members) in remoteByGroup) {
            members.remove(playerId)
            if (members.isEmpty()) remoteByGroup.remove(groupId)
        }
    }

    fun catchUpNewLocalConnection(joiner: Player) {
        for ((groupId, members) in remoteByGroup) for (member in members.values) sendMemberTo(joiner, groupId, member)
    }

    private fun pushMemberToLocalListeners(groupId: UUID, member: RemoteMember) {
        // Everyone needs remote state before opening the join-group GUI.
        for (player in Bukkit.getOnlinePlayers()) sendMemberTo(player, groupId, member)
    }

    private fun removeMemberFromLocalListeners(member: RemoteMember) {
        // Native state is authoritative for live local players.
        if (Bukkit.getPlayer(member.uuid()) != null) return
        for (player in Bukkit.getOnlinePlayers()) svcPackets.sendRemove(player, member.uuid())
    }

    private fun sendMemberTo(recipient: Player, groupId: UUID, member: RemoteMember) {
        if (Bukkit.getPlayer(member.uuid()) != null) return
        svcPackets.sendState(recipient, member.uuid(), member.name(), groupId)
    }

    fun shutdown() {
        closed = true
        for (members in remoteByGroup.values) for (member in members.values) removeMemberFromLocalListeners(member)
        remoteByGroup.clear()
    }

    data class RemoteMember(private val uuid: UUID, private val name: String?, private val route: String, private val lastSeenAt: Long) {
        fun uuid(): UUID = uuid
        fun name(): String? = name
        fun route(): String = route
        fun lastSeenAt(): Long = lastSeenAt
        fun backend(): String = VoiceMessages.routeBackend(route)!!
        fun withTimestamp(seenTimestamp: Long): RemoteMember = RemoteMember(uuid, name, route, seenTimestamp)
    }

    companion object { const val ENTRY_TIMEOUT_MS = 90_000L }
}
