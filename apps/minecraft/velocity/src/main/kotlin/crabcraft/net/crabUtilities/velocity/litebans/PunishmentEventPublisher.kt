package crabcraft.net.crabUtilities.velocity.litebans

import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import litebans.api.Entry
import litebans.api.Events
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.XAddParams
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Watches active LiteBans ban/mute state and publishes state-change events to a
 * Redis Stream for the Discord bot. The bot still performs periodic REST
 * reconciliation, so missed stream messages or watcher downtime only delay sync.
 */
class PunishmentEventPublisher(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private val streamName = config.getRedisPunishmentStream()
    private var jedisPool: JedisPool? = null
    private var executor: ScheduledExecutorService? = null
    private var liteBansListener: Events.Listener? = null
    private var lastActiveUuids: MutableSet<String>? = null
    @Volatile private var redisFailureLogged = false
    @Volatile private var liteBansFailureLogged = false
    @Volatile private var liteBansEventFailureLogged = false

    fun start() {
        jedisPool = RedisPools.create(config)
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "crabutilities-punishment-events").apply { isDaemon = true }
        }
        this.executor = executor
        registerLiteBansListener()
        executor.scheduleWithFixedDelay({ pollAndPublish("watch") }, 0L,
            config.getRedisPunishmentWatchIntervalSeconds(), TimeUnit.SECONDS)
        plugin.getLogger().info("Punishment event publisher started on Redis stream {} (LiteBans events + {}s fallback watch interval).",
            streamName, config.getRedisPunishmentWatchIntervalSeconds())
    }

    fun shutdown() {
        unregisterLiteBansListener()
        executor?.shutdownNow()
        executor = null
        jedisPool?.close()
        jedisPool = null
        lastActiveUuids = null
    }

    private fun registerLiteBansListener() {
        try {
            val listener = object : Events.Listener() {
                override fun entryAdded(entry: Entry?) {
                    handleEntryAdded(entry)
                }

                override fun entryRemoved(entry: Entry?) {
                    handleEntryRemoved(entry)
                }
            }
            liteBansListener = listener
            Events.get().register(listener)
            plugin.getLogger().info("Punishment event publisher registered LiteBans event listener.")
            liteBansEventFailureLogged = false
        } catch (e: Throwable) {
            if (!liteBansEventFailureLogged) {
                plugin.getLogger().warn("Punishment event publisher could not register LiteBans events; fallback watch interval remains active.", e)
                liteBansEventFailureLogged = true
            }
            liteBansListener = null
        }
    }

    private fun unregisterLiteBansListener() {
        val listener = liteBansListener ?: return
        try {
            Events.get().unregister(listener)
        } catch (e: Throwable) {
            plugin.getLogger().debug("Failed to unregister LiteBans punishment event listener: {}", e.message)
        } finally {
            liteBansListener = null
        }
    }

    private fun handleEntryAdded(entry: Entry?) {
        val type = getEntryType(entry)
        if (type == null) {
            triggerDiff("litebans-event")
            return
        }
        if (!isTrackedPunishmentType(type)) return
        val uuid = normalizeEntryUuid(entry!!.uuid)
        if (uuid == null || !entry.isActive) {
            triggerDiff("litebans-event")
            return
        }
        val currentExecutor = executor ?: return
        if (currentExecutor.isShutdown) return
        currentExecutor.execute {
            if (publish(uuid, true, "litebans-event")) lastActiveUuids?.add(uuid)
        }
    }

    private fun handleEntryRemoved(entry: Entry?) {
        val type = getEntryType(entry)
        if (type != null && !isTrackedPunishmentType(type)) return
        val uuid = normalizeEntryUuid(entry!!.uuid)
        if (uuid == null) {
            triggerDiff("litebans-event")
            return
        }
        val currentExecutor = executor ?: return
        if (currentExecutor.isShutdown) return
        currentExecutor.execute { publishCurrentPunishmentState(uuid, "litebans-event") }
    }

    private fun triggerDiff(source: String) {
        val currentExecutor = executor ?: return
        if (currentExecutor.isShutdown) return
        currentExecutor.execute { pollAndPublish(source) }
    }

    private fun pollAndPublish(source: String) {
        val service = plugin.getLiteBansInfractionService() ?: return
        val current: Set<String>
        try {
            current = service.getAllActivePunishedUuids()
            if (liteBansFailureLogged) {
                plugin.getLogger().info("Punishment event publisher recovered LiteBans access.")
                liteBansFailureLogged = false
            }
        } catch (e: Exception) {
            if (e !is LiteBansInfractionService.LiteBansUnavailableException && e !is SQLException) throw e
            if (!liteBansFailureLogged) {
                plugin.getLogger().warn("Punishment event publisher cannot query LiteBans; REST reconcile will cover gaps.", e)
                liteBansFailureLogged = true
            } else {
                plugin.getLogger().debug("Punishment event publisher LiteBans query failed: {}", e.message)
            }
            return
        }

        val previous = lastActiveUuids
        val next = LinkedHashSet(current)
        var publishedAllDeltas = true
        if (previous == null) {
            for (uuid in current) publishedAllDeltas = publishedAllDeltas and publish(uuid, true, "snapshot")
            if (publishedAllDeltas) lastActiveUuids = next
            return
        }

        for (uuid in current) {
            if (!previous.contains(uuid)) publishedAllDeltas = publishedAllDeltas and publish(uuid, true, source)
        }
        for (uuid in previous) {
            if (!current.contains(uuid)) publishedAllDeltas = publishedAllDeltas and publish(uuid, false, source)
        }
        if (publishedAllDeltas) lastActiveUuids = next
    }

    private fun publishCurrentPunishmentState(uuid: String, source: String) {
        val service = plugin.getLiteBansInfractionService() ?: return
        val active: Boolean
        try {
            active = service.getActivePunishedUuids(setOf(uuid)).contains(uuid)
            if (liteBansFailureLogged) {
                plugin.getLogger().info("Punishment event publisher recovered LiteBans access.")
                liteBansFailureLogged = false
            }
        } catch (e: Exception) {
            if (e !is LiteBansInfractionService.LiteBansUnavailableException && e !is SQLException) throw e
            if (!liteBansFailureLogged) {
                plugin.getLogger().warn("Punishment event publisher cannot query LiteBans; REST reconcile will cover gaps.", e)
                liteBansFailureLogged = true
            } else {
                plugin.getLogger().debug("Punishment event publisher LiteBans query failed: {}", e.message)
            }
            return
        }
        if (publish(uuid, active, source)) {
            if (active) lastActiveUuids?.add(uuid) else lastActiveUuids?.remove(uuid)
        }
    }

    private fun publish(uuid: String, active: Boolean, source: String): Boolean {
        val pool = jedisPool ?: return false
        val fields = LinkedHashMap<String, String>()
        fields["uuid"] = uuid
        fields["active"] = active.toString()
        fields["source"] = source
        fields["occurred_at"] = Instant.now().epochSecond.toString()
        try {
            pool.resource.use { jedis ->
                jedis.xadd(streamName, XAddParams.xAddParams().maxLen(STREAM_MAX_LEN).approximateTrimming(), fields)
                if (redisFailureLogged) {
                    plugin.getLogger().info("Punishment event Redis publisher recovered.")
                    redisFailureLogged = false
                }
                return true
            }
        } catch (e: Exception) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Failed to publish punishment event to Redis; REST reconcile will cover gaps.", e)
                redisFailureLogged = true
            } else {
                plugin.getLogger().debug("Failed to publish punishment event for {}: {}", uuid, e.message)
            }
            return false
        }
    }

    companion object {
        private const val STREAM_MAX_LEN = 10_000L

        private fun getEntryType(entry: Entry?): String? = entry?.type?.lowercase(Locale.ROOT)

        private fun isTrackedPunishmentType(type: String): Boolean =
            type == "ban" || type == "bans" || type == "mute" || type == "mutes"

        private fun normalizeEntryUuid(rawUuid: String?): String? {
            if (rawUuid == null || rawUuid.isBlank()) return null
            var trimmed = rawUuid.trim { it <= ' ' }
            return try {
                if (trimmed.length == 32) {
                    trimmed = trimmed.substring(0, 8) + "-" + trimmed.substring(8, 12) + "-" + trimmed.substring(12, 16) + "-" +
                        trimmed.substring(16, 20) + "-" + trimmed.substring(20)
                }
                UUID.fromString(trimmed).toString()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
