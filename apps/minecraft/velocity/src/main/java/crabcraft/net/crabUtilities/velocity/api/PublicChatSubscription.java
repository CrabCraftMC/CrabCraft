package crabcraft.net.crabUtilities.velocity.api;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded stream of public chat events for one connected API client. */
final class PublicChatSubscription implements AutoCloseable {

    private static final Object CLOSED = new Object();

    private final PublicChatFeed owner;
    private final BlockingQueue<Object> queue;
    private final Object queueLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    PublicChatSubscription(PublicChatFeed owner, int capacity) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Waits for the next event. A {@code null} result is either a timeout or a
     * closed subscription; callers can distinguish them with {@link #isClosed()}.
     */
    PublicChatEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0L) throw new IllegalArgumentException("timeout must not be negative");
        Objects.requireNonNull(unit, "unit");
        Object next = queue.poll(timeout, unit);
        return next instanceof PublicChatEvent event ? event : null;
    }

    boolean isClosed() {
        return closed.get();
    }

    void offer(PublicChatEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (queueLock) {
            if (closed.get()) return;
            while (!queue.offer(event)) {
                queue.poll();
            }
        }
    }

    void closeFromFeed() {
        close(false);
    }

    @Override
    public void close() {
        close(true);
    }

    private void close(boolean removeFromOwner) {
        if (!closed.compareAndSet(false, true)) return;
        if (removeFromOwner) {
            owner.unsubscribe(this);
        }
        synchronized (queueLock) {
            queue.clear();
            queue.offer(CLOSED);
        }
    }
}
