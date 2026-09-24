package crabcraft.net.crabUtilities.velocity.litebans

import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import litebans.api.Entry
import litebans.api.Events
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.XAddParams

/** Publishes ban/mute state changes to Redis; periodic bot reconciliation covers missed events. */
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
        val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "crabutilities-punishment-events").apply { isDaemon = true }
        }
        executor = worker
        registerLiteBansListener()
        worker.scheduleWithFixedDelay(
            { pollAndPublish("watch") },
            0L,
            config.getRedisPunishmentWatchIntervalSeconds(),
            TimeUnit.SECONDS,
        )
        plugin
            .getLogger()
            .info(
                "Punishment event publisher started on Redis stream {} (LiteBans events + {}s fallback watch interval).",
                streamName,
                config.getRedisPunishmentWatchIntervalSeconds(),
            )
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
            val listener =
                object : Events.Listener() {
                    override fun entryAdded(entry: Entry) = handleEntryAdded(entry)

                    override fun entryRemoved(entry: Entry) = handleEntryRemoved(entry)
                }
            liteBansListener = listener
            Events.get().register(listener)
            plugin.getLogger().info("Punishment event publisher registered LiteBans event listener.")
            liteBansEventFailureLogged = false
        } catch (e: Throwable) {
            if (!liteBansEventFailureLogged) {
                plugin
                    .getLogger()
                    .warn(
                        "Punishment event publisher could not register LiteBans events; fallback watch interval remains active.",
                        e,
                    )
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

    private fun handleEntryAdded(entry: Entry) {
        val type = getEntryType(entry)
        if (type == null) {
            triggerDiff("litebans-event")
            return
        }
        if (!isTrackedPunishmentType(type)) return
        val uuid = normalizeEntryUuid(entry.uuid)
        if (uuid == null || !entry.isActive) {
            triggerDiff("litebans-event")
            return
        }
        val currentExecutor = executor
        if (currentExecutor == null || currentExecutor.isShutdown) return
        currentExecutor.execute {
            if (publish(uuid, true, "litebans-event")) lastActiveUuids?.add(uuid)
        }
    }

    private fun handleEntryRemoved(entry: Entry) {
        val type = getEntryType(entry)
        if (type != null && !isTrackedPunishmentType(type)) return
        val uuid = normalizeEntryUuid(entry.uuid)
        if (uuid == null) {
            triggerDiff("litebans-event")
            return
        }
        val currentExecutor = executor
        if (currentExecutor == null || currentExecutor.isShutdown) return
        currentExecutor.execute { publishCurrentPunishmentState(uuid, "litebans-event") }
    }

    private fun triggerDiff(source: String) {
        val currentExecutor = executor
        if (currentExecutor == null || currentExecutor.isShutdown) return
        currentExecutor.execute { pollAndPublish(source) }
    }

    private fun pollAndPublish(source: String) {
        val service = plugin.getLiteBansInfractionService() ?: return
        val current =
            try {
                service.getAllActivePunishedUuids().also { queryRecovered() }
            } catch (e: LiteBansInfractionService.LiteBansUnavailableException) {
                queryFailed(e)
                return
            } catch (e: SQLException) {
                queryFailed(e)
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
        val active =
            try {
                service.getActivePunishedUuids(setOf(uuid)).contains(uuid).also { queryRecovered() }
            } catch (e: LiteBansInfractionService.LiteBansUnavailableException) {
                queryFailed(e)
                return
            } catch (e: SQLException) {
                queryFailed(e)
                return
            }
        if (publish(uuid, active, source)) {
            if (active) lastActiveUuids?.add(uuid) else lastActiveUuids?.remove(uuid)
        }
    }

    private fun queryRecovered() {
        if (liteBansFailureLogged) {
            plugin.getLogger().info("Punishment event publisher recovered LiteBans access.")
            liteBansFailureLogged = false
        }
    }

    private fun queryFailed(error: Exception) {
        if (!liteBansFailureLogged) {
            plugin
                .getLogger()
                .warn("Punishment event publisher cannot query LiteBans; REST reconcile will cover gaps.", error)
            liteBansFailureLogged = true
        } else {
            plugin.getLogger().debug("Punishment event publisher LiteBans query failed: {}", error.message)
        }
    }

    private fun publish(uuid: String, active: Boolean, source: String): Boolean {
        val pool = jedisPool ?: return false
        val fields =
            linkedMapOf(
                "uuid" to uuid,
                "active" to active.toString(),
                "source" to source,
                "occurred_at" to Instant.now().epochSecond.toString(),
            )
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
                plugin
                    .getLogger()
                    .warn("Failed to publish punishment event to Redis; REST reconcile will cover gaps.", e)
                redisFailureLogged = true
            } else {
                plugin.getLogger().debug("Failed to publish punishment event for {}: {}", uuid, e.message)
            }
            return false
        }
    }

    companion object {
        private const val STREAM_MAX_LEN = 10_000L

        private fun getEntryType(entry: Entry?) = entry?.type?.lowercase(Locale.ROOT)

        private fun isTrackedPunishmentType(type: String) =
            type == "ban" || type == "bans" || type == "mute" || type == "mutes"

        private fun normalizeEntryUuid(rawUuid: String?): String? {
            if (rawUuid.isNullOrBlank()) return null
            var value = rawUuid.trim()
            return try {
                if (value.length == 32)
                    value =
                        value.substring(0, 8) +
                            "-" +
                            value.substring(8, 12) +
                            "-" +
                            value.substring(12, 16) +
                            "-" +
                            value.substring(16, 20) +
                            "-" +
                            value.substring(20)
                UUID.fromString(value).toString()
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
