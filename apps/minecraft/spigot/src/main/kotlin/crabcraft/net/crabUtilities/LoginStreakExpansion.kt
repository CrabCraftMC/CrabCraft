package crabcraft.net.crabUtilities

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.time.Duration

/** PlaceholderAPI bridge; all values come from the in-memory login streak cache. */
open class LoginStreakExpansion(private val plugin: CrabUtilities, private val cache: LoginStreakCache) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "crabutilities"
    override fun getAuthor(): String = "Max"
    override fun getVersion(): String = plugin.getDescription().getVersion()
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return ""
        if (!params.startsWith("streak_")) return null
        val key = params.substring("streak_".length)
        val snap = cache.get(player.getUniqueId())
        val now = System.currentTimeMillis() / 1000L
        // Re-derive liveness from expiry because cached flags can go stale.
        return when (key) {
            "current" -> (snap?.currentStreakAt(now) ?: 0).toString()
            "pending" -> (snap?.pendingStreak ?: 0).toString()
            "longest" -> (snap?.longestStreak ?: 0).toString()
            "active" -> (snap != null && snap.isActiveAt(now)).toString()
            "last_login" -> (snap?.lastLoginAt ?: 0L).toString()
            "started_at" -> (snap?.streakStartedAt ?: 0L).toString()
            "expires_at" -> (snap?.expiresAt ?: 0L).toString()
            "remaining_seconds" -> remaining(snap, now).toString()
            "remaining_pretty" -> prettyDuration(remaining(snap, now))
            else -> null
        }
    }

    companion object {
        private fun remaining(snap: LoginStreakCache.StreakSnapshot?, now: Long): Long {
            if (snap == null || !snap.isActiveAt(now)) return 0L
            return maxOf(0L, snap.expiresAt - now)
        }
        private fun prettyDuration(seconds: Long): String {
            if (seconds <= 0L) return "0m"
            val d = Duration.ofSeconds(seconds)
            val hours = d.toHours()
            val minutes = d.toMinutesPart()
            return if (hours <= 0L) "${minutes}m" else "${hours}h ${minutes}m"
        }
    }
}
