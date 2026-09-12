package crabcraft.net.crabUtilities.velocity.voicechat

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object CallManagerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        targetBoundOneUseInvites()
        invitationsAreBoundToProxySessions()
        expiringInvites()
        provisionalCallsAreReused()
        outgoingInvitesCanBeBounded()
        passwordStaysOutOfTheAcceptCommand()
        callGroupsUseTheSharedRegistryEncoding()
        ringtoneMessagesAreStrictAndTokenBound()
    }

    private fun targetBoundOneUseInvites() {
        val registry = CallInviteRegistry()
        val caller = UUID.randomUUID()
        val target = UUID.randomUUID()
        val stranger = UUID.randomUUID()
        val call = credentials(10_000L)
        val invite = CallInviteRegistry.Invite(
                "opaque-token", caller, "Caller", target, "Target",
                call, false, 1_000L)

        check(registry.add(invite, 0L), "first invitation was rejected")
        check(registry.take(invite.token(), stranger, 100L).isEmpty(),
                "another player consumed the invitation")
        check(registry.take(invite.token(), target, 100L).orElseThrow() === invite,
                "the intended target could not accept")
        check(registry.take(invite.token(), target, 100L).isEmpty(),
                "an accepted token was reusable")

        val removable = CallInviteRegistry.Invite(
                "remove-token", caller, "Caller", target, "Target",
                call, false, 1_000L)
        check(registry.add(removable, 100L), "removable invitation was rejected")
        check(registry.remove(removable.token(), stranger).isEmpty(),
                "another player removed the invitation during terminal cleanup")
        check(registry.remove(removable.token(), target).orElseThrow() === removable,
                "terminal cleanup could not remove the intended invitation")
        check(registry.remove(removable.token(), target).isEmpty(),
                "terminal cleanup removed an invitation twice")
    }

    private fun invitationsAreBoundToProxySessions() {
        val registry = CallInviteRegistry()
        val callerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val callerSession = Any()
        val targetSession = Any()
        val quickRelog = Any()
        val invite = CallInviteRegistry.Invite(
                "session-token", callerId, "Caller", targetId, "Target",
                callerSession, targetSession, credentials(10_000L), false, 1_000L)

        check(registry.add(invite, 0L), "session-bound invitation was rejected")
        check(registry.take(invite.token(), targetId, quickRelog, 100L).isEmpty(),
                "a quick relog adopted an earlier session's invitation")
        check(registry.take(invite.token(), targetId, targetSession, 100L).orElseThrow() === invite,
                "the original target session could not accept")

        val oldTarget = UUID.randomUUID()
        val newTarget = UUID.randomUUID()
        val oldInvite = CallInviteRegistry.Invite(
                "old-session-token", callerId, "Caller", oldTarget, "Old",
                callerSession, Any(), credentials(10_000L), false, 1_000L)
        val newInvite = CallInviteRegistry.Invite(
                "new-session-token", callerId, "Caller", newTarget, "New",
                quickRelog, Any(), credentials(10_000L), false, 1_000L)
        check(registry.add(oldInvite, 0L) && registry.add(newInvite, 0L),
                "relogin cleanup invitations were rejected")
        check(registry.removeSession(callerId, callerSession).contains(oldInvite),
                "old proxy session was not cleaned up")
        check(registry.take(newInvite.token(), newTarget,
                        newInvite.targetSession(), 100L).orElseThrow() === newInvite,
                "old disconnect cleanup removed the quick relog's invitation")
    }

    private fun expiringInvites() {
        val registry = CallInviteRegistry()
        val target = UUID.randomUUID()
        val invite = CallInviteRegistry.Invite(
                "short-lived", UUID.randomUUID(), "Caller", target, "Target",
                credentials(10_000L), false, 500L)

        check(registry.add(invite, 0L), "expiring invitation was rejected")
        check(registry.take(invite.token(), target, 500L).isEmpty(),
                "an expired invitation was accepted")
    }

    private fun provisionalCallsAreReused() {
        val registry = CallInviteRegistry()
        val caller = UUID.randomUUID()
        val creations = AtomicInteger()

        val first = registry.provisionalFor(
                caller, 100L, {
                    creations.incrementAndGet()
                    credentials(1_000L)
                })
        val second = registry.provisionalFor(
                caller, 200L, {
                    creations.incrementAndGet()
                    credentials(1_000L)
                })

        check(first === second, "simultaneous outgoing invites created different calls")
        check(creations.get() == 1, "provisional call factory ran more than once")

        val target = UUID.randomUUID()
        val pending = CallInviteRegistry.Invite(
                "later-invite", caller, "Caller", target, "Target",
                first, false, 900L)
        check(registry.add(pending, 200L), "follow-up invitation was rejected")
        registry.activate(caller, first.groupId())
        check(registry.take(pending.token(), target, 300L).orElseThrow().callerWasInCall(),
                "follow-up invitation could resurrect a call after its caller left")

        val replacement = registry.provisionalFor(
                caller, 300L, {
                    creations.incrementAndGet()
                    credentials(2_000L)
                })
        check(replacement !== first, "activated provisional call was retained")

        val oldSession = Any()
        val quickRelog = Any()
        val beforeRelog = registry.provisionalFor(
                caller, oldSession, 400L, { credentials(2_000L) })
        val afterRelog = registry.provisionalFor(
                caller, quickRelog, 400L, { credentials(2_000L) })
        check(beforeRelog !== afterRelog,
                "a quick relog inherited the old session's provisional password")
    }

    private fun passwordStaysOutOfTheAcceptCommand() {
        val password = "server_only_password_1234567890"
        val token = "click-token"
        val groupId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val generation = "AbCdEfGhIjKlMnOpQrStUvWx"
        val call = CallInviteRegistry.CallCredentials(groupId, password, 10_000L)

        val wire = CallManager.encodeCallJoin(groupId, playerId, generation)
                .split(CallManager.SEPARATOR)
        check(wire.size == 4, "CALL_JOIN field count changed")
        check(CallManager.OP_CALL_JOIN.equals(wire[0]), "CALL_JOIN opcode changed")
        check(groupId.toString().equals(wire[1]), "CALL_JOIN group changed")
        check(playerId.toString().equals(wire[2]), "CALL_JOIN player changed")
        check(generation.equals(wire[3]), "CALL_JOIN generation changed")
        check(!wire.joinToString(CallManager.SEPARATOR).contains(password),
                "group password leaked into CALL_JOIN")
        check(PlayerLocationTracker.playerHomeKey(playerId)
                        .equals("crabcraft:svc:player-home:" + playerId),
                "route guard key changed")

        val target = CallManager.callTargetValue(groupId, generation)
        check(target.equals(groupId.toString() + CallManager.SEPARATOR + generation),
                "durable call target encoding changed")
        check(!target.contains(password), "group password leaked into durable call target")

        val command = CallManager.acceptCommand(token)
        check(command.equals("/call accept " + token), "accept command changed")
        check(!command.contains(password), "group password leaked into click command")
    }

    private fun callGroupsUseTheSharedRegistryEncoding() {
        val groupId = UUID.randomUUID()
        val call = CallInviteRegistry.CallCredentials(
                groupId, "server_only_password_1234567890", Long.MAX_VALUE)
        val encoded = CallManager.encodeCallGroupDefinition(call)
        val definition = CallManager.decodeGroupDefinition(groupId, encoded)

        check(definition != null, "call group definition did not round-trip")
        definition!!
        check(groupId.equals(definition.id()), "call group ID changed")
        check("Private Call".equals(definition.name()), "call marker name changed")
        check(call.password().equals(definition.password()), "call password changed")
        check("OPEN".equals(definition.type()), "call group is no longer OPEN")
        check(definition.hidden(), "call group is no longer hidden")
        check(!definition.permanent(), "call group became permanent")
        check(CallManager.isCallGroupDefinition(definition),
                "call definition was not recognised as a call")

        val ordinary = CallManager.GroupDefinition(
                groupId, "Secret Club", call.password(), "OPEN", true, false)
        check(!CallManager.isCallGroupDefinition(ordinary),
                "an ordinary passworded group was co-opted as a call")
        check(CallManager.CONTROL_CHANNEL.equals("crabcraft:svc:lifecycle"),
                "call controls left the shared lifecycle channel")
    }

    private fun outgoingInvitesCanBeBounded() {
        val registry = CallInviteRegistry()
        val caller = UUID.randomUUID()
        val call = credentials(10_000L)
        for (index in 0 until 5) {
            val target = UUID.randomUUID()
            val invite = CallInviteRegistry.Invite(
                    "token-" + index, caller, "Caller", target, "Target",
                    call, false, 1_000L)
            check(registry.add(invite, 0L), "bounded invitation was rejected early")
        }
        check(!registry.hasOutgoingCapacity(caller, 5, 100L),
                "outgoing invitation cap was not enforced")
        check(registry.hasOutgoingCapacity(caller, 5, 1_000L),
                "expired outgoing invitations still consumed capacity")
    }

    private fun ringtoneMessagesAreStrictAndTokenBound() {
        val token = "AbCdEfGhIjKlMnOpQrStUvWx"
        val anotherToken = "ZyXwVuTsRqPoNmLkJiHgFeDc"
        val password = "server_only_password_1234567890"
        val callerId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val expiresAtMillis = 50_000L

        val callerStart = CallManager.encodeCallRingStart(token, callerId,
                CallManager.RingDirection.OUTGOING, expiresAtMillis)
        val targetStart = CallManager.encodeCallRingStart(token, targetId,
                CallManager.RingDirection.INCOMING, expiresAtMillis)
        val callerStop = CallManager.encodeCallRingStop(token, callerId,
                CallManager.RingDirection.OUTGOING)
        val targetStop = CallManager.encodeCallRingStop(token, targetId,
                CallManager.RingDirection.INCOMING)

        val callerStartWire = callerStart.split(CallManager.SEPARATOR)
        check(callerStartWire.size == 5, "CALL_RING_START field count changed")
        check(CallManager.OP_CALL_RING_START.equals(callerStartWire[0]),
                "CALL_RING_START opcode changed")
        check(token.equals(callerStartWire[1]), "CALL_RING_START token changed")
        check(callerId.toString().equals(callerStartWire[2]),
                "CALL_RING_START player changed")
        check("OUTGOING".equals(callerStartWire[3]),
                "caller did not receive the outgoing ringtone")
        check(expiresAtMillis.toString().equals(callerStartWire[4]),
                "CALL_RING_START deadline changed")

        val targetStartWire = targetStart.split(CallManager.SEPARATOR)
        check(targetStartWire.size == 5, "incoming CALL_RING_START field count changed")
        check(targetId.toString().equals(targetStartWire[2]),
                "incoming CALL_RING_START player changed")
        check("INCOMING".equals(targetStartWire[3]),
                "target did not receive the incoming ringtone")
        check(targetStartWire[4].equals(callerStartWire[4]),
                "caller and target received different ringtone deadlines")

        val callerStopWire = callerStop.split(CallManager.SEPARATOR)
        val targetStopWire = targetStop.split(CallManager.SEPARATOR)
        check(callerStopWire.size == 4 && targetStopWire.size == 4,
                "CALL_RING_STOP field count changed")
        check(CallManager.OP_CALL_RING_STOP.equals(callerStopWire[0]),
                "CALL_RING_STOP opcode changed")
        check(token.equals(callerStopWire[1]) && token.equals(targetStopWire[1]),
                "CALL_RING_STOP was not bound to its invitation token")
        check("OUTGOING".equals(callerStopWire[3])
                        && "INCOMING".equals(targetStopWire[3]),
                "CALL_RING_STOP direction changed")

        val anotherStop = CallManager.encodeCallRingStop(anotherToken, callerId,
                CallManager.RingDirection.OUTGOING)
        check(!callerStop.equals(anotherStop),
                "one outgoing invitation could stop another invitation's ringtone")
        check(!callerStart.contains(password) && !targetStart.contains(password)
                        && !callerStop.contains(password) && !targetStop.contains(password),
                "call password leaked into ringtone control messages")
        val retryInvite = CallInviteRegistry.Invite(
                token, callerId, "Caller", targetId, "Target",
                CallInviteRegistry.CallCredentials(UUID.randomUUID(), password,
                        expiresAtMillis + 10_000L),
                false, expiresAtMillis)
        val retryArguments = CallManager.encodeCallRingStopRetryArguments(retryInvite)
        check(retryArguments.size == 4,
                "CALL_RING_STOP retry argument count changed")
        check(CallManager.CONTROL_CHANNEL.equals(retryArguments.get(0)),
                "CALL_RING_STOP retry channel changed")
        check(callerStop.equals(retryArguments.get(1))
                        && targetStop.equals(retryArguments.get(2)),
                "CALL_RING_STOP retry was not token-bound to both players")
        check(expiresAtMillis.toString().equals(retryArguments.get(3)),
                "CALL_RING_STOP retry did not retain the original deadline")
        check(retryArguments.none { argument -> argument.contains(password) },
                "call password leaked into CALL_RING_STOP retry arguments")
        check(CallManager.INVITE_TIMEOUT_MILLIS == 30_000L,
                "call invitation no longer rings for exactly 30 seconds")
        check(CallManager.shouldRetryRingtoneStop(expiresAtMillis, expiresAtMillis - 1L),
                "CALL_RING_STOP retry ended before the invitation deadline")
        check(!CallManager.shouldRetryRingtoneStop(expiresAtMillis, expiresAtMillis),
                "CALL_RING_STOP retried at the invitation deadline")
        check(!CallManager.shouldRetryRingtoneStop(expiresAtMillis, expiresAtMillis + 1L),
                "CALL_RING_STOP retried beyond the invitation deadline")
        val normalRetryDelay = CallManager.ringtoneStopRetryDelayMillis(30_000L, 0L)
        check(normalRetryDelay == 2_000L,
                "CALL_RING_STOP retry interval changed")
        val finalRetryDelay = CallManager.ringtoneStopRetryDelayMillis(30_000L, 29_000L)
        check(finalRetryDelay > 0L && 29_000L + finalRetryDelay < 30_000L,
                "final CALL_RING_STOP retry was not bounded by the original deadline")
        check(CallManager.ringtoneStopRetryDelayMillis(30_000L, 30_000L) == 0L,
                "CALL_RING_STOP scheduled work at the original deadline")

        expectIllegalArgument { CallManager.encodeCallRingStart(
                "too-short", callerId, CallManager.RingDirection.OUTGOING, expiresAtMillis) }
        expectIllegalArgument { CallManager.encodeCallRingStop(
                "AbCdEfGhIjKlMnOpQrStUv\u0000", callerId, CallManager.RingDirection.OUTGOING) }
        expectIllegalArgument { CallManager.encodeCallRingStart(
                token, callerId, CallManager.RingDirection.OUTGOING, 0L) }
    }

    private fun expectIllegalArgument(operation: Runnable) {
        try {
            operation.run()
            throw AssertionError("invalid ringtone control message was accepted")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun credentials(expiresAtMillis: Long): CallInviteRegistry.CallCredentials {
        return CallInviteRegistry.CallCredentials(
                UUID.randomUUID(), "server_only_password_1234567890", expiresAtMillis)
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
