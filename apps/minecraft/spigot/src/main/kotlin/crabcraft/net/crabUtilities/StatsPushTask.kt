package crabcraft.net.crabUtilities

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.awards.EatingAwardTracker
import crabcraft.net.crabUtilities.awards.XpLevelReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalInt
import java.util.concurrent.atomic.AtomicBoolean
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

/**
 * Snapshots live XP and eating scores on the server thread, then asynchronously publishes changed player saves to Redis
 * for Velocity awards and persistence. Offline XP comes from saved data.
 */
open class StatsPushTask(private val plugin: CrabUtilities) {
    private val host = plugin.config.getString("redis.host", "localhost")
    private val port = plugin.config.getInt("redis.port", 6379)
    private val password = plugin.config.getString("redis.password", "")
    private val season = plugin.config.getString("season", "")!!.trim { it <= ' ' }
    private val intervalMinutes = maxOf(1L, plugin.config.getLong("stats-push.interval-minutes", 5L))
    private val playersDirectory = playerStorageDirectory(plugin.server.levelDirectory)
    private val statsDir = playersDirectory.resolve("stats").toFile()
    private val advancementsDir = playersDirectory.resolve("advancements").toFile()
    private val playerDataDir = playersDirectory.resolve("data").toFile()
    private var jedisPool: JedisPool? = null
    private var task: BukkitTask? = null
    private val lastSeenMtime = HashMap<String, Long>()
    private val lastSeenPlayerDataMtime = HashMap<String, Long>()
    private val lastSeenXpLevel = HashMap<String, Int>()
    private val lastSeenEatingScores = HashMap<String, Map<String, Long>>()
    private val scanRunning = AtomicBoolean()
    @Volatile private var redisFailureLogged = false

    open fun start() {
        if (season.isEmpty()) {
            plugin.logger.info("Stats push DISABLED: 'season' is not set in config.yml")
            return
        }
        val poolConfig = JedisPoolConfig().apply { maxTotal = 2 }
        jedisPool =
            if (!password.isNullOrEmpty()) JedisPool(poolConfig, host, port, 2000, password)
            else JedisPool(poolConfig, host, port, 2000)
        task =
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable { startScan() }, 0L, TICKS_PER_MINUTE * intervalMinutes)
        plugin.logger.info(
            "Stats push scheduled every $intervalMinutes minute(s) on channel $CHANNEL; Redis will be retried asynchronously if unavailable."
        )
    }

    private fun startScan() {
        if (!scanRunning.compareAndSet(false, true)) return
        val onlineXpLevels = HashMap<String, Int>()
        val onlineEatingScores = HashMap<String, Map<String, Long>>()
        for (player in Bukkit.getOnlinePlayers()) {
            onlineXpLevels[player.uniqueId.toString()] = player.level
            try {
                onlineEatingScores[player.uniqueId.toString()] = EatingAwardTracker.scores(player)
            } catch (e: RuntimeException) {
                onlineEatingScores[player.uniqueId.toString()] = emptyMap()
                plugin.logger.warning("Could not read eating progress for ${player.uniqueId}: ${e.message}")
            }
        }
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                Runnable {
                    try {
                        scan(java.util.Map.copyOf(onlineXpLevels), java.util.Map.copyOf(onlineEatingScores))
                    } finally {
                        scanRunning.set(false)
                    }
                },
            )
    }

    private fun scan(onlineXpLevels: Map<String, Int>, onlineEatingScores: Map<String, Map<String, Long>>) {
        val pool = jedisPool ?: return
        if (pool.isClosed || !statsDir.isDirectory) return
        val files = statsDir.listFiles { _, name -> name.endsWith(".json") } ?: return
        var pushed = 0
        try {
            pool.resource.use { jedis ->
                if (redisFailureLogged) {
                    plugin.logger.info("Stats push Redis connection recovered.")
                    redisFailureLogged = false
                }
                for (file in files) {
                    val uuid = file.name.substring(0, file.name.length - ".json".length)
                    val playerDataFile = File(playerDataDir, "$uuid.dat")
                    val mtime = file.lastModified()
                    val playerDataMtime = if (playerDataFile.isFile) playerDataFile.lastModified() else 0L
                    val prev = lastSeenMtime[file.name]
                    val previousPlayerDataMtime = lastSeenPlayerDataMtime[uuid]
                    val liveXpLevel = onlineXpLevels[uuid]
                    val liveEatingScores = onlineEatingScores[uuid]
                    if (
                        prev != null &&
                            prev == mtime &&
                            previousPlayerDataMtime != null &&
                            previousPlayerDataMtime == playerDataMtime &&
                            !hasLiveXpLevelChanged(liveXpLevel, lastSeenXpLevel[uuid]) &&
                            !hasLiveEatingScoresChanged(liveEatingScores, lastSeenEatingScores[uuid])
                    )
                        continue
                    val raw =
                        try {
                            Files.readString(file.toPath())
                        } catch (e: IOException) {
                            plugin.logger.warning("Failed to read stats file ${file.name}: ${e.message}")
                            continue
                        }
                    // Parse once, rejecting malformed files before sending them.
                    val stats =
                        try {
                            JsonParser.parseString(raw)
                        } catch (_: Exception) {
                            plugin.logger.warning("Skipping malformed stats file ${file.name}")
                            continue
                        }
                    val envelope =
                        JsonObject().apply {
                            addProperty("season", season)
                            addProperty("uuid", uuid)
                            add("stats", stats)
                        }
                    val xpLevel = resolveXpLevel(liveXpLevel, playerDataFile.toPath())
                    val custom = JsonObject()
                    if (xpLevel.isPresent) custom.addProperty("xp_level", xpLevel.asInt)
                    else if (playerDataFile.isFile) plugin.logger.fine("Could not read XP level for $uuid")
                    val eatingScores =
                        liveEatingScores
                            ?: EatingAwardTracker.scores(playerDataFile.toPath(), envelope.getAsJsonObject("stats"))
                    eatingScores.forEach { (key, value) -> custom.addProperty(key, value) }
                    if (!custom.isEmpty) envelope.add("custom", custom)
                    val advFile = File(advancementsDir, "$uuid.json")
                    if (advFile.isFile) {
                        try {
                            val advJson = JsonParser.parseString(Files.readString(advFile.toPath())).asJsonObject
                            // Recipe unlocks are not advancements and bloat the payload.
                            advJson.keySet().removeIf { it.startsWith("minecraft:recipes/") }
                            envelope.add("advancements", advJson)
                        } catch (e: Exception) {
                            plugin.logger.fine("Could not read advancements for $uuid: ${e.message}")
                        }
                    }
                    jedis.publish(CHANNEL, envelope.toString())
                    lastSeenMtime[file.name] = mtime
                    lastSeenPlayerDataMtime[uuid] = playerDataMtime
                    xpLevel.ifPresent { lastSeenXpLevel[uuid] = it }
                    lastSeenEatingScores[uuid] = eatingScores
                    pushed++
                }
            }
        } catch (e: Exception) {
            if (!redisFailureLogged) {
                plugin.logger.warning("Stats push Redis unavailable; will retry on the next scan: ${e.message}")
                redisFailureLogged = true
            }
            return
        }
        if (pushed > 0) plugin.logger.info("Pushed $pushed stats update(s).")
    }

    open fun shutdown() {
        task?.cancel()
        task = null
        jedisPool
            ?.takeUnless { it.isClosed }
            ?.let {
                try {
                    it.close()
                } catch (_: NoClassDefFoundError) {}
                jedisPool = null
            }
    }

    companion object {
        private const val CHANNEL = "crabutilities:stats-push"
        private const val TICKS_PER_MINUTE = 20L * 60L

        @JvmStatic fun playerStorageDirectory(levelDirectory: Path): Path = levelDirectory.resolve("players")

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
