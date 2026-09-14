package crabcraft.net.crabUtilities.velocity.api

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Deque
import java.util.LinkedHashSet

/** Thread-safe, in-process replay buffer and subscriber fan-out. */
class PublicChatFeed(private val recentCapacity: Int, private val subscriberCapacity: Int) : AutoCloseable {
    private val stateLock = Any()
    private val recent: Deque<PublicChatEvent> = ArrayDeque()
    private val subscriptions = LinkedHashSet<PublicChatSubscription>()
    private var closed = false

    init {
        if (recentCapacity < 1) throw IllegalArgumentException("recentCapacity must be positive")
        if (subscriberCapacity < 1) throw IllegalArgumentException("subscriberCapacity must be positive")
    }

    fun subscribe(lastEventId: String?, defaultReplay: Int): PublicChatSubscription {
        if (defaultReplay < 0) throw IllegalArgumentException("defaultReplay must not be negative")
        synchronized(stateLock) {
            if (closed) throw IllegalStateException("public chat feed is closed")
            val subscription = PublicChatSubscription(this, subscriberCapacity)
            for (event in replay(lastEventId, defaultReplay)) subscription.offer(event)
            subscriptions.add(subscription)
            return subscription
        }
    }

    fun publish(event: PublicChatEvent) {
        synchronized(stateLock) {
            if (closed) return
            val newest = recent.peekLast()
            if (newest != null && PublicChatEvent.compareIds(event.id(), newest.id()) <= 0) return
            recent.addLast(event)
            while (recent.size > recentCapacity) recent.removeFirst()
            for (subscription in subscriptions) subscription.offer(event)
        }
    }

    fun recentCapacity(): Int = recentCapacity
    fun subscriptionCount(): Int = synchronized(stateLock) { subscriptions.size }

    fun unsubscribe(subscription: PublicChatSubscription) {
        synchronized(stateLock) { subscriptions.remove(subscription) }
    }

    private fun replay(lastEventId: String?, defaultReplay: Int): List<PublicChatEvent> {
        val snapshot = ArrayList(recent)
        if (PublicChatEvent.isStreamId(lastEventId)) {
            snapshot.removeIf { event -> PublicChatEvent.compareIds(event.id(), lastEventId!!) <= 0 }
            return snapshot
        }
        val count = Math.min(defaultReplay, snapshot.size)
        return snapshot.subList(snapshot.size - count, snapshot.size)
    }

    override fun close() {
        val toClose: List<PublicChatSubscription>
        synchronized(stateLock) {
            if (closed) return
            closed = true
            toClose = ArrayList(subscriptions)
            subscriptions.clear()
            recent.clear()
        }
        for (subscription in toClose) subscription.closeFromFeed()
    }
}
