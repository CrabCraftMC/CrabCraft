package crabcraft.net.crabUtilities.velocity.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Thread-safe, in-process replay buffer and subscriber fan-out. */
final class PublicChatFeed implements AutoCloseable {

    private final int recentCapacity;
    private final int subscriberCapacity;
    private final Object stateLock = new Object();
    private final Deque<PublicChatEvent> recent = new ArrayDeque<>();
    private final Set<PublicChatSubscription> subscriptions = new LinkedHashSet<>();
    private boolean closed;

    PublicChatFeed(int recentCapacity, int subscriberCapacity) {
        if (recentCapacity < 1) {
            throw new IllegalArgumentException("recentCapacity must be positive");
        }
        if (subscriberCapacity < 1) {
            throw new IllegalArgumentException("subscriberCapacity must be positive");
        }
        this.recentCapacity = recentCapacity;
        this.subscriberCapacity = subscriberCapacity;
    }

    PublicChatSubscription subscribe(String lastEventId, int defaultReplay) {
        if (defaultReplay < 0) {
            throw new IllegalArgumentException("defaultReplay must not be negative");
        }
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("public chat feed is closed");

            PublicChatSubscription subscription =
                    new PublicChatSubscription(this, subscriberCapacity);
            for (PublicChatEvent event : replay(lastEventId, defaultReplay)) {
                subscription.offer(event);
            }
            subscriptions.add(subscription);
            return subscription;
        }
    }

    void publish(PublicChatEvent event) {
        synchronized (stateLock) {
            if (closed) return;

            PublicChatEvent newest = recent.peekLast();
            if (newest != null && PublicChatEvent.compareIds(event.id(), newest.id()) <= 0) {
                return;
            }

            recent.addLast(event);
            while (recent.size() > recentCapacity) {
                recent.removeFirst();
            }
            for (PublicChatSubscription subscription : subscriptions) {
                subscription.offer(event);
            }
        }
    }

    int recentCapacity() {
        return recentCapacity;
    }

    int subscriptionCount() {
        synchronized (stateLock) {
            return subscriptions.size();
        }
    }

    void unsubscribe(PublicChatSubscription subscription) {
        synchronized (stateLock) {
            subscriptions.remove(subscription);
        }
    }

    private List<PublicChatEvent> replay(String lastEventId, int defaultReplay) {
        List<PublicChatEvent> snapshot = new ArrayList<>(recent);
        if (PublicChatEvent.isStreamId(lastEventId)) {
            snapshot.removeIf(event -> PublicChatEvent.compareIds(event.id(), lastEventId) <= 0);
            return snapshot;
        }

        int count = Math.min(defaultReplay, snapshot.size());
        return snapshot.subList(snapshot.size() - count, snapshot.size());
    }

    @Override
    public void close() {
        List<PublicChatSubscription> toClose;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            toClose = new ArrayList<>(subscriptions);
            subscriptions.clear();
            recent.clear();
        }
        for (PublicChatSubscription subscription : toClose) {
            subscription.closeFromFeed();
        }
    }
}
