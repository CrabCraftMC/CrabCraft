package crabcraft.net.crabUtilities.velocity

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

open class PendingJoinManager {
    private val pendingJoins = ConcurrentHashMap<UUID, CompletableFuture<Void>>()
    open fun register(uuid: UUID): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        pendingJoins[uuid] = future
        return future
    }
    open fun complete(uuid: UUID) { pendingJoins.remove(uuid)?.complete(null) }
    open fun remove(uuid: UUID) { pendingJoins.remove(uuid)?.cancel(false) }
}
