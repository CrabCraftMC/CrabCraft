package crabcraft.net.crabUtilities.velocity.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Bounds long-lived public chat connections globally and per client IP. */
class ChatConnectionLimiter(maxTotal: Int, maxPerIp: Int) {
    private val totalSlots = Semaphore(Math.max(1, maxTotal))
    private val maxPerIp = Math.max(1, maxPerIp)
    private val connectionsByIp = ConcurrentHashMap<String, Int>()

    fun tryAcquire(ip: String): Boolean {
        if (!totalSlots.tryAcquire()) return false
        val acquired = AtomicBoolean()
        connectionsByIp.compute(ip) { _, current ->
            val count = current ?: 0
            if (count >= maxPerIp) {
                current
            } else {
                acquired.set(true)
                count + 1
            }
        }
        if (!acquired.get()) totalSlots.release()
        return acquired.get()
    }

    fun release(ip: String) {
        val released = AtomicBoolean()
        connectionsByIp.computeIfPresent(ip) { _, current ->
            released.set(true)
            if (current <= 1) null else current - 1
        }
        if (released.get()) totalSlots.release()
    }
}
