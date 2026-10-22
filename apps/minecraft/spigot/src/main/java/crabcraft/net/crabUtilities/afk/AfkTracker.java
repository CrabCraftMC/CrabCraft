package crabcraft.net.crabUtilities.afk;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread activity updates with thread-safe placeholder reads. */
final class AfkTracker {

    private final long idleNanos;
    private final Map<UUID, State> players = new ConcurrentHashMap<>();

    AfkTracker(long idleNanos) {
        this.idleNanos = idleNanos;
    }

    void add(UUID uuid, long now) {
        players.put(uuid, new State(now, false));
    }

    boolean recordActivity(UUID uuid, long now) {
        State previous = players.get(uuid);
        if (previous == null) return false;
        players.put(uuid, new State(now, false));
        return previous.afk();
    }

    boolean toggle(UUID uuid, long now) {
        State previous = players.get(uuid);
        boolean afk = previous == null || !previous.afk();
        players.put(uuid, new State(now, afk));
        return afk;
    }

    boolean markIdle(UUID uuid, long now) {
        State previous = players.get(uuid);
        if (previous == null || previous.afk() || now - previous.lastActivity() < idleNanos) {
            return false;
        }
        players.put(uuid, new State(previous.lastActivity(), true));
        return true;
    }

    boolean isAfk(UUID uuid) {
        State state = players.get(uuid);
        return state != null && state.afk();
    }

    void remove(UUID uuid) {
        players.remove(uuid);
    }

    void clear() {
        players.clear();
    }

    private record State(long lastActivity, boolean afk) {
    }
}
