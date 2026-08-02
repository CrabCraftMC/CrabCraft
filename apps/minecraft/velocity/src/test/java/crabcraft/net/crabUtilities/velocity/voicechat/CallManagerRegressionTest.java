package crabcraft.net.crabUtilities.velocity.voicechat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class CallManagerRegressionTest {

    public static void main(String[] args) {
        targetBoundOneUseInvites();
        expiringInvites();
        provisionalCallsAreReused();
        outgoingInvitesCanBeBounded();
        passwordStaysOutOfTheAcceptCommand();
        ringtoneMessagesAreStrictAndTokenBound();
    }

    private static void targetBoundOneUseInvites() {
        CallInviteRegistry registry = new CallInviteRegistry();
        UUID caller = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        CallInviteRegistry.CallCredentials call = credentials(10_000L);
        CallInviteRegistry.Invite invite = new CallInviteRegistry.Invite(
                "opaque-token", caller, "Caller", target, "Target",
                call, false, 1_000L);

        check(registry.add(invite, 0L), "first invitation was rejected");
        check(registry.take(invite.token(), stranger, 100L).isEmpty(),
                "another player consumed the invitation");
        check(registry.take(invite.token(), target, 100L).orElseThrow() == invite,
                "the intended target could not accept");
        check(registry.take(invite.token(), target, 100L).isEmpty(),
                "an accepted token was reusable");

        CallInviteRegistry.Invite removable = new CallInviteRegistry.Invite(
                "remove-token", caller, "Caller", target, "Target",
                call, false, 1_000L);
        check(registry.add(removable, 100L), "removable invitation was rejected");
        check(registry.remove(removable.token(), stranger).isEmpty(),
                "another player removed the invitation during terminal cleanup");
        check(registry.remove(removable.token(), target).orElseThrow() == removable,
                "terminal cleanup could not remove the intended invitation");
        check(registry.remove(removable.token(), target).isEmpty(),
                "terminal cleanup removed an invitation twice");
    }

    private static void expiringInvites() {
        CallInviteRegistry registry = new CallInviteRegistry();
        UUID target = UUID.randomUUID();
        CallInviteRegistry.Invite invite = new CallInviteRegistry.Invite(
                "short-lived", UUID.randomUUID(), "Caller", target, "Target",
                credentials(10_000L), false, 500L);

        check(registry.add(invite, 0L), "expiring invitation was rejected");
        check(registry.take(invite.token(), target, 500L).isEmpty(),
                "an expired invitation was accepted");
    }

    private static void provisionalCallsAreReused() {
        CallInviteRegistry registry = new CallInviteRegistry();
        UUID caller = UUID.randomUUID();
        AtomicInteger creations = new AtomicInteger();

        CallInviteRegistry.CallCredentials first = registry.provisionalFor(
                caller, 100L, () -> {
                    creations.incrementAndGet();
                    return credentials(1_000L);
                });
        CallInviteRegistry.CallCredentials second = registry.provisionalFor(
                caller, 200L, () -> {
                    creations.incrementAndGet();
                    return credentials(1_000L);
                });

        check(first == second, "simultaneous outgoing invites created different calls");
        check(creations.get() == 1, "provisional call factory ran more than once");

        UUID target = UUID.randomUUID();
        CallInviteRegistry.Invite pending = new CallInviteRegistry.Invite(
                "later-invite", caller, "Caller", target, "Target",
                first, false, 900L);
        check(registry.add(pending, 200L), "follow-up invitation was rejected");
        registry.activate(caller, first.groupId());
        check(registry.take(pending.token(), target, 300L).orElseThrow().callerWasInCall(),
                "follow-up invitation could resurrect a call after its caller left");

        CallInviteRegistry.CallCredentials replacement = registry.provisionalFor(
                caller, 300L, () -> {
                    creations.incrementAndGet();
                    return credentials(2_000L);
                });
        check(replacement != first, "activated provisional call was retained");
    }

    private static void passwordStaysOutOfTheAcceptCommand() {
        String password = "server_only_password_1234567890";
        String token = "click-token";
        UUID groupId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        CallInviteRegistry.CallCredentials call =
                new CallInviteRegistry.CallCredentials(groupId, password, 10_000L);

        String[] wire = CallManager.encodeCallJoin(call, playerId)
                .split(CallManager.SEPARATOR, -1);
        check(wire.length == 4, "CALL_JOIN field count changed");
        check(CallManager.OP_CALL_JOIN.equals(wire[0]), "CALL_JOIN opcode changed");
        check(groupId.toString().equals(wire[1]), "CALL_JOIN group changed");
        check(playerId.toString().equals(wire[2]), "CALL_JOIN player changed");
        check(password.equals(wire[3]), "CALL_JOIN password changed");
        check(PlayerLocationTracker.playerSessionKey(playerId)
                        .equals("crabcraft:svc:player-session:" + playerId),
                "session guard key changed");

        String command = CallManager.acceptCommand(token);
        check(command.equals("/call accept " + token), "accept command changed");
        check(!command.contains(password), "group password leaked into click command");
    }

    private static void outgoingInvitesCanBeBounded() {
        CallInviteRegistry registry = new CallInviteRegistry();
        UUID caller = UUID.randomUUID();
        CallInviteRegistry.CallCredentials call = credentials(10_000L);
        for (int index = 0; index < 5; index++) {
            UUID target = UUID.randomUUID();
            CallInviteRegistry.Invite invite = new CallInviteRegistry.Invite(
                    "token-" + index, caller, "Caller", target, "Target",
                    call, false, 1_000L);
            check(registry.add(invite, 0L), "bounded invitation was rejected early");
        }
        check(!registry.hasOutgoingCapacity(caller, 5, 100L),
                "outgoing invitation cap was not enforced");
        check(registry.hasOutgoingCapacity(caller, 5, 1_000L),
                "expired outgoing invitations still consumed capacity");
    }

    private static void ringtoneMessagesAreStrictAndTokenBound() {
        String token = "AbCdEfGhIjKlMnOpQrStUvWx";
        String anotherToken = "ZyXwVuTsRqPoNmLkJiHgFeDc";
        String password = "server_only_password_1234567890";
        UUID callerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        long expiresAtMillis = 50_000L;

        String callerStart = CallManager.encodeCallRingStart(token, callerId,
                CallManager.RingDirection.OUTGOING, expiresAtMillis);
        String targetStart = CallManager.encodeCallRingStart(token, targetId,
                CallManager.RingDirection.INCOMING, expiresAtMillis);
        String callerStop = CallManager.encodeCallRingStop(token, callerId,
                CallManager.RingDirection.OUTGOING);
        String targetStop = CallManager.encodeCallRingStop(token, targetId,
                CallManager.RingDirection.INCOMING);

        String[] callerStartWire = callerStart.split(CallManager.SEPARATOR, -1);
        check(callerStartWire.length == 5, "CALL_RING_START field count changed");
        check(CallManager.OP_CALL_RING_START.equals(callerStartWire[0]),
                "CALL_RING_START opcode changed");
        check(token.equals(callerStartWire[1]), "CALL_RING_START token changed");
        check(callerId.toString().equals(callerStartWire[2]),
                "CALL_RING_START player changed");
        check("OUTGOING".equals(callerStartWire[3]),
                "caller did not receive the outgoing ringtone");
        check(Long.toString(expiresAtMillis).equals(callerStartWire[4]),
                "CALL_RING_START deadline changed");

        String[] targetStartWire = targetStart.split(CallManager.SEPARATOR, -1);
        check(targetStartWire.length == 5, "incoming CALL_RING_START field count changed");
        check(targetId.toString().equals(targetStartWire[2]),
                "incoming CALL_RING_START player changed");
        check("INCOMING".equals(targetStartWire[3]),
                "target did not receive the incoming ringtone");
        check(targetStartWire[4].equals(callerStartWire[4]),
                "caller and target received different ringtone deadlines");

        String[] callerStopWire = callerStop.split(CallManager.SEPARATOR, -1);
        String[] targetStopWire = targetStop.split(CallManager.SEPARATOR, -1);
        check(callerStopWire.length == 4 && targetStopWire.length == 4,
                "CALL_RING_STOP field count changed");
        check(CallManager.OP_CALL_RING_STOP.equals(callerStopWire[0]),
                "CALL_RING_STOP opcode changed");
        check(token.equals(callerStopWire[1]) && token.equals(targetStopWire[1]),
                "CALL_RING_STOP was not bound to its invitation token");
        check("OUTGOING".equals(callerStopWire[3])
                        && "INCOMING".equals(targetStopWire[3]),
                "CALL_RING_STOP direction changed");

        String anotherStop = CallManager.encodeCallRingStop(anotherToken, callerId,
                CallManager.RingDirection.OUTGOING);
        check(!callerStop.equals(anotherStop),
                "one outgoing invitation could stop another invitation's ringtone");
        check(!callerStart.contains(password) && !targetStart.contains(password)
                        && !callerStop.contains(password) && !targetStop.contains(password),
                "call password leaked into ringtone control messages");
        CallInviteRegistry.Invite retryInvite = new CallInviteRegistry.Invite(
                token, callerId, "Caller", targetId, "Target",
                new CallInviteRegistry.CallCredentials(UUID.randomUUID(), password,
                        expiresAtMillis + 10_000L),
                false, expiresAtMillis);
        java.util.List<String> retryArguments =
                CallManager.encodeCallRingStopRetryArguments(retryInvite);
        check(retryArguments.size() == 4,
                "CALL_RING_STOP retry argument count changed");
        check(CallManager.CONTROL_CHANNEL.equals(retryArguments.get(0)),
                "CALL_RING_STOP retry channel changed");
        check(callerStop.equals(retryArguments.get(1))
                        && targetStop.equals(retryArguments.get(2)),
                "CALL_RING_STOP retry was not token-bound to both players");
        check(Long.toString(expiresAtMillis).equals(retryArguments.get(3)),
                "CALL_RING_STOP retry did not retain the original deadline");
        check(retryArguments.stream().noneMatch(argument -> argument.contains(password)),
                "call password leaked into CALL_RING_STOP retry arguments");
        check(CallManager.INVITE_TIMEOUT_MILLIS == 30_000L,
                "call invitation no longer rings for exactly 30 seconds");
        check(CallManager.shouldRetryRingtoneStop(expiresAtMillis, expiresAtMillis - 1L),
                "CALL_RING_STOP retry ended before the invitation deadline");
        check(!CallManager.shouldRetryRingtoneStop(expiresAtMillis, expiresAtMillis),
                "CALL_RING_STOP retried at the invitation deadline");
        check(!CallManager.shouldRetryRingtoneStop(expiresAtMillis, expiresAtMillis + 1L),
                "CALL_RING_STOP retried beyond the invitation deadline");
        long normalRetryDelay = CallManager.ringtoneStopRetryDelayMillis(30_000L, 0L);
        check(normalRetryDelay == 2_000L,
                "CALL_RING_STOP retry interval changed");
        long finalRetryDelay = CallManager.ringtoneStopRetryDelayMillis(30_000L, 29_000L);
        check(finalRetryDelay > 0L && 29_000L + finalRetryDelay < 30_000L,
                "final CALL_RING_STOP retry was not bounded by the original deadline");
        check(CallManager.ringtoneStopRetryDelayMillis(30_000L, 30_000L) == 0L,
                "CALL_RING_STOP scheduled work at the original deadline");

        expectIllegalArgument(() -> CallManager.encodeCallRingStart(
                "too-short", callerId, CallManager.RingDirection.OUTGOING, expiresAtMillis));
        expectIllegalArgument(() -> CallManager.encodeCallRingStop(
                "AbCdEfGhIjKlMnOpQrStUv\0", callerId, CallManager.RingDirection.OUTGOING));
        expectIllegalArgument(() -> CallManager.encodeCallRingStart(
                token, callerId, CallManager.RingDirection.OUTGOING, 0L));
    }

    private static void expectIllegalArgument(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("invalid ringtone control message was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static CallInviteRegistry.CallCredentials credentials(long expiresAtMillis) {
        return new CallInviteRegistry.CallCredentials(
                UUID.randomUUID(), "server_only_password_1234567890", expiresAtMillis);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
