package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonObject
import crabcraft.net.crabUtilities.velocity.db.LoginStreakService
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool


/**
 * Mirrors login streak snapshots into Redis so backend Spigot servers
 * can render them through PlaceholderAPI without each one talking to
 * Postgres.
 *
 * <p>Two writes per recorded login:
 * <ul>
 *   <li>{@code HSET crabutilities:streaks &ltuuid&gt &ltjson&gt} —
 *       authoritative cache the backends seed from on player join.</li>
 *   <li>{@code PUBLISH crabutilities:streaks-updates &ltjson&gt} —
 *       live notification for backends to refresh their in-memory
 *       cache on the spot.</li>
 * </ul>
 *
 * <p>The published payload always includes the player UUID so a single
 * subscription handles all players.
 */
open class LoginStreakPublisher(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private var jedisPool: JedisPool? = null
    @Volatile private var redisFailureLogged = false
    init { connect() }
    private fun connect() {
        jedisPool = RedisPools.create(config, 2)
        plugin.getLogger().info("Login streak publisher ready; Redis will be retried on publish if unavailable.")
    }
    open fun publish(uuid: String, snapshot: LoginStreakService.StreakSnapshot?, resetHourUtc: Int) {
        val pool = jedisPool
        if (pool == null || pool.isClosed || snapshot == null) return
        val now = System.currentTimeMillis() / 1000L
        val expiresAt = LoginStreakService.expiryOf(snapshot.lastLoginAt, resetHourUtc)
        val active = now < expiresAt
        val payload = JsonObject()
        payload.addProperty("uuid", uuid)
        payload.addProperty("current_streak", if (active) snapshot.currentStreak else 0)
        payload.addProperty("pending_streak", snapshot.currentStreak)
        payload.addProperty("longest_streak", snapshot.longestStreak)
        payload.addProperty("last_login_at", snapshot.lastLoginAt)
        payload.addProperty("streak_started_at", snapshot.streakStartedAt)
        payload.addProperty("expires_at", expiresAt)
        payload.addProperty("active", active)
        val body = payload.toString()
        try {
            pool.resource.use { jedis ->
                jedis.eval(PUBLISH_SCRIPT, listOf(HASH_KEY), listOf(uuid, snapshot.lastLoginAt.toString(), body, UPDATE_CHANNEL))
                if (redisFailureLogged) { plugin.getLogger().info("Login streak Redis publisher recovered."); redisFailureLogged = false }
            }
        } catch (e: Exception) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Failed to publish login streak for {}; will retry on later logins", uuid, e)
                redisFailureLogged = true
            } else plugin.getLogger().debug("Failed to publish login streak for {}: {}", uuid, e.message)
        }
    }
    open fun shutdown() {
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try { pool.close() } catch (_: NoClassDefFoundError) {}
            jedisPool = null
        }
    }
    companion object {
        const val HASH_KEY = "crabutilities:streaks"
        const val UPDATE_CHANNEL = "crabutilities:streaks-updates"
        private val PUBLISH_SCRIPT = """local current = redis.call('HGET', KEYS[1], ARGV[1])
if current then
    local ok, decoded = pcall(cjson.decode, current)
    if ok and type(decoded) == 'table'
            and (tonumber(decoded['last_login_at']) or 0) > tonumber(ARGV[2]) then
        return 0
    end
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
redis.call('PUBLISH', ARGV[4], ARGV[3])
return 1
"""
    }
}
