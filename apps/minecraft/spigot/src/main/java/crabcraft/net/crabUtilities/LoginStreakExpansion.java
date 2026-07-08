package crabcraft.net.crabUtilities;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * PlaceholderAPI bridge for the login streak data Velocity broadcasts
 * over Redis. All values are read from {@link LoginStreakCache}, so
 * placeholder rendering is a single map lookup with no I/O.
 *
 * <p>Supported placeholders (under the {@code crabutilities} identifier
 * — register one expansion per Spigot, plugin name controls the prefix):
 * <ul>
 *   <li>{@code %crabutilities_streak_current%} — live streak; 0 if lapsed.</li>
 *   <li>{@code %crabutilities_streak_pending%} — last recorded streak, even if it has now lapsed.</li>
 *   <li>{@code %crabutilities_streak_longest%} — all-time longest.</li>
 *   <li>{@code %crabutilities_streak_active%} — "true" / "false".</li>
 *   <li>{@code %crabutilities_streak_last_login%} — Unix seconds.</li>
 *   <li>{@code %crabutilities_streak_started_at%} — Unix seconds the current run began.</li>
 *   <li>{@code %crabutilities_streak_expires_at%} — Unix seconds the streak lapses if no rejoin.</li>
 *   <li>{@code %crabutilities_streak_remaining_seconds%} — seconds left before the streak lapses ({@code 0} once lapsed).</li>
 *   <li>{@code %crabutilities_streak_remaining_pretty%} — human-friendly remaining time, e.g. {@code "11h 32m"}.</li>
 * </ul>
 */
public class LoginStreakExpansion extends PlaceholderExpansion {

    private final CrabUtilities plugin;
    private final LoginStreakCache cache;

    public LoginStreakExpansion(CrabUtilities plugin, LoginStreakCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "crabutilities";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Max";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        if (!params.startsWith("streak_")) return null;
        String key = params.substring("streak_".length());

        LoginStreakCache.StreakSnapshot snap = cache.get(player.getUniqueId());
        long now = System.currentTimeMillis() / 1000L;

        // Re-derive liveness from expires_at on every request: the cached
        // payload's own active/current fields were computed when Velocity
        // last published and go stale while the player is offline.
        return switch (key) {
            case "current" -> Integer.toString(snap == null ? 0 : snap.currentStreakAt(now));
            case "pending" -> Integer.toString(snap == null ? 0 : snap.pendingStreak);
            case "longest" -> Integer.toString(snap == null ? 0 : snap.longestStreak);
            case "active" -> Boolean.toString(snap != null && snap.isActiveAt(now));
            case "last_login" -> Long.toString(snap == null ? 0L : snap.lastLoginAt);
            case "started_at" -> Long.toString(snap == null ? 0L : snap.streakStartedAt);
            case "expires_at" -> Long.toString(snap == null ? 0L : snap.expiresAt);
            case "remaining_seconds" -> Long.toString(remaining(snap, now));
            case "remaining_pretty" -> prettyDuration(remaining(snap, now));
            default -> null;
        };
    }

    private static long remaining(LoginStreakCache.StreakSnapshot snap, long now) {
        if (snap == null || !snap.isActiveAt(now)) return 0L;
        return Math.max(0L, snap.expiresAt - now);
    }

    private static String prettyDuration(long seconds) {
        if (seconds <= 0L) return "0m";
        Duration d = Duration.ofSeconds(seconds);
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours <= 0L) return minutes + "m";
        return hours + "h " + minutes + "m";
    }
}
