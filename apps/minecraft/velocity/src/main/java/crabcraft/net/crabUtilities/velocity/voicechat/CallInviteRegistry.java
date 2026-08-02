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
                  CallCredentials call, boolean callerWasInCall,
                  long expiresAtMillis) {}

    private final Map<String, Invite> byToken = new HashMap<>();
    private final Map<UUID, String> tokenByTarget = new HashMap<>();
    private final Map<UUID, CallCredentials> provisionalByCaller = new HashMap<>();

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
        Invite invite = byToken.get(token);
        if (invite == null || !invite.targetId().equals(targetId)) {
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

    synchronized Collection<Invite> removePlayer(UUID playerId) {
        Collection<Invite> removed = new ArrayList<>();
        for (Invite invite : new ArrayList<>(byToken.values())) {
            if (invite.callerId().equals(playerId) || invite.targetId().equals(playerId)) {
                remove(invite);
                removed.add(invite);
            }
        }
        provisionalByCaller.remove(playerId);
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
        CallCredentials existing = provisionalByCaller.get(callerId);
        if (existing != null && existing.expiresAtMillis() > nowMillis) {
            return existing;
        }
        CallCredentials created = factory.get();
        provisionalByCaller.put(callerId, created);
        return created;
    }

    /** Promote a provisional call and make its other invites require live membership. */
    synchronized void activate(UUID callerId, UUID groupId) {
        CallCredentials provisional = provisionalByCaller.get(callerId);
        if (provisional != null && provisional.groupId().equals(groupId)) {
            provisionalByCaller.remove(callerId);
        }
        for (Map.Entry<String, Invite> entry : new ArrayList<>(byToken.entrySet())) {
            Invite invite = entry.getValue();
            if (!invite.callerId().equals(callerId)
                    || !invite.call().groupId().equals(groupId)
                    || invite.callerWasInCall()) continue;
            byToken.put(entry.getKey(), new Invite(
                    invite.token(), invite.callerId(), invite.callerName(),
                    invite.targetId(), invite.targetName(), invite.call(), true,
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
}
