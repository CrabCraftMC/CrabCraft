package crabcraft.net.crabUtilities.velocity

import crabcraft.net.crabUtilities.velocity.db.LoginStreakService

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object LoginStreakCacheSeedRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val loads = AtomicInteger()
        val published = AtomicReference<LoginStreakService.StreakSnapshot>()
        val stored = LoginStreakService.StreakSnapshot(19, 19, 123L, 123L)

        ConnectionListener.seedLoginStreakCache({
            loads.incrementAndGet()
            stored
        }, published::set)

        check(loads.get() == 1, "stored streak was not loaded exactly once")
        check(published.get() === stored, "stored 19-day streak was not published on login")

        val missingPublishes = AtomicInteger()
        ConnectionListener.seedLoginStreakCache({ null }, { _ -> missingPublishes.incrementAndGet() })
        check(missingPublishes.get() == 0, "missing streak should not be published")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
