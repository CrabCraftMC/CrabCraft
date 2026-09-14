package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import redis.clients.jedis.BinaryJedisPubSub
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.function.BiConsumer
import java.util.function.Consumer

/** Jedis voice bridge with reconnecting subscribers and ordered audio/control publishers. */
class RedisVoiceBus(private val plugin: CrabUtilities) {
    private val host = plugin.getConfig().getString("redis.host", "localhost")
    private val port = plugin.getConfig().getInt("redis.port", 6379)
    private val password = plugin.getConfig().getString("redis.password", "")
    private var jedisPool: JedisPool? = null
    private var audioHandler: BiConsumer<UUID, ByteArray>? = null
    private var lifecycleHandler: Consumer<String>? = null
    private var lifecyclePubSub: JedisPubSub? = null
    private var lifecycleSubscriberThread: Thread? = null
    private var audioPubSub: BinaryJedisPubSub? = null
    private var audioSubscriberThread: Thread? = null
    // Normal audio is capped at 64 queued frames; reset markers replace a speaker's queued work.
    private val audioPublishExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        { r -> Thread(r, "CrabUtilities-VoiceBus-AudioPublish").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    // Coalesce control work per entity without losing the latest authoritative state during outages.
    private val pendingControlTasks = ConcurrentHashMap<String, Runnable>()
    private val controlExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "CrabUtilities-VoiceBus-Control").apply { isDaemon = true } }

    fun start(audioHandler: BiConsumer<UUID, ByteArray>, lifecycleHandler: Consumer<String>) {
        this.audioHandler = audioHandler
        this.lifecycleHandler = lifecycleHandler
        val poolConfig = JedisPoolConfig()
        poolConfig.setMaxTotal(8)
        poolConfig.setMaxWait(Duration.ofMillis(1500))
        jedisPool = if (!password.isNullOrEmpty()) JedisPool(poolConfig, host, port, 2000, password) else JedisPool(poolConfig, host, port, 2000)
        startLifecycleSubscriber()
        startAudioSubscriber()
        plugin.getLogger().info("Voice bus started; Redis will be retried asynchronously if unavailable.")
    }

    private fun startLifecycleSubscriber() {
        lifecyclePubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                try { lifecycleHandler!!.accept(message) } catch (t: Throwable) { plugin.getLogger().fine("Voice lifecycle handler threw: " + t.message) }
            }
        }
        val subscriber = Thread({
            var warned = false
            while (!Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis ->
                        if (warned) { plugin.getLogger().info("Voice lifecycle Redis subscriber reconnected."); warned = false }
                        jedis.subscribe(lifecyclePubSub, VoiceMessages.LIFECYCLE_CHANNEL, VoiceMessages.ROSTER_CHANNEL)
                    }
                } catch (e: NoClassDefFoundError) { break }
                catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) {
                        plugin.getLogger().warning("Voice lifecycle Redis subscriber unavailable; reconnecting in 3s: " + e.message)
                        warned = true
                    } else plugin.getLogger().fine("Voice lifecycle subscriber disconnected: " + e.message)
                    try { Thread.sleep(3000L) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        }, "CrabUtilities-VoiceBus-Lifecycle")
        lifecycleSubscriberThread = subscriber
        subscriber.isDaemon = true
        subscriber.start()
    }

    private fun startAudioSubscriber() {
        audioPubSub = object : BinaryJedisPubSub() {
            override fun onPMessage(pattern: ByteArray, channel: ByteArray, message: ByteArray) {
                val channelName = String(channel, StandardCharsets.UTF_8)
                if (!channelName.startsWith(VoiceMessages.AUDIO_CHANNEL_PREFIX)) return
                try {
                    val groupId = UUID.fromString(channelName.substring(VoiceMessages.AUDIO_CHANNEL_PREFIX.length))
                    audioHandler!!.accept(groupId, message)
                } catch (_: IllegalArgumentException) { /* Ignore malformed or unrelated channels. */ }
                catch (t: Throwable) { plugin.getLogger().fine("Voice audio handler threw: " + t.message) }
            }
        }
        val subscriber = Thread({
            val pattern = (VoiceMessages.AUDIO_CHANNEL_PREFIX + "*").toByteArray(StandardCharsets.UTF_8)
            var warned = false
            while (!Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis ->
                        if (warned) { plugin.getLogger().info("Voice audio Redis subscriber reconnected."); warned = false }
                        jedis.psubscribe(audioPubSub, pattern)
                    }
                } catch (e: NoClassDefFoundError) { break }
                catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) {
                        plugin.getLogger().warning("Voice audio Redis subscriber unavailable; reconnecting in 3s: " + e.message)
                        warned = true
                    } else plugin.getLogger().fine("Voice audio subscriber disconnected: " + e.message)
                    try { Thread.sleep(3000L) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        }, "CrabUtilities-VoiceBus-Audio")
        audioSubscriberThread = subscriber
        subscriber.isDaemon = true
        subscriber.start()
    }

    fun publishAudio(groupId: UUID, speakerId: UUID, frame: ByteArray, resetMarker: Boolean) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        if (resetMarker) {
            // The reset follows the speaker's in-flight frame, retaining other speakers' work.
            audioPublishExecutor.queue.removeIf { task -> task is AudioPublish && task.speakerId == speakerId }
        } else if (audioPublishExecutor.queue.size >= 64) return
        try { audioPublishExecutor.execute(AudioPublish(groupId, speakerId, frame)) } catch (_: RejectedExecutionException) { /* Plugin is stopping. */ }
    }

    fun publishRoster(message: String, playerId: UUID, expectedRoute: String?) {
        if (expectedRoute == null) return
        val leave = VoiceMessages.decodeRosterLeave(message)
        // Departed routes may retract their own roster entries; receivers compare the entire hop token.
        val leaving = leave != null && playerId == leave.playerId() && expectedRoute == leave.route()
        submitControl("roster:" + playerId) {
            val pool = jedisPool
            if (pool == null || pool.isClosed) return@submitControl
            try { pool.resource.use { jedis -> jedis.eval(PUBLISH_ROSTER_SCRIPT, listOf(VoiceMessages.playerHomeKey(playerId), VoiceMessages.ROSTER_CHANNEL), listOf(expectedRoute, message, if (leaving) "1" else "0")) } }
            catch (e: Exception) { plugin.getLogger().warning("Voice roster publish failed: " + e.message) }
        }
    }

    fun upsertGroup(group: VoiceMessages.GroupDefinition, completion: Consumer<Boolean>) {
        val accepted = submitControl("group:" + group.id()) {
            var succeeded = false
            val pool = jedisPool
            try {
                if (pool != null && !pool.isClosed) pool.resource.use { jedis ->
                    jedis.eval(UPSERT_GROUP_SCRIPT, listOf(VoiceMessages.GROUPS_REGISTRY_KEY, VoiceMessages.PERMANENT_GROUPS_KEY, VoiceMessages.LIFECYCLE_CHANNEL),
                        listOf(group.id().toString(), VoiceMessages.encodeGroupDefinition(group), if (group.permanent()) "1" else "0", VoiceMessages.encodeGroupChanged(group.id())))
                    succeeded = true
                }
            } catch (e: Exception) { plugin.getLogger().warning("Voice group registry write failed: " + e.message) }
            finally { completion.accept(succeeded) }
        }
        if (!accepted) completion.accept(false)
    }

    fun fetchGroup(groupId: UUID): VoiceMessages.GroupDefinition? {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return null
        return try { pool.resource.use { jedis -> VoiceMessages.decodeGroupDefinition(groupId, jedis.hget(VoiceMessages.GROUPS_REGISTRY_KEY, groupId.toString())) } }
        catch (e: Exception) { null }
    }

    fun fetchGroups(): Map<UUID, VoiceMessages.GroupDefinition>? {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return null
        return try {
            pool.resource.use { jedis ->
                val result = HashMap<UUID, VoiceMessages.GroupDefinition>()
                for ((key, value) in jedis.hgetAll(VoiceMessages.GROUPS_REGISTRY_KEY)) {
                    try {
                        val id = UUID.fromString(key)
                        val group = VoiceMessages.decodeGroupDefinition(id, value)
                        if (group != null) result[id] = group
                    } catch (_: IllegalArgumentException) { /* Skip corrupt registry entries without losing the rest. */ }
                }
                java.util.Map.copyOf(result)
            }
        } catch (e: Exception) { null }
    }

    fun pruneGroups() {
        submitControl("prune") {
            val pool = jedisPool
            if (pool == null || pool.isClosed) return@submitControl
            try { pool.resource.use { jedis ->
                jedis.eval(PRUNE_GROUPS_SCRIPT, listOf(VoiceMessages.GROUPS_REGISTRY_KEY, VoiceMessages.PERMANENT_GROUPS_KEY, VoiceMessages.LIFECYCLE_CHANNEL),
                    listOf(VoiceMessages.GROUP_MEMBERS_KEY_PREFIX, VoiceMessages.PLAYER_GROUP_KEY_PREFIX, VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP))
            } } catch (e: Exception) { plugin.getLogger().fine("Voice group lease pruning failed: " + e.message) }
        }
    }

    /** Returns the Velocity backend and hop token, or null if not set. */
    fun fetchPlayerHome(playerId: UUID): String? {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return null
        return try { pool.resource.use { it.get(VoiceMessages.playerHomeKey(playerId)) } } catch (e: Exception) { null }
    }

    fun fetchPlayerHomes(playerIds: Set<UUID>): Map<UUID, String>? {
        if (playerIds.isEmpty()) return emptyMap()
        val pool = jedisPool
        if (pool == null || pool.isClosed) return null
        val players = java.util.List.copyOf(playerIds)
        val keys = Array(players.size) { VoiceMessages.playerHomeKey(players[it]) }
        return try {
            pool.resource.use { jedis ->
                val routes = jedis.mget(*keys)
                val result = HashMap<UUID, String>()
                for (i in players.indices) routes[i]?.let { result[players[i]] = it }
                result
            }
        } catch (e: Exception) { null }
    }

    fun fetchCallTargets(playerIds: Set<UUID>): ReadResult<Map<UUID, VoiceMessages.CallTarget>> {
        if (playerIds.isEmpty()) return ReadResult(true, emptyMap())
        val pool = jedisPool
        if (pool == null || pool.isClosed) return ReadResult(false, emptyMap())
        val players = java.util.List.copyOf(playerIds)
        val keys = Array(players.size) { VoiceMessages.callTargetKey(players[it]) }
        return try {
            pool.resource.use { jedis ->
                val encodedTargets = jedis.mget(*keys)
                val result = HashMap<UUID, VoiceMessages.CallTarget>()
                for (index in players.indices) {
                    val target = VoiceMessages.decodeCallTarget(encodedTargets[index])
                    if (target != null) result[players[index]] = target
                }
                ReadResult(true, java.util.Map.copyOf(result))
            }
        } catch (e: Exception) { ReadResult(false, emptyMap()) }
    }

    fun clearCallTarget(playerId: UUID, target: VoiceMessages.CallTarget?, expectedRoute: String?, completion: Runnable) {
        if (target == null || expectedRoute == null) { completion.run(); return }
        val accepted = submitControl("call-target:" + playerId) {
            val pool = jedisPool
            try {
                if (pool != null && !pool.isClosed) pool.resource.use { jedis ->
                    jedis.eval(CLEAR_CALL_TARGET_SCRIPT, listOf(VoiceMessages.playerHomeKey(playerId), VoiceMessages.callTargetKey(playerId)), listOf(expectedRoute, VoiceMessages.encodeCallTarget(target)))
                }
            } catch (e: Exception) { plugin.getLogger().fine("Call target clear failed: " + e.message) }
            finally { completion.run() }
        }
        if (!accepted) completion.run()
    }

    /** Reads the saved group lease used for auto-rejoin after a server hop. */
    fun fetchPlayerGroup(playerId: UUID): ReadResult<String> {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return ReadResult(false, null)
        return try { pool.resource.use { jedis -> ReadResult(true, jedis.get(VoiceMessages.playerGroupKey(playerId))) } }
        catch (e: Exception) { ReadResult(false, null) }
    }

    fun writePlayerGroup(playerId: UUID, group: VoiceMessages.GroupDefinition?, ttlSeconds: Long, expectedRoute: String?, completion: Consumer<Boolean>) {
        if (group == null || expectedRoute == null) return
        val accepted = submitControl("membership:" + playerId) {
            var definitionChanged = false
            val pool = jedisPool
            try {
                if (pool != null && !pool.isClosed) pool.resource.use { jedis ->
                    val result = jedis.eval(SET_PLAYER_GROUP_SCRIPT,
                        listOf(VoiceMessages.playerGroupKey(playerId), VoiceMessages.GROUPS_REGISTRY_KEY, VoiceMessages.PERMANENT_GROUPS_KEY, VoiceMessages.LIFECYCLE_CHANNEL, VoiceMessages.playerHomeKey(playerId), VoiceMessages.callTargetKey(playerId)),
                        listOf(group.id().toString(), playerId.toString(), VoiceMessages.GROUP_MEMBERS_KEY_PREFIX, VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP, ttlSeconds.toString(), expectedRoute, VoiceMessages.encodeGroupDefinition(group), if (group.permanent()) "1" else "0", VoiceMessages.SEP))
                    definitionChanged = result is Long && result == 1L
                }
            } catch (e: Exception) { plugin.getLogger().fine("writePlayerGroup failed: " + e.message) }
            finally { completion.accept(definitionChanged) }
        }
        if (!accepted) completion.accept(false)
    }

    fun deletePlayerGroup(playerId: UUID, previousGroupId: UUID?, expectedRoute: String?, completion: Consumer<Boolean>) {
        if (expectedRoute == null) return
        val accepted = submitControl("membership:" + playerId) {
            var registryChanged = false
            val pool = jedisPool
            try {
                if (pool != null && !pool.isClosed) pool.resource.use { jedis ->
                    val result = jedis.eval(CLEAR_PLAYER_GROUP_SCRIPT,
                        listOf(VoiceMessages.playerGroupKey(playerId), VoiceMessages.GROUPS_REGISTRY_KEY, VoiceMessages.PERMANENT_GROUPS_KEY, VoiceMessages.LIFECYCLE_CHANNEL, VoiceMessages.playerHomeKey(playerId), VoiceMessages.callTargetKey(playerId)),
                        listOf(playerId.toString(), VoiceMessages.GROUP_MEMBERS_KEY_PREFIX, VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP, expectedRoute, previousGroupId?.toString() ?: ""))
                    registryChanged = result is Long && result == 1L
                }
            } catch (_: Exception) { /* The next ungrouped heartbeat retries the authoritative clear. */ }
            finally { completion.accept(registryChanged) }
        }
        if (!accepted) completion.accept(false)
    }

    private fun submitControl(key: String, task: Runnable): Boolean {
        if (controlExecutor.isShutdown) return false
        val previous = pendingControlTasks.put(key, task)
        if (previous != null) return true
        try {
            controlExecutor.execute { pendingControlTasks.remove(key)?.run() }
            return true
        } catch (_: RejectedExecutionException) {
            pendingControlTasks.remove(key, task)
            return false
        }
    }

    fun shutdown() {
        controlExecutor.shutdown()
        try { if (!controlExecutor.awaitTermination(2L, TimeUnit.SECONDS)) controlExecutor.shutdownNow() }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); controlExecutor.shutdownNow() }
        lifecyclePubSub?.let { try { it.unsubscribe() } catch (_: Exception) {} }
        lifecycleSubscriberThread?.interrupt()
        audioPubSub?.let { try { it.punsubscribe() } catch (_: Exception) {} }
        audioSubscriberThread?.interrupt()
        audioPublishExecutor.shutdownNow()
        pendingControlTasks.clear()
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try { pool.close() } catch (_: NoClassDefFoundError) {}
            jedisPool = null
        }
    }

    private inner class AudioPublish(private val groupId: UUID, val speakerId: UUID, private val frame: ByteArray) : Runnable {
        override fun run() {
            val pool = jedisPool
            if (pool == null || pool.isClosed) return
            try { pool.resource.use { jedis -> jedis.publish(VoiceMessages.audioChannel(groupId).toByteArray(StandardCharsets.UTF_8), frame) } }
            catch (e: Exception) { plugin.getLogger().fine("Voice audio publish failed: " + e.message) }
        }
    }

    data class ReadResult<T>(private val succeeded: Boolean, private val value: T?) {
        fun succeeded(): Boolean = succeeded
        fun value(): T? = value
    }
    companion object {
        private val UPSERT_GROUP_SCRIPT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            if ARGV[3] == '1' then
                redis.call('SADD', KEYS[2], ARGV[1])
            else
                redis.call('SREM', KEYS[2], ARGV[1])
            end
            redis.call('PUBLISH', KEYS[3], ARGV[4])
            return 1
        """.trimIndent() + "\n"
        private val SET_PLAYER_GROUP_SCRIPT = """
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
        """.trimIndent() + "\n"
        private val CLEAR_PLAYER_GROUP_SCRIPT = """
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
        """.trimIndent() + "\n"
        private val PUBLISH_ROSTER_SCRIPT = """
            if ARGV[3] == '1' or redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PUBLISH', KEYS[2], ARGV[2])
            end
            return 0
        """.trimIndent() + "\n"
        private val CLEAR_CALL_TARGET_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return 0
            end
            redis.call('DEL', KEYS[2])
            return 1
        """.trimIndent() + "\n"
        private val PRUNE_GROUPS_SCRIPT = """
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
        """.trimIndent() + "\n"
    }
}
