package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.redis.RedisSubscriberThread
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import redis.clients.jedis.*

/** Redis voice transport: binary audio, text lifecycle, ordered and coalesced control work. */
open class RedisVoiceBus(private val plugin: CrabUtilities) {
    private val host = plugin.config.getString("redis.host", "localhost")
    private val port = plugin.config.getInt("redis.port", 6379)
    private val password = plugin.config.getString("redis.password", "")
    private var jedisPool: JedisPool? = null
    private lateinit var audioHandler: BiConsumer<UUID, ByteArray>
    private lateinit var lifecycleHandler: Consumer<String>
    private var lifecyclePubSub: JedisPubSub? = null
    private var lifecycleSubscriberThread: Thread? = null
    private var audioPubSub: BinaryJedisPubSub? = null
    private var audioSubscriberThread: Thread? = null
    private val audioPublishExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { Thread(it, "CrabUtilities-VoiceBus-AudioPublish").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val pendingControlTasks = ConcurrentHashMap<String, Runnable>()
    private val controlExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "CrabUtilities-VoiceBus-Control").apply { isDaemon = true }
    }

    fun start(audioHandler: BiConsumer<UUID, ByteArray>, lifecycleHandler: Consumer<String>) {
        this.audioHandler = audioHandler
        this.lifecycleHandler = lifecycleHandler
        val config =
            JedisPoolConfig().apply {
                maxTotal = 8
                setMaxWait(Duration.ofMillis(1500))
            }
        jedisPool =
            if (!password.isNullOrEmpty()) JedisPool(config, host, port, 2000, password)
            else JedisPool(config, host, port, 2000)
        startLifecycleSubscriber()
        startAudioSubscriber()
        plugin.logger.info("Voice bus started; Redis will be retried asynchronously if unavailable.")
    }

    private fun startLifecycleSubscriber() {
        val subscriber =
            object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    try {
                        lifecycleHandler.accept(message)
                    } catch (t: Throwable) {
                        plugin.logger.fine("Voice lifecycle handler threw: ${t.message}")
                    }
                }
            }
        lifecyclePubSub = subscriber
        lifecycleSubscriberThread =
            startSubscriber("lifecycle", "CrabUtilities-VoiceBus-Lifecycle") {
                it.subscribe(subscriber, VoiceMessages.LIFECYCLE_CHANNEL, VoiceMessages.ROSTER_CHANNEL)
            }
    }

    private fun startAudioSubscriber() {
        val subscriber =
            object : BinaryJedisPubSub() {
                override fun onPMessage(pattern: ByteArray, channel: ByteArray, message: ByteArray) {
                    val channelName = String(channel, UTF_8)
                    if (!channelName.startsWith(VoiceMessages.AUDIO_CHANNEL_PREFIX)) return
                    try {
                        audioHandler.accept(
                            UUID.fromString(channelName.substring(VoiceMessages.AUDIO_CHANNEL_PREFIX.length)),
                            message,
                        )
                    } catch (_: IllegalArgumentException) {
                        /* Malformed or unrelated channel. */
                    } catch (t: Throwable) {
                        plugin.logger.fine("Voice audio handler threw: ${t.message}")
                    }
                }
            }
        audioPubSub = subscriber
        val pattern = (VoiceMessages.AUDIO_CHANNEL_PREFIX + "*").toByteArray(UTF_8)
        audioSubscriberThread =
            startSubscriber("audio", "CrabUtilities-VoiceBus-Audio") { it.psubscribe(subscriber, pattern) }
    }

    private fun startSubscriber(label: String, threadName: String, subscribe: Consumer<Jedis>): Thread =
        RedisSubscriberThread.start(
            threadName,
            { jedisPool },
            subscribe,
            { plugin.logger.info("Voice $label Redis subscriber reconnected.") },
            { failure, firstFailure ->
                if (firstFailure)
                    plugin.logger.warning(
                        "Voice $label Redis subscriber unavailable; reconnecting in 3s: ${failure.message}"
                    )
                else plugin.logger.fine("Voice $label subscriber disconnected: ${failure.message}")
            },
        )

    fun publishAudio(groupId: UUID, speakerId: UUID, frame: ByteArray, resetMarker: Boolean) {
        val pool = jedisPool ?: return
        if (pool.isClosed) return
        if (resetMarker) audioPublishExecutor.queue.removeIf { it is AudioPublish && it.speakerId == speakerId }
        else if (audioPublishExecutor.queue.size >= 64) return
        try {
            audioPublishExecutor.execute(AudioPublish(groupId, speakerId, frame))
        } catch (_: RejectedExecutionException) {
            /* Plugin is stopping. */
        }
    }

    fun publishRoster(message: String, playerId: UUID, expectedRoute: String?) {
        if (expectedRoute == null) return
        val leave = VoiceMessages.decodeRosterLeave(message)
        val leaving = leave != null && playerId == leave.playerId && expectedRoute == leave.route
        submitControl("roster:$playerId") {
            val pool = jedisPool ?: return@submitControl
            if (pool.isClosed) return@submitControl
            try {
                pool.resource.use {
                    it.eval(
                        PUBLISH_ROSTER_SCRIPT,
                        listOf(VoiceMessages.playerHomeKey(playerId), VoiceMessages.ROSTER_CHANNEL),
                        listOf(expectedRoute, message, if (leaving) "1" else "0"),
                    )
                }
            } catch (e: Exception) {
                plugin.logger.warning("Voice roster publish failed: ${e.message}")
            }
        }
    }

    fun upsertGroup(group: VoiceMessages.GroupDefinition, completion: Consumer<Boolean>) {
        val accepted =
            submitControl("group:${group.id}") {
                var succeeded = false
                val pool = jedisPool
                try {
                    if (pool != null && !pool.isClosed)
                        pool.resource.use {
                            it.eval(
                                UPSERT_GROUP_SCRIPT,
                                listOf(
                                    VoiceMessages.GROUPS_REGISTRY_KEY,
                                    VoiceMessages.PERMANENT_GROUPS_KEY,
                                    VoiceMessages.LIFECYCLE_CHANNEL,
                                ),
                                listOf(
                                    group.id.toString(),
                                    VoiceMessages.encodeGroupDefinition(group),
                                    if (group.permanent) "1" else "0",
                                    VoiceMessages.encodeGroupChanged(group.id),
                                ),
                            )
                            succeeded = true
                        }
                } catch (e: Exception) {
                    plugin.logger.warning("Voice group registry write failed: ${e.message}")
                } finally {
                    completion.accept(succeeded)
                }
            }
        if (!accepted) completion.accept(false)
    }

    fun fetchGroup(groupId: UUID): VoiceMessages.GroupDefinition? {
        val pool = jedisPool ?: return null
        if (pool.isClosed) return null
        return try {
            pool.resource.use {
                VoiceMessages.decodeGroupDefinition(
                    groupId,
                    it.hget(VoiceMessages.GROUPS_REGISTRY_KEY, groupId.toString()),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchGroups(): Map<UUID, VoiceMessages.GroupDefinition>? {
        val pool = jedisPool ?: return null
        if (pool.isClosed) return null
        return try {
            pool.resource.use { jedis ->
                val result = HashMap<UUID, VoiceMessages.GroupDefinition>()
                for ((key, value) in jedis.hgetAll(VoiceMessages.GROUPS_REGISTRY_KEY)) {
                    try {
                        val id = UUID.fromString(key)
                        VoiceMessages.decodeGroupDefinition(id, value)?.let { result[id] = it }
                    } catch (_: IllegalArgumentException) {
                        /* Skip only the corrupt entry. */
                    }
                }
                java.util.Map.copyOf(result)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun pruneGroups() {
        submitControl("prune") {
            val pool = jedisPool ?: return@submitControl
            if (pool.isClosed) return@submitControl
            try {
                pool.resource.use {
                    it.eval(
                        PRUNE_GROUPS_SCRIPT,
                        listOf(
                            VoiceMessages.GROUPS_REGISTRY_KEY,
                            VoiceMessages.PERMANENT_GROUPS_KEY,
                            VoiceMessages.LIFECYCLE_CHANNEL,
                        ),
                        listOf(
                            VoiceMessages.GROUP_MEMBERS_KEY_PREFIX,
                            VoiceMessages.PLAYER_GROUP_KEY_PREFIX,
                            VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP,
                        ),
                    )
                }
            } catch (e: Exception) {
                plugin.logger.fine("Voice group lease pruning failed: ${e.message}")
            }
        }
    }

    /** Velocity backend and hop token, if present. */
    fun fetchPlayerHome(playerId: UUID): String? {
        val pool = jedisPool ?: return null
        if (pool.isClosed) return null
        return try {
            pool.resource.use { it.get(VoiceMessages.playerHomeKey(playerId)) }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchPlayerHomes(playerIds: Set<UUID>): Map<UUID, String>? {
        if (playerIds.isEmpty()) return emptyMap()
        val pool = jedisPool ?: return null
        if (pool.isClosed) return null
        val players = playerIds.toList()
        val keys = players.map(VoiceMessages::playerHomeKey).toTypedArray()
        return try {
            pool.resource.use { jedis ->
                val routes = jedis.mget(*keys)
                val result = HashMap<UUID, String>()
                for (index in players.indices) routes[index]?.let { result[players[index]] = it }
                result
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fetchCallTargets(playerIds: Set<UUID>): ReadResult<Map<UUID, VoiceMessages.CallTarget>> {
        if (playerIds.isEmpty()) return ReadResult(true, emptyMap())
        val pool = jedisPool ?: return ReadResult(false, emptyMap())
        if (pool.isClosed) return ReadResult(false, emptyMap())
        val players = playerIds.toList()
        val keys = players.map(VoiceMessages::callTargetKey).toTypedArray()
        return try {
            pool.resource.use { jedis ->
                val encoded = jedis.mget(*keys)
                val result = HashMap<UUID, VoiceMessages.CallTarget>()
                for (index in players.indices) VoiceMessages.decodeCallTarget(encoded[index])?.let {
                    result[players[index]] = it
                }
                ReadResult(true, java.util.Map.copyOf(result))
            }
        } catch (_: Exception) {
            ReadResult(false, emptyMap())
        }
    }

    fun clearCallTarget(
        playerId: UUID,
        target: VoiceMessages.CallTarget?,
        expectedRoute: String?,
        completion: Runnable,
    ) {
        if (target == null || expectedRoute == null) {
            completion.run()
            return
        }
        val accepted =
            submitControl("call-target:$playerId") {
                val pool = jedisPool
                try {
                    if (pool != null && !pool.isClosed)
                        pool.resource.use {
                            it.eval(
                                CLEAR_CALL_TARGET_SCRIPT,
                                listOf(VoiceMessages.playerHomeKey(playerId), VoiceMessages.callTargetKey(playerId)),
                                listOf(expectedRoute, VoiceMessages.encodeCallTarget(target)),
                            )
                        }
                } catch (e: Exception) {
                    plugin.logger.fine("Call target clear failed: ${e.message}")
                } finally {
                    completion.run()
                }
            }
        if (!accepted) completion.run()
    }

    /** The committed cross-backend group, used to restore membership after a hop. */
    fun fetchPlayerGroup(playerId: UUID): ReadResult<String?> {
        val pool = jedisPool ?: return ReadResult(false, null)
        if (pool.isClosed) return ReadResult(false, null)
        return try {
            pool.resource.use { ReadResult(true, it.get(VoiceMessages.playerGroupKey(playerId))) }
        } catch (_: Exception) {
            ReadResult(false, null)
        }
    }

    fun writePlayerGroup(
        playerId: UUID,
        group: VoiceMessages.GroupDefinition?,
        ttlSeconds: Long,
        expectedRoute: String?,
        completion: Consumer<Boolean>,
    ) {
        if (group == null || expectedRoute == null) return
        val accepted =
            submitControl("membership:$playerId") {
                var definitionChanged = false
                val pool = jedisPool
                try {
                    if (pool != null && !pool.isClosed)
                        pool.resource.use {
                            val result =
                                it.eval(
                                    SET_PLAYER_GROUP_SCRIPT,
                                    membershipKeys(playerId),
                                    listOf(
                                        group.id.toString(),
                                        playerId.toString(),
                                        VoiceMessages.GROUP_MEMBERS_KEY_PREFIX,
                                        VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP,
                                        ttlSeconds.toString(),
                                        expectedRoute,
                                        VoiceMessages.encodeGroupDefinition(group),
                                        if (group.permanent) "1" else "0",
                                        VoiceMessages.SEP,
                                    ),
                                )
                            definitionChanged = result is Long && result == 1L
                        }
                } catch (e: Exception) {
                    plugin.logger.fine("writePlayerGroup failed: ${e.message}")
                } finally {
                    completion.accept(definitionChanged)
                }
            }
        if (!accepted) completion.accept(false)
    }

    fun deletePlayerGroup(
        playerId: UUID,
        previousGroupId: UUID?,
        expectedRoute: String?,
        completion: Consumer<Boolean>,
    ) {
        if (expectedRoute == null) return
        val accepted =
            submitControl("membership:$playerId") {
                var registryChanged = false
                val pool = jedisPool
                try {
                    if (pool != null && !pool.isClosed)
                        pool.resource.use {
                            val result =
                                it.eval(
                                    CLEAR_PLAYER_GROUP_SCRIPT,
                                    membershipKeys(playerId),
                                    listOf(
                                        playerId.toString(),
                                        VoiceMessages.GROUP_MEMBERS_KEY_PREFIX,
                                        VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP,
                                        expectedRoute,
                                        previousGroupId?.toString() ?: "",
                                    ),
                                )
                            registryChanged = result is Long && result == 1L
                        }
                } catch (_: Exception) {
                    /* The next ungrouped heartbeat retries the authoritative clear. */
                } finally {
                    completion.accept(registryChanged)
                }
            }
        if (!accepted) completion.accept(false)
    }

    private fun membershipKeys(playerId: UUID) =
        listOf(
            VoiceMessages.playerGroupKey(playerId),
            VoiceMessages.GROUPS_REGISTRY_KEY,
            VoiceMessages.PERMANENT_GROUPS_KEY,
            VoiceMessages.LIFECYCLE_CHANNEL,
            VoiceMessages.playerHomeKey(playerId),
            VoiceMessages.callTargetKey(playerId),
        )

    private fun submitControl(key: String, task: Runnable): Boolean {
        if (controlExecutor.isShutdown) return false
        if (pendingControlTasks.put(key, task) != null) return true
        return try {
            controlExecutor.execute { pendingControlTasks.remove(key)?.run() }
            true
        } catch (_: RejectedExecutionException) {
            pendingControlTasks.remove(key, task)
            false
        }
    }

    fun shutdown() {
        controlExecutor.shutdown()
        try {
            if (!controlExecutor.awaitTermination(2L, TimeUnit.SECONDS)) controlExecutor.shutdownNow()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            controlExecutor.shutdownNow()
        }
        try {
            lifecyclePubSub?.unsubscribe()
        } catch (_: Exception) {}
        lifecycleSubscriberThread?.interrupt()
        try {
            audioPubSub?.punsubscribe()
        } catch (_: Exception) {}
        audioSubscriberThread?.interrupt()
        audioPublishExecutor.shutdownNow()
        pendingControlTasks.clear()
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try {
                pool.close()
            } catch (_: NoClassDefFoundError) {}
            jedisPool = null
        }
    }

    private inner class AudioPublish(val groupId: UUID, val speakerId: UUID, val frame: ByteArray) : Runnable {
        override fun run() {
            val pool = jedisPool ?: return
            if (pool.isClosed) return
            try {
                pool.resource.use { it.publish(VoiceMessages.audioChannel(groupId).toByteArray(UTF_8), frame) }
            } catch (e: Exception) {
                plugin.logger.fine("Voice audio publish failed: ${e.message}")
            }
        }
    }

    data class ReadResult<T>(val succeeded: Boolean, val value: T) {
        fun succeeded() = succeeded

        fun value() = value
    }

    companion object {
        private val UPSERT_GROUP_SCRIPT =
            """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            if ARGV[3] == '1' then
                redis.call('SADD', KEYS[2], ARGV[1])
            else
                redis.call('SREM', KEYS[2], ARGV[1])
            end
            redis.call('PUBLISH', KEYS[3], ARGV[4])
            return 1
            """
                .trimIndent()
        private val SET_PLAYER_GROUP_SCRIPT =
            """
            if redis.call('GET', KEYS[5]) ~= ARGV[6] then
                return -1
            end
            local callTarget = redis.call('GET', KEYS[6])
            if callTarget and string.sub(callTarget, 1, 37) ~= ARGV[1] .. ARGV[9] then
                return -2
            end
            local definition = redis.call('HGET', KEYS[2], ARGV[1])
            local registryChanged = definition ~= ARGV[7]
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[7])
            if ARGV[8] == '1' then
                redis.call('SADD', KEYS[3], ARGV[1])
            else
                redis.call('SREM', KEYS[3], ARGV[1])
            end
            if definition ~= ARGV[7] then
                redis.call('PUBLISH', KEYS[4], ARGV[4] .. ARGV[1])
            end
            local old = redis.call('GET', KEYS[1])
            if old and old ~= ARGV[1] then
                local oldMembers = ARGV[3] .. old
                redis.call('SREM', oldMembers, ARGV[2])
                if redis.call('SCARD', oldMembers) == 0
                        and redis.call('SISMEMBER', KEYS[3], old) == 0 then
                    if redis.call('HDEL', KEYS[2], old) > 0 then
                        registryChanged = true
                    end
                    redis.call('PUBLISH', KEYS[4], ARGV[4] .. old)
                end
            end
            redis.call('SETEX', KEYS[1], ARGV[5], ARGV[1])
            redis.call('SADD', ARGV[3] .. ARGV[1], ARGV[2])
            if registryChanged then
                return 1
            end
            return 0
            """
                .trimIndent()
        private val CLEAR_PLAYER_GROUP_SCRIPT =
            """
            if redis.call('GET', KEYS[5]) ~= ARGV[4] then
                return -1
            end
            if redis.call('GET', KEYS[6]) then
                return -2
            end
            local registryChanged = 0
            local old = redis.call('GET', KEYS[1])
            if not old and ARGV[5] ~= '' then
                old = ARGV[5]
            end
            redis.call('DEL', KEYS[1])
            if old then
                local oldMembers = ARGV[2] .. old
                redis.call('SREM', oldMembers, ARGV[1])
                if redis.call('SCARD', oldMembers) == 0
                        and redis.call('SISMEMBER', KEYS[3], old) == 0 then
                    if redis.call('HDEL', KEYS[2], old) > 0 then
                        registryChanged = 1
                    end
                    redis.call('PUBLISH', KEYS[4], ARGV[3] .. old)
                end
            end
            return registryChanged
            """
                .trimIndent()
        private val PUBLISH_ROSTER_SCRIPT =
            """
            if ARGV[3] == '1' or redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PUBLISH', KEYS[2], ARGV[2])
            end
            return 0
            """
                .trimIndent()
        private val CLEAR_CALL_TARGET_SCRIPT =
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return 0
            end
            redis.call('DEL', KEYS[2])
            return 1
            """
                .trimIndent()
        private val PRUNE_GROUPS_SCRIPT =
            """
            for _, groupId in ipairs(redis.call('HKEYS', KEYS[1])) do
                local membersKey = ARGV[1] .. groupId
                local hadMembers = redis.call('SCARD', membersKey) > 0
                for _, playerId in ipairs(redis.call('SMEMBERS', membersKey)) do
                    if redis.call('GET', ARGV[2] .. playerId) ~= groupId then
                        redis.call('SREM', membersKey, playerId)
                    end
                end
                if not hadMembers and redis.call('SCARD', membersKey) == 0
                        and redis.call('SISMEMBER', KEYS[2], groupId) == 0 then
                    redis.call('HDEL', KEYS[1], groupId)
                    redis.call('DEL', membersKey)
                    redis.call('PUBLISH', KEYS[3], ARGV[3] .. groupId)
                end
            end
            return 1
            """
                .trimIndent()
    }
}
