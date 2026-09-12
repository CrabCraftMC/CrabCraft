package crabcraft.net.crabUtilities.velocity.api

import java.util.Objects
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded stream of public chat events for one connected API client. */
class PublicChatSubscription(private val owner: PublicChatFeed, capacity: Int) : AutoCloseable {
    private val queue: BlockingQueue<Any> = ArrayBlockingQueue(capacity)
    private val queueLock = Any()
    private val closed = AtomicBoolean()

    init { Objects.requireNonNull(owner, "owner") }

    /** A null result is a timeout or closed subscription; callers can check isClosed(). */
    @Throws(InterruptedException::class)
    fun poll(timeout: Long, unit: TimeUnit): PublicChatEvent? {
        if (timeout < 0L) throw IllegalArgumentException("timeout must not be negative")
        Objects.requireNonNull(unit, "unit")
        return queue.poll(timeout, unit) as? PublicChatEvent
    }

    fun isClosed(): Boolean = closed.get()

    fun offer(event: PublicChatEvent) {
        Objects.requireNonNull(event, "event")
        synchronized(queueLock) {
            if (closed.get()) return
            while (!queue.offer(event)) queue.poll()
        }
    }

    fun closeFromFeed() { close(false) }
    override fun close() { close(true) }

    private fun close(removeFromOwner: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        if (removeFromOwner) owner.unsubscribe(this)
        synchronized(queueLock) {
            queue.clear()
            queue.offer(CLOSED)
        }
    }

    companion object { private val CLOSED = Any() }
}
