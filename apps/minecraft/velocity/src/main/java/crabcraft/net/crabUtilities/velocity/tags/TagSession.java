package crabcraft.net.crabUtilities.velocity.tags;

import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.tags.TagSnapshot;

import java.util.ArrayDeque;
import java.util.UUID;

/** State and bounded work for one proxy login, never shared with a later login. */
final class TagSession {
    static final int MAX_PENDING = 8;

    final Player player;
    final UUID playerId;
    final ArrayDeque<Work> pending = new ArrayDeque<>();
    boolean running;
    boolean refreshing;
    boolean mutationPending;
    private TagSnapshot snapshot;

    TagSession(Player player, UUID playerId) {
        this.player = player;
        this.playerId = playerId;
    }

    synchronized TagSnapshot accept(TagSnapshot next) {
        if (!playerId.equals(next.playerId())) {
            throw new IllegalArgumentException("Tag snapshot belongs to another player");
        }
        if (snapshot == null || next.revision() >= snapshot.revision()) {
            snapshot = next;
        }
        return snapshot;
    }

    record Work(Runnable action, Runnable rejected) {}
}
