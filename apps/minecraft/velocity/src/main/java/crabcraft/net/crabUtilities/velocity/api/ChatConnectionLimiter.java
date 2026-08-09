package crabcraft.net.crabUtilities.velocity.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds long-lived public chat connections globally and per client IP. */
final class ChatConnectionLimiter {

    private final Semaphore totalSlots;
    private final int maxPerIp;
    private final ConcurrentHashMap<String, Integer> connectionsByIp = new ConcurrentHashMap<>();

    ChatConnectionLimiter(int maxTotal, int maxPerIp) {
        this.totalSlots = new Semaphore(Math.max(1, maxTotal));
        this.maxPerIp = Math.max(1, maxPerIp);
    }

    boolean tryAcquire(String ip) {
        if (!totalSlots.tryAcquire()) {
            return false;
        }

        AtomicBoolean acquired = new AtomicBoolean();
        connectionsByIp.compute(ip, (key, current) -> {
            int count = current == null ? 0 : current;
            if (count >= maxPerIp) {
                return current;
            }
            acquired.set(true);
            return count + 1;
        });

        if (!acquired.get()) {
            totalSlots.release();
        }
        return acquired.get();
    }

    void release(String ip) {
        AtomicBoolean released = new AtomicBoolean();
        connectionsByIp.computeIfPresent(ip, (key, current) -> {
            released.set(true);
            return current <= 1 ? null : current - 1;
        });
        if (released.get()) {
            totalSlots.release();
        }
    }
}
