package crabcraft.net.crabUtilities.velocity.voicechat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Thread-safe in-memory state for short-lived call invitations. */
final class CallInviteRegistry {

    record CallCredentials(UUID groupId, String password, long expiresAtMillis) {}

    record Invite(String token, UUID callerId, String callerName,
                  UUID targetId, String targetName,
                  Object callerSession, Object targetSession,
                  CallCredentials call, boolean callerWasInCall,
                  long expiresAtMillis) {
        Invite(String token, UUID callerId, String callerName,
               UUID targetId, String targetName,
               CallCredentials call, boolean callerWasInCall,
               long expiresAtMillis) {
            this(token, callerId, callerName, targetId, targetName,
                    null, null, call, callerWasInCall, expiresAtMillis);
        }
    }

    private final Map<String, Invite> byToken = new HashMap<>();
    private final Map<UUID, String> tokenByTarget = new HashMap<>();
    private final Map<UUID, ProvisionalCall> provisionalByCaller = new HashMap<>();

    synchronized boolean add(Invite invite, long nowMillis) {
        removeExpiredTarget(invite.targetId(), nowMillis);
        if (tokenByTarget.containsKey(invite.targetId()) || byToken.containsKey(invite.token())) {
            return false;
        }
        byToken.put(invite.token(), invite);
        tokenByTarget.put(invite.targetId(), invite.token());
        return true;
    }

    synchronized Optional<Invite> take(String token, UUID targetId, long nowMillis) {
        return take(token, targetId, null, nowMillis);
    }

    synchronized Optional<Invite> take(String token, UUID targetId,
                                       Object targetSession, long nowMillis) {
        Invite invite = byToken.get(token);
        if (invite == null || !invite.targetId().equals(targetId)
                || (invite.targetSession() != null
                        && invite.targetSession() != targetSession)) {
            return Optional.empty();
        }
        remove(invite);
        if (invite.expiresAtMillis() <= nowMillis) {
            return Optional.empty();
        }
        return Optional.of(invite);
    }

    synchronized Optional<Invite> expire(String token, long nowMillis) {
        Invite invite = byToken.get(token);
        if (invite == null || invite.expiresAtMillis() > nowMillis) {
            return Optional.empty();
        }
        remove(invite);
        return Optional.of(invite);
    }

    synchronized Optional<Invite> remove(String token, UUID targetId) {
        Invite invite = byToken.get(token);
        if (invite == null || !invite.targetId().equals(targetId)) {
            return Optional.empty();
        }
        remove(invite);
        return Optional.of(invite);
    }

    synchronized boolean isPending(String token, UUID targetId, long nowMillis) {
        Invite invite = byToken.get(token);
        return invite != null
                && invite.targetId().equals(targetId)
                && invite.expiresAtMillis() > nowMillis;
    }

    synchronized boolean hasOutgoingCapacity(UUID callerId, int maximum, long nowMillis) {
        int active = 0;
        for (Invite invite : byToken.values()) {
            if (invite.callerId().equals(callerId) && invite.expiresAtMillis() > nowMillis) {
                active++;
            }
        }
        return active < maximum;
    }

    synchronized Collection<Invite> removeSession(UUID playerId, Object session) {
        Collection<Invite> removed = new ArrayList<>();
        for (Invite invite : new ArrayList<>(byToken.values())) {
            boolean callerDisconnected = invite.callerId().equals(playerId)
                    && invite.callerSession() == session;
            boolean targetDisconnected = invite.targetId().equals(playerId)
                    && invite.targetSession() == session;
            if (callerDisconnected || targetDisconnected) {
                remove(invite);
                removed.add(invite);
            }
        }
        ProvisionalCall provisional = provisionalByCaller.get(playerId);
        if (provisional != null && provisional.session() == session) {
            provisionalByCaller.remove(playerId, provisional);
        }
        return removed;
    }

    synchronized Collection<Invite> clear() {
        Collection<Invite> removed = new ArrayList<>(byToken.values());
        byToken.clear();
        tokenByTarget.clear();
        provisionalByCaller.clear();
        return removed;
    }

    synchronized CallCredentials provisionalFor(UUID callerId, long nowMillis,
                                                Supplier<CallCredentials> factory) {
        return provisionalFor(callerId, null, nowMillis, factory);
    }

    synchronized CallCredentials provisionalFor(UUID callerId, Object callerSession,
                                                 long nowMillis,
                                                 Supplier<CallCredentials> factory) {
        ProvisionalCall existing = provisionalByCaller.get(callerId);
        if (existing != null && existing.session() == callerSession
                && existing.credentials().expiresAtMillis() > nowMillis) {
            return existing.credentials();
        }
        CallCredentials created = factory.get();
        provisionalByCaller.put(callerId, new ProvisionalCall(callerSession, created));
        return created;
    }

    /** Promote a provisional call and make its other invites require live membership. */
    synchronized void activate(UUID callerId, UUID groupId) {
        ProvisionalCall provisional = provisionalByCaller.get(callerId);
        if (provisional != null && provisional.credentials().groupId().equals(groupId)) {
            provisionalByCaller.remove(callerId);
        }
        for (Map.Entry<String, Invite> entry : new ArrayList<>(byToken.entrySet())) {
            Invite invite = entry.getValue();
            if (!invite.callerId().equals(callerId)
                    || !invite.call().groupId().equals(groupId)
                    || invite.callerWasInCall()) continue;
            byToken.put(entry.getKey(), new Invite(
                    invite.token(), invite.callerId(), invite.callerName(),
                    invite.targetId(), invite.targetName(),
                    invite.callerSession(), invite.targetSession(), invite.call(), true,
                    invite.expiresAtMillis()));
        }
    }

    private void removeExpiredTarget(UUID targetId, long nowMillis) {
        String token = tokenByTarget.get(targetId);
        if (token == null) return;
        Invite invite = byToken.get(token);
        if (invite != null && invite.expiresAtMillis() <= nowMillis) {
            remove(invite);
        }
    }

    private void remove(Invite invite) {
        byToken.remove(invite.token(), invite);
        tokenByTarget.remove(invite.targetId(), invite.token());
    }

    private record ProvisionalCall(Object session, CallCredentials credentials) {}
}
