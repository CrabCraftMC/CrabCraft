package crabcraft.net.crabUtilities

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.awards.EatingAwardTracker
import crabcraft.net.crabUtilities.awards.XpLevelReader
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalInt
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes changed saved player statistics to Redis for Velocity award scores.
 * Online XP and eating scores are captured on the server thread; saved data is
 * scanned asynchronously. Unchanged players are skipped.
 */
open class StatsPushTask(private val plugin: CrabUtilities) {
    private val host = plugin.getConfig().getString("redis.host", "localhost")
    private val port = plugin.getConfig().getInt("redis.port", 6379)
    private val password = plugin.getConfig().getString("redis.password", "")
    private val season = plugin.getConfig().getString("season", "")!!.trim { it <= ' ' }
    private val intervalMinutes = maxOf(1L, plugin.getConfig().getLong("stats-push.interval-minutes", 5L))
    private val statsDir: File
    private val advancementsDir: File
    private val playerDataDir: File
    private var jedisPool: JedisPool? = null
    private var task: BukkitTask? = null
    private val lastSeenMtime = HashMap<String, Long>()
    private val lastSeenPlayerDataMtime = HashMap<String, Long>()
    private val lastSeenXpLevel = HashMap<String, Int>()
    private val lastSeenEatingScores = HashMap<String, Map<String, Long>>()
    private val scanRunning = AtomicBoolean()
    @Volatile private var redisFailureLogged = false

    init {
        val playersDirectory = playerStorageDirectory(plugin.getServer().getLevelDirectory())
        statsDir = playersDirectory.resolve("stats").toFile()
        advancementsDir = playersDirectory.resolve("advancements").toFile()
        playerDataDir = playersDirectory.resolve("data").toFile()
    }

    open fun start() {
        if (season.isEmpty()) {
            plugin.getLogger().info("Stats push DISABLED: 'season' is not set in config.yml")
            return
        }
        val poolConfig = JedisPoolConfig()
        poolConfig.setMaxTotal(2)
        jedisPool = if (!password.isNullOrEmpty()) {
            JedisPool(poolConfig, host, port, 2000, password)
        } else {
            JedisPool(poolConfig, host, port, 2000)
        }
        val intervalTicks = TICKS_PER_MINUTE * intervalMinutes
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { startScan() }, 0L, intervalTicks)
        plugin.getLogger().info(
            "Stats push scheduled every " + intervalMinutes + " minute(s) on channel " + CHANNEL +
                "; Redis will be retried asynchronously if unavailable.")
    }

    private fun startScan() {
        if (!scanRunning.compareAndSet(false, true)) return
        val onlineXpLevels = HashMap<String, Int>()
        val onlineEatingScores = HashMap<String, Map<String, Long>>()
        for (player in Bukkit.getOnlinePlayers()) {
            onlineXpLevels[player.getUniqueId().toString()] = player.getLevel()
            try {
                onlineEatingScores[player.getUniqueId().toString()] = EatingAwardTracker.scores(player)
            } catch (e: RuntimeException) {
                onlineEatingScores[player.getUniqueId().toString()] = emptyMap()
                plugin.getLogger().warning("Could not read eating progress for " + player.getUniqueId() + ": " + e.message)
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                scan(java.util.Map.copyOf(onlineXpLevels), java.util.Map.copyOf(onlineEatingScores))
            } finally {
                scanRunning.set(false)
            }
        })
    }

    /** Pushes changed players/stats/<uuid>.json files from the level directory. */
    private fun scan(onlineXpLevels: Map<String, Int>, onlineEatingScores: Map<String, Map<String, Long>>) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        if (!statsDir.isDirectory) return
        val files = statsDir.listFiles { _, name -> name.endsWith(".json") } ?: return
        var pushed = 0
        try {
            pool.resource.use { jedis ->
                if (redisFailureLogged) {
                    plugin.getLogger().info("Stats push Redis connection recovered.")
                    redisFailureLogged = false
                }
                for (file in files) {
                    val uuid = file.name.substring(0, file.name.length - ".json".length)
                    val playerDataFile = File(playerDataDir, uuid + ".dat")
                    val mtime = file.lastModified()
                    val playerDataMtime = if (playerDataFile.isFile) playerDataFile.lastModified() else 0L
                    val prev = lastSeenMtime[file.name]
                    val previousPlayerDataMtime = lastSeenPlayerDataMtime[uuid]
                    val liveXpLevel = onlineXpLevels[uuid]
                    val liveEatingScores = onlineEatingScores[uuid]
                    if (prev != null && prev == mtime && previousPlayerDataMtime != null &&
                        previousPlayerDataMtime == playerDataMtime &&
                        !hasLiveXpLevelChanged(liveXpLevel, lastSeenXpLevel[uuid]) &&
                        !hasLiveEatingScoresChanged(liveEatingScores, lastSeenEatingScores[uuid])) {
                        continue
                    }
                    val raw: String
                    try {
                        raw = Files.readString(file.toPath())
                    } catch (e: IOException) {
                        plugin.getLogger().warning("Failed to read stats file " + file.name + ": " + e.message)
                        continue
                    }
                    // Reject malformed files before publishing them.
                    try {
                        JsonParser.parseString(raw)
                    } catch (e: Exception) {
                        plugin.getLogger().warning("Skipping malformed stats file " + file.name)
                        continue
                    }
                    val envelope = JsonObject()
                    envelope.addProperty("season", season)
                    envelope.addProperty("uuid", uuid)
                    envelope.add("stats", JsonParser.parseString(raw))
                    val xpLevel = resolveXpLevel(liveXpLevel, playerDataFile.toPath())
                    val custom = JsonObject()
                    if (xpLevel.isPresent) {
                        custom.addProperty("xp_level", xpLevel.asInt)
                    } else if (playerDataFile.isFile) {
                        plugin.getLogger().fine("Could not read XP level for " + uuid)
                    }
                    val eatingScores = liveEatingScores ?: EatingAwardTracker.scores(
                        playerDataFile.toPath(), envelope.getAsJsonObject("stats"))
                    eatingScores.forEach { (key, value) -> custom.addProperty(key, value) }
                    if (!custom.isEmpty) envelope.add("custom", custom)
                    // Include advancements if available for this player.
                    val advFile = File(advancementsDir, uuid + ".json")
                    if (advFile.isFile) {
                        try {
                            val advRaw = Files.readString(advFile.toPath())
                            val advJson = JsonParser.parseString(advRaw).asJsonObject
                            // Strip recipe unlocks; they are not real advancements.
                            advJson.keySet().removeIf { it.startsWith("minecraft:recipes/") }
                            envelope.add("advancements", advJson)
                        } catch (e: Exception) {
                            // Advancement read failure is non-fatal.
                            plugin.getLogger().fine("Could not read advancements for " + uuid + ": " + e.message)
                        }
                    }
                    jedis.publish(CHANNEL, envelope.toString())
                    lastSeenMtime[file.name] = mtime
                    lastSeenPlayerDataMtime[uuid] = playerDataMtime
                    xpLevel.ifPresent { level -> lastSeenXpLevel[uuid] = level }
                    lastSeenEatingScores[uuid] = eatingScores
                    pushed++
                }
            }
        } catch (e: Exception) {
            if (!redisFailureLogged) {
                plugin.getLogger().warning("Stats push Redis unavailable; will retry on the next scan: " + e.message)
                redisFailureLogged = true
            }
            return
        }
        if (pushed > 0) plugin.getLogger().info("Pushed " + pushed + " stats update(s).")
    }

    open fun shutdown() {
        task?.let {
            it.cancel()
            task = null
        }
        jedisPool?.let {
            if (!it.isClosed) {
                try { it.close() } catch (ignored: NoClassDefFoundError) {}
                jedisPool = null
            }
        }
    }

    companion object {
        private const val CHANNEL = "crabutilities:stats-push"
        private const val TICKS_PER_MINUTE = 20L * 60L

        @JvmStatic
        fun playerStorageDirectory(levelDirectory: Path): Path = levelDirectory.resolve("players")

        @JvmStatic
        fun resolveXpLevel(liveXpLevel: Int?, playerDataFile: Path): OptionalInt =
            if (liveXpLevel == null) XpLevelReader.read(playerDataFile) else OptionalInt.of(liveXpLevel)

        @JvmStatic
        fun hasLiveXpLevelChanged(liveXpLevel: Int?, previousXpLevel: Int?): Boolean =
            liveXpLevel != null && liveXpLevel != previousXpLevel

        @JvmStatic
        fun hasLiveEatingScoresChanged(liveScore: Map<String, Long>?, previousScore: Map<String, Long>?): Boolean =
            liveScore != null && liveScore != previousScore
    }
}
