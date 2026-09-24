package crabcraft.net.crabUtilities.velocity.api

import java.util.ArrayDeque

/** Thread-safe, in-process replay buffer and subscriber fan-out. */
class PublicChatFeed(private val recentCapacity: Int, private val subscriberCapacity: Int) : AutoCloseable {
    private val stateLock = Any()
    private val recent = ArrayDeque<PublicChatEvent>()
    private val subscriptions = LinkedHashSet<PublicChatSubscription>()
    private var closed = false

    init {
        require(recentCapacity >= 1) { "recentCapacity must be positive" }
        require(subscriberCapacity >= 1) { "subscriberCapacity must be positive" }
    }

    fun subscribe(lastEventId: String?, defaultReplay: Int): PublicChatSubscription {
        require(defaultReplay >= 0) { "defaultReplay must not be negative" }
        synchronized(stateLock) {
            check(!closed) { "public chat feed is closed" }
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

    fun recentCapacity() = recentCapacity

    fun subscriptionCount(): Int = synchronized(stateLock) { subscriptions.size }

    fun unsubscribe(subscription: PublicChatSubscription) {
        synchronized(stateLock) { subscriptions.remove(subscription) }
    }

    private fun replay(lastEventId: String?, defaultReplay: Int): List<PublicChatEvent> {
        val snapshot = ArrayList(recent)
        if (PublicChatEvent.isStreamId(lastEventId)) {
            snapshot.removeIf { PublicChatEvent.compareIds(it.id(), lastEventId!!) <= 0 }
            return snapshot
        }
        val count = minOf(defaultReplay, snapshot.size)
        return snapshot.subList(snapshot.size - count, snapshot.size)
    }

    override fun close() {
        val toClose =
            synchronized(stateLock) {
                if (closed) return
                closed = true
                val snapshot = ArrayList(subscriptions)
                subscriptions.clear()
                recent.clear()
                snapshot
            }
        for (subscription in toClose) subscription.closeFromFeed()
    }
}
