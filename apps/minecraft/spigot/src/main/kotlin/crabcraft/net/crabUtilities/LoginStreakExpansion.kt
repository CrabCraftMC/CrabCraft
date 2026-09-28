package crabcraft.net.crabUtilities

import java.time.Duration
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer

/** Serves Velocity-owned login streak placeholders from memory, without request-time I/O. */
open class LoginStreakExpansion(private val plugin: CrabUtilities, private val cache: LoginStreakCache) :
    PlaceholderExpansion() {
    override fun getIdentifier() = "crabutilities"

    override fun getAuthor() = "Max"

    override fun getVersion(): String = plugin.description.version

    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (player == null) return ""
        if (!params.startsWith("streak_")) return null
        val snap = cache.get(player.uniqueId)
        val now = System.currentTimeMillis() / 1000L
        // Cached flags age while a player is offline; derive liveness from expiry.
        return when (params.substring("streak_".length)) {
            "current" -> (snap?.currentStreakAt(now) ?: 0).toString()
            "pending" -> (snap?.pendingStreak ?: 0).toString()
            "longest" -> (snap?.longestStreak ?: 0).toString()
            "active" -> (snap?.isActiveAt(now) == true).toString()
            "last_login" -> (snap?.lastLoginAt ?: 0L).toString()
            "started_at" -> (snap?.streakStartedAt ?: 0L).toString()
            "expires_at" -> (snap?.expiresAt ?: 0L).toString()
            "remaining_seconds" -> remaining(snap, now).toString()
            "remaining_pretty" -> prettyDuration(remaining(snap, now))
            else -> null
        }
    }

    private fun remaining(snap: LoginStreakCache.StreakSnapshot?, now: Long): Long =
        if (snap == null || !snap.isActiveAt(now)) 0L else maxOf(0L, snap.expiresAt - now)

    private fun prettyDuration(seconds: Long): String {
        if (seconds <= 0L) return "0m"
        val duration = Duration.ofSeconds(seconds)
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        return if (hours <= 0L) "${minutes}m" else "${hours}h ${minutes}m"
    }
}
