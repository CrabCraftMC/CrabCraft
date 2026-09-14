package crabcraft.net.crabUtilities.velocity.voicechat

import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/** Thread-safe in-memory state for short-lived call invitations. */
class CallInviteRegistry {
    data class CallCredentials(private val groupId: UUID, private val password: String, private val expiresAtMillis: Long) {
        fun groupId() = groupId
        fun password() = password
        fun expiresAtMillis() = expiresAtMillis
    }
    data class Invite(private val token: String, private val callerId: UUID, private val callerName: String,
                      private val targetId: UUID, private val targetName: String,
                      private val callerSession: Any?, private val targetSession: Any?,
                      private val call: CallCredentials, private val callerWasInCall: Boolean,
                      private val expiresAtMillis: Long) {
        constructor(token: String, callerId: UUID, callerName: String, targetId: UUID, targetName: String,
                    call: CallCredentials, callerWasInCall: Boolean, expiresAtMillis: Long) :
            this(token, callerId, callerName, targetId, targetName, null, null, call, callerWasInCall, expiresAtMillis)
        fun token() = token
        fun callerId() = callerId
        fun callerName() = callerName
        fun targetId() = targetId
        fun targetName() = targetName
        fun callerSession() = callerSession
        fun targetSession() = targetSession
        fun call() = call
        fun callerWasInCall() = callerWasInCall
        fun expiresAtMillis() = expiresAtMillis
    }
    private val byToken = HashMap<String, Invite>()
    private val tokenByTarget = HashMap<UUID, String>()
    private val provisionalByCaller = HashMap<UUID, ProvisionalCall>()

    @Synchronized fun add(invite: Invite, nowMillis: Long): Boolean {
        removeExpiredTarget(invite.targetId(), nowMillis)
        if (tokenByTarget.containsKey(invite.targetId()) || byToken.containsKey(invite.token())) return false
        byToken[invite.token()] = invite
        tokenByTarget[invite.targetId()] = invite.token()
        return true
    }
    @Synchronized fun take(token: String, targetId: UUID, nowMillis: Long): Optional<Invite> = take(token, targetId, null, nowMillis)
    @Synchronized fun take(token: String, targetId: UUID, targetSession: Any?, nowMillis: Long): Optional<Invite> {
        val invite = byToken[token]
        if (invite == null || invite.targetId() != targetId || (invite.targetSession() != null && invite.targetSession() !== targetSession)) return Optional.empty()
        remove(invite)
        return if (invite.expiresAtMillis() <= nowMillis) Optional.empty() else Optional.of(invite)
    }
    @Synchronized fun expire(token: String, nowMillis: Long): Optional<Invite> {
        val invite = byToken[token]
        if (invite == null || invite.expiresAtMillis() > nowMillis) return Optional.empty()
        remove(invite)
        return Optional.of(invite)
    }
    @Synchronized fun remove(token: String, targetId: UUID): Optional<Invite> {
        val invite = byToken[token]
        if (invite == null || invite.targetId() != targetId) return Optional.empty()
        remove(invite)
        return Optional.of(invite)
    }
    @Synchronized fun isPending(token: String, targetId: UUID, nowMillis: Long): Boolean {
        val invite = byToken[token]
        return invite != null && invite.targetId() == targetId && invite.expiresAtMillis() > nowMillis
    }
    @Synchronized fun hasOutgoingCapacity(callerId: UUID, maximum: Int, nowMillis: Long): Boolean {
        var active = 0
        for (invite in byToken.values) if (invite.callerId() == callerId && invite.expiresAtMillis() > nowMillis) active++
        return active < maximum
    }
    @Synchronized fun removeSession(playerId: UUID, session: Any): Collection<Invite> {
        val removed = ArrayList<Invite>()
        for (invite in ArrayList(byToken.values)) {
            val callerDisconnected = invite.callerId() == playerId && invite.callerSession() === session
            val targetDisconnected = invite.targetId() == playerId && invite.targetSession() === session
            if (callerDisconnected || targetDisconnected) { remove(invite); removed.add(invite) }
        }
        val provisional = provisionalByCaller[playerId]
        if (provisional != null && provisional.session === session) provisionalByCaller.remove(playerId, provisional)
        return removed
    }
    @Synchronized fun clear(): Collection<Invite> {
        val removed = ArrayList(byToken.values)
        byToken.clear(); tokenByTarget.clear(); provisionalByCaller.clear()
        return removed
    }
    @Synchronized fun provisionalFor(callerId: UUID, nowMillis: Long, factory: Supplier<CallCredentials>): CallCredentials =
        provisionalFor(callerId, null, nowMillis, factory)
    @Synchronized fun provisionalFor(callerId: UUID, callerSession: Any?, nowMillis: Long, factory: Supplier<CallCredentials>): CallCredentials {
        val existing = provisionalByCaller[callerId]
        if (existing != null && existing.session === callerSession && existing.credentials.expiresAtMillis() > nowMillis) return existing.credentials
        val created = factory.get()
        provisionalByCaller[callerId] = ProvisionalCall(callerSession, created)
        return created
    }
    /** Promote a provisional call and make its other invites require live membership. */
    @Synchronized fun activate(callerId: UUID, groupId: UUID) {
        val provisional = provisionalByCaller[callerId]
        if (provisional != null && provisional.credentials.groupId() == groupId) provisionalByCaller.remove(callerId)
        for ((key, invite) in ArrayList(byToken.entries)) {
            if (invite.callerId() != callerId || invite.call().groupId() != groupId || invite.callerWasInCall()) continue
            byToken[key] = Invite(invite.token(), invite.callerId(), invite.callerName(), invite.targetId(), invite.targetName(),
                invite.callerSession(), invite.targetSession(), invite.call(), true, invite.expiresAtMillis())
        }
    }
    private fun removeExpiredTarget(targetId: UUID, nowMillis: Long) {
        val token = tokenByTarget[targetId] ?: return
        val invite = byToken[token]
        if (invite != null && invite.expiresAtMillis() <= nowMillis) remove(invite)
    }
    private fun remove(invite: Invite) {
        byToken.remove(invite.token(), invite)
        tokenByTarget.remove(invite.targetId(), invite.token())
    }
    private data class ProvisionalCall(val session: Any?, val credentials: CallCredentials)
}
