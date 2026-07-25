package crabcraft.net.crabUtilities.velocity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PendingJoinManager {

    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pendingJoins = new ConcurrentHashMap<>();

    public CompletableFuture<Void> register(UUID uuid) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        pendingJoins.put(uuid, future);
        return future;
    }

    public void complete(UUID uuid) {
        CompletableFuture<Void> future = pendingJoins.remove(uuid);
        if (future != null) {
            future.complete(null);
        }
    }

    public void remove(UUID uuid) {
        CompletableFuture<Void> future = pendingJoins.remove(uuid);
        if (future != null) {
            future.cancel(false);
        }
    }
}
