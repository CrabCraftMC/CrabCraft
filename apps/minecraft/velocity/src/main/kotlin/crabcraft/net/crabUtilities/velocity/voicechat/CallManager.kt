package crabcraft.net.crabUtilities.velocity.voicechat

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.scheduler.ScheduledTask
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns network-wide call invitations and sends trusted join requests to the backends. */
class CallManager(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig,
                  private val locationTracker: PlayerLocationTracker) {
    private val invites = CallInviteRegistry()
    private val tasksByToken = ConcurrentHashMap<String, InviteTasks>()
    private val stopRetriesByToken = ConcurrentHashMap<String, StopRetry>()
    private val lastInviteAt = ConcurrentHashMap<UUID, Long>()
    private val random = SecureRandom()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(128),
        { runnable -> Thread(runnable, "CrabUtilities-Calls").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val acceptanceLifecycleLock = Any()
    @Volatile private var jedisPool: JedisPool? = null
    @Volatile private var running = false

    fun start() {
        val poolConfig = JedisPoolConfig()
        poolConfig.maxTotal = 2
        poolConfig.setMaxWait(Duration.ofMillis(1500L))
        jedisPool = RedisPools.create(config, poolConfig)
        running = true
        plugin.getLogger().info("Voice call manager started.")
    }
    fun invite(caller: Player, target: Player) {
        if (caller.uniqueId == target.uniqueId) { error(caller, "You can't call yourself."); return }
        if (!reserveInviteSlot(caller.uniqueId, System.currentTimeMillis())) {
            error(caller, "Please wait a moment before calling someone else."); return
        }
        submit(caller, Runnable { createInvite(caller, target) })
    }
    fun accept(target: Player, token: String) { submit(target, Runnable { acceptInvite(target, token) }) }
    fun decline(target: Player, token: String) { submit(target, Runnable { declineInvite(target, token) }) }
    @Subscribe(order = PostOrder.LAST)
    fun onDisconnect(event: DisconnectEvent) {
        val disconnectedSession = event.player
        val playerId = event.player.uniqueId
        val removed: Collection<CallInviteRegistry.Invite>
        synchronized(acceptanceLifecycleLock) {
            val current = plugin.getServer().getPlayer(playerId).orElse(null)
            if (current == null || current === disconnectedSession) lastInviteAt.remove(playerId)
            removed = invites.removeSession(playerId, disconnectedSession)
            for (invite in removed) registerRingtoneStopRetriesLocked(invite)
        }
        for (invite in removed) {
            publishRingtoneStops(invite)
            val otherId = if (invite.callerId() == playerId) invite.targetId() else invite.callerId()
            plugin.getServer().getPlayer(otherId).ifPresent { other -> error(other, event.player.username + " went offline, so the call was cancelled.") }
        }
    }
    fun shutdown() {
        // Finish committed transactions before a replacement manager starts on reload.
        val pendingByToken = LinkedHashMap<String, CallInviteRegistry.Invite>()
        synchronized(acceptanceLifecycleLock) {
            running = false
            for (invite in invites.clear()) pendingByToken[invite.token()] = invite
            for (tasks in ArrayList(tasksByToken.values)) {
                pendingByToken[tasks.invite.token()] = tasks.invite; tasks.cancel()
            }
            tasksByToken.clear()
            for (retry in ArrayList(stopRetriesByToken.values)) {
                pendingByToken[retry.invite().token()] = retry.invite(); retry.cancel()
            }
            stopRetriesByToken.clear()
        }
        worker.shutdownNow()
        try { worker.awaitTermination(3L, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val pool = jedisPool
        if (pool != null && !pool.isClosed && pendingByToken.isNotEmpty()) {
            try { pool.resource.use { jedis -> for (invite in pendingByToken.values) publishRingtoneStops(jedis, invite) } }
            catch (e: Exception) { plugin.getLogger().warn("Could not stop voice call ringtones during shutdown", e) }
        }
        jedisPool = null
        if (pool != null && !pool.isClosed) { try { pool.close() } catch (_: NoClassDefFoundError) {} }
        lastInviteAt.clear()
    }
    private fun createInvite(callerSession: Player, targetSession: Player) {
        if (!running) return
        val callerId = callerSession.uniqueId
        val targetId = targetSession.uniqueId
        val currentCaller = plugin.getServer().getPlayer(callerId).orElse(null)
        val currentTarget = plugin.getServer().getPlayer(targetId).orElse(null)
        if (currentCaller !== callerSession || currentTarget !== targetSession) {
            if (currentCaller === callerSession) error(callerSession, "That player is no longer online.")
            return
        }
        if (locationTracker.currentRoute(callerSession) == null || locationTracker.currentRoute(targetSession) == null) {
            error(callerSession, "Voice calls are still connecting; please try again."); return
        }
        val pool = jedisPool
        if (pool == null || pool.isClosed) { error(callerSession, "Voice calls are not available right now."); return }
        val now = System.currentTimeMillis()
        if (!invites.hasOutgoingCapacity(callerId, MAXIMUM_OUTGOING_INVITES, now)) {
            error(callerSession, "Wait for one of your current calls to be answered first."); return
        }
        var ringingInvite: CallInviteRegistry.Invite? = null
        try {
            pool.resource.use { jedis ->
                val targetCall = activeCall(jedis, targetId)
                if (targetCall != null) {
                    error(callerSession, if (targetCall.groupId() == activeCall(jedis, callerId)?.groupId())
                        targetSession.username + " is already in your call." else targetSession.username + " is already in another call.")
                    return
                }
                val active = activeCall(jedis, callerId)
                val callerWasInCall = active != null
                val call = active ?: invites.provisionalFor(callerId, callerSession, now) {
                    CallInviteRegistry.CallCredentials(UUID.randomUUID(), randomSecret(24), now + PROVISIONAL_CALL_LIFETIME_MILLIS)
                }
                val ringingStartedAt = System.currentTimeMillis()
                val token = randomSecret(18)
                val invite = CallInviteRegistry.Invite(token, callerId, callerSession.username, targetId, targetSession.username,
                    callerSession, targetSession, call, callerWasInCall, ringingStartedAt + INVITE_TIMEOUT_MILLIS)
                synchronized(acceptanceLifecycleLock) {
                    if (!running) return
                    val liveCaller = plugin.getServer().getPlayer(callerId).orElse(null)
                    val liveTarget = plugin.getServer().getPlayer(targetId).orElse(null)
                    if (liveCaller !== callerSession || liveTarget !== targetSession || locationTracker.currentRoute(callerSession) == null
                        || locationTracker.currentRoute(targetSession) == null) {
                        if (liveCaller != null) error(liveCaller, "That player is no longer online.")
                        return
                    }
                    if (!invites.add(invite, ringingStartedAt)) { error(callerSession, targetSession.username + " already has an incoming call."); return }
                    ringingInvite = invite
                    try { beginRinging(invite, callerSession, targetSession, jedis) }
                    catch (e: Exception) {
                        invites.remove(invite.token(), invite.targetId()); registerRingtoneStopRetriesLocked(invite); throw e
                    }
                }
            }
        } catch (e: Exception) {
            val ringing = ringingInvite
            if (ringing != null) {
                synchronized(acceptanceLifecycleLock) { invites.remove(ringing.token(), ringing.targetId()); registerRingtoneStopRetriesLocked(ringing) }
                publishRingtoneStops(ringing)
            }
            if (!running) return
            plugin.getLogger().warn("Could not create voice call invitation", e)
            error(callerSession, "Voice calls are not available right now.")
        }
    }
    private fun acceptInvite(acceptingSession: Player, token: String) {
        val targetId = acceptingSession.uniqueId
        val now = System.currentTimeMillis()
        val taken: Optional<CallInviteRegistry.Invite>
        var expiredRinging: CallInviteRegistry.Invite? = null
        synchronized(acceptanceLifecycleLock) {
            if (!running) return
            taken = invites.take(token, targetId, acceptingSession, now)
            if (taken.isPresent) registerRingtoneStopRetriesLocked(taken.get())
            else {
                expiredRinging = cancelInviteTasksForTarget(token, targetId, acceptingSession)
                expiredRinging?.let { registerRingtoneStopRetriesLocked(it) }
            }
        }
        if (taken.isEmpty) {
            expiredRinging?.let { publishRingtoneStops(it) }
            plugin.getServer().getPlayer(targetId).ifPresent { player -> error(player, "That call invitation is no longer valid.") }
            return
        }
        val invite = taken.get()
        publishRingtoneStops(invite)
        val caller = plugin.getServer().getPlayer(invite.callerId()).orElse(null)
        val target = plugin.getServer().getPlayer(targetId).orElse(null)
        if (caller !== invite.callerSession() || target !== invite.targetSession() || target !== acceptingSession) {
            if (target === acceptingSession) error(acceptingSession, "The caller is no longer online.")
            return
        }
        // Both sessions are the non-null Player sessions captured by createInvite.
        caller!!
        target!!
        val pool = jedisPool
        if (pool == null || pool.isClosed) { error(target, "Voice calls are not available right now."); return }
        try {
            pool.resource.use { jedis ->
                val callerGroupValue = jedis.get(playerGroupKey(invite.callerId()))
                val targetGroupValue = jedis.get(playerGroupKey(targetId))
                val callerCall = activeCall(jedis, callerGroupValue)
                if ((callerCall != null && callerCall.groupId() != invite.call().groupId())
                    || (callerCall != null && callerCall.password() != invite.call().password())
                    || (invite.callerWasInCall() && callerCall == null)) {
                    error(target, "The caller is no longer in that call."); return
                }
                val targetCall = activeCall(jedis, targetGroupValue)
                if (targetCall != null && targetCall.groupId() != invite.call().groupId()) { error(target, "You are already in another call."); return }
                val callerRoute = locationTracker.currentRoute(caller)
                val targetRoute = locationTracker.currentRoute(target)
                if (callerRoute == null || targetRoute == null) { error(target, "One of you disconnected; please try the call again."); return }
                val groupId = invite.call().groupId().toString()
                val groupDefinition = encodeCallGroupDefinition(invite.call())
                val callerGeneration = randomSecret(18)
                val targetGeneration = randomSecret(18)
                val callerTarget = callTargetValue(invite.call().groupId(), callerGeneration)
                val targetTarget = callTargetValue(invite.call().groupId(), targetGeneration)
                synchronized(acceptanceLifecycleLock) {
                    if (!running) return
                    if (plugin.getServer().getPlayer(invite.callerId()).orElse(null) !== caller || plugin.getServer().getPlayer(targetId).orElse(null) !== target) {
                        error(target, "One of you disconnected; please try the call again."); return
                    }
                    val result = jedis.eval(ACCEPT_CALL_SCRIPT,
                        listOf(PlayerLocationTracker.playerHomeKey(invite.callerId()), PlayerLocationTracker.playerHomeKey(targetId),
                            GROUPS_REGISTRY_KEY, PERMANENT_GROUPS_KEY, playerGroupKey(invite.callerId()), playerGroupKey(targetId),
                            callTargetKey(invite.callerId()), callTargetKey(targetId)),
                        listOf(callerRoute.value(), targetRoute.value(), groupId, groupDefinition, PLAYER_GROUP_TTL_SECONDS.toString(),
                            invite.callerId().toString(), targetId.toString(), GROUP_MEMBERS_KEY_PREFIX, CALL_TARGET_TTL_SECONDS.toString(),
                            callerTarget, targetTarget, CONTROL_CHANNEL, OP_GROUP_CHANGED + SEPARATOR,
                            encodeCallJoin(invite.call().groupId(), invite.callerId(), callerGeneration),
                            encodeCallJoin(invite.call().groupId(), targetId, targetGeneration), nullToEmpty(callerGroupValue), nullToEmpty(targetGroupValue)))
                    if (result !is Number || result.toLong() != 1L) {
                        error(target, "One of you disconnected; please try the call again.")
                        error(caller, "The call could not connect because one of you disconnected."); return
                    }
                    invites.activate(invite.callerId(), invite.call().groupId())
                    target.sendMessage(Component.text("Call accepted. Connecting you to ", NamedTextColor.GREEN)
                        .append(Component.text(invite.callerName(), NamedTextColor.AQUA)).append(Component.text("…", NamedTextColor.GREEN)))
                    caller.sendMessage(Component.text(invite.targetName(), NamedTextColor.AQUA)
                        .append(Component.text(" accepted your call. Connecting… Once connected, anyone in the call can use /call <player> to invite more.", NamedTextColor.GREEN)))
                }
            }
        } catch (e: Exception) {
            if (!running) return
            plugin.getLogger().warn("Could not connect voice call", e)
            error(target, "Voice calls are not available right now."); error(caller, "Voice calls are not available right now.")
        }
    }
    private fun declineInvite(decliningSession: Player, token: String) {
        val targetId = decliningSession.uniqueId
        val taken: Optional<CallInviteRegistry.Invite>
        var expiredRinging: CallInviteRegistry.Invite? = null
        synchronized(acceptanceLifecycleLock) {
            if (!running) return
            taken = invites.take(token, targetId, decliningSession, System.currentTimeMillis())
            if (taken.isPresent) registerRingtoneStopRetriesLocked(taken.get())
            else { expiredRinging = cancelInviteTasksForTarget(token, targetId, decliningSession); expiredRinging?.let { registerRingtoneStopRetriesLocked(it) } }
        }
        if (taken.isEmpty) {
            expiredRinging?.let { publishRingtoneStops(it) }
            plugin.getServer().getPlayer(targetId).ifPresent { player -> error(player, "That call invitation is no longer valid.") }
            return
        }
        val invite = taken.get()
        publishRingtoneStops(invite)
        plugin.getServer().getPlayer(targetId).ifPresent { player -> player.sendMessage(Component.text("Call declined.", NamedTextColor.YELLOW)) }
        plugin.getServer().getPlayer(invite.callerId()).ifPresent { player -> player.sendMessage(Component.text(invite.targetName(), NamedTextColor.AQUA)
            .append(Component.text(" declined your call.", NamedTextColor.YELLOW))) }
    }
    private fun beginRinging(invite: CallInviteRegistry.Invite, caller: Player, target: Player, jedis: Jedis) {
        if (!running) return
        val accept = Component.text("[ACCEPT]", NamedTextColor.GREEN, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand(acceptCommand(invite.token())))
            .hoverEvent(HoverEvent.showText(Component.text("Join the private voice call")))
        val decline = Component.text("[DECLINE]", NamedTextColor.RED, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand(declineCommand(invite.token())))
            .hoverEvent(HoverEvent.showText(Component.text("Dismiss this call")))
        val remainingMillis = Math.max(0L, invite.expiresAtMillis() - System.currentTimeMillis())
        var timeout: ScheduledTask? = null
        var retry: ScheduledTask? = null
        try {
            timeout = plugin.getServer().scheduler.buildTask(plugin, Runnable { expireInvite(invite.token()) }).delay(Duration.ofMillis(remainingMillis)).schedule()
            retry = plugin.getServer().scheduler.buildTask(plugin, Runnable { retryRingtoneStart(invite.token()) })
                .delay(RING_START_RETRY_INTERVAL).repeat(RING_START_RETRY_INTERVAL).schedule()
        } catch (e: RuntimeException) { timeout?.cancel(); retry?.cancel(); throw e }
        val tasks = InviteTasks(invite, retry!!, timeout!!)
        tasksByToken.put(invite.token(), tasks)?.cancel()
        publishRingtoneStarts(jedis, invite)
        target.sendMessage(Component.text("☎ ", NamedTextColor.GOLD).append(Component.text(invite.callerName(), NamedTextColor.AQUA))
            .append(Component.text(" is calling you! ", NamedTextColor.GOLD)).append(accept).append(Component.space()).append(decline))
        caller.sendMessage(Component.text("Calling ", NamedTextColor.YELLOW).append(Component.text(invite.targetName(), NamedTextColor.AQUA))
            .append(Component.text("…", NamedTextColor.YELLOW)))
    }
    private fun retryRingtoneStart(token: String) {
        synchronized(acceptanceLifecycleLock) {
            val tasks = tasksByToken[token]
            if (!running || tasks == null || !invites.isPending(token, tasks.invite.targetId(), System.currentTimeMillis())) return
            publishRingtoneStarts(tasks.invite)
        }
    }
    private fun expireInvite(token: String) {
        val expired: Optional<CallInviteRegistry.Invite>
        synchronized(acceptanceLifecycleLock) {
            expired = invites.expire(token, Long.MAX_VALUE)
            if (expired.isPresent) registerRingtoneStopRetriesLocked(expired.get()) else cancelInviteTasks(token)
        }
        if (expired.isEmpty) return
        val invite = expired.get()
        publishRingtoneStops(invite)
        plugin.getServer().getPlayer(invite.callerId()).ifPresent { player -> player.sendMessage(Component.text(invite.targetName(), NamedTextColor.AQUA)
            .append(Component.text(" didn't answer.", NamedTextColor.YELLOW))) }
        plugin.getServer().getPlayer(invite.targetId()).ifPresent { player -> player.sendMessage(Component.text("Missed call from ", NamedTextColor.YELLOW)
            .append(Component.text(invite.callerName(), NamedTextColor.AQUA)).append(Component.text(".", NamedTextColor.YELLOW))) }
    }
    private fun activeCall(jedis: Jedis, playerId: UUID): CallInviteRegistry.CallCredentials? = activeCall(jedis, jedis.get(playerGroupKey(playerId)))
    private fun activeCall(jedis: Jedis, groupValue: String?): CallInviteRegistry.CallCredentials? {
        if (groupValue == null) return null
        try {
            val groupId = UUID.fromString(groupValue)
            val definition = decodeGroupDefinition(groupId, jedis.hget(GROUPS_REGISTRY_KEY, groupValue))
            if (!isCallGroupDefinition(definition)) return null
            return CallInviteRegistry.CallCredentials(groupId, definition!!.password()!!, Long.MAX_VALUE)
        } catch (_: IllegalArgumentException) { return null }
    }
    private fun submit(feedback: Player, operation: Runnable) {
        if (!running) { error(feedback, "Voice calls are not available right now."); return }
        try { worker.execute(operation) }
        catch (_: RejectedExecutionException) { error(feedback, "The call service is busy; please try again.") }
    }
    private fun cancelInviteTasks(token: String) { tasksByToken.remove(token)?.cancel() }
    private fun cancelInviteTasksForTarget(token: String, targetId: UUID, targetSession: Any): CallInviteRegistry.Invite? {
        val tasks = tasksByToken[token]
        if (tasks == null || tasks.invite.targetId() != targetId || (tasks.invite.targetSession() != null && tasks.invite.targetSession() !== targetSession)
            || !tasksByToken.remove(token, tasks)) return null
        tasks.cancel()
        return tasks.invite
    }
    /** Must be called while holding acceptanceLifecycleLock. */
    private fun registerRingtoneStopRetriesLocked(invite: CallInviteRegistry.Invite) {
        cancelInviteTasks(invite.token())
        if (stopRetriesByToken.containsKey(invite.token()) || !running) return
        val retryDelayMillis = ringtoneStopRetryDelayMillis(invite.expiresAtMillis(), System.currentTimeMillis())
        if (retryDelayMillis == 0L) return
        val retry = StopRetry(invite)
        stopRetriesByToken[invite.token()] = retry
        try {
            val task = plugin.getServer().scheduler.buildTask(plugin, Runnable { retryRingtoneStop(invite.token()) })
                .delay(Duration.ofMillis(retryDelayMillis)).repeat(RING_STOP_RETRY_INTERVAL).schedule()
            retry.attach(task)
        } catch (e: RuntimeException) {
            // Retain the unscheduled entry for shutdown/reload's final STOP publication.
            plugin.getLogger().warn("Could not schedule voice call ringtone stops", e)
        }
    }
    private fun retryRingtoneStop(token: String) {
        val retry: StopRetry
        synchronized(acceptanceLifecycleLock) {
            retry = stopRetriesByToken[token] ?: return
            if (!running || !shouldRetryRingtoneStop(retry.invite().expiresAtMillis(), System.currentTimeMillis())) {
                if (stopRetriesByToken.remove(token, retry)) retry.cancel()
                return
            }
        }
        publishRingtoneStopRetry(retry.invite())
    }
    private fun publishRingtoneStops(invite: CallInviteRegistry.Invite) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        try { pool.resource.use { jedis -> publishRingtoneStops(jedis, invite) } }
        catch (e: Exception) { if (running) plugin.getLogger().warn("Could not stop voice call ringtones", e) }
    }
    private fun publishRingtoneStopRetry(invite: CallInviteRegistry.Invite) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        try { pool.resource.use { jedis -> publishRingtoneStopRetry(jedis, invite) } }
        catch (e: Exception) { if (running) plugin.getLogger().warn("Could not retry voice call ringtone stops", e) }
    }
    private fun publishRingtoneStarts(invite: CallInviteRegistry.Invite) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        try { pool.resource.use { jedis -> publishRingtoneStarts(jedis, invite) } }
        catch (e: Exception) { if (running) plugin.getLogger().warn("Could not retry voice call ringtones", e) }
    }
    private fun randomSecret(bytes: Int): String {
        val value = ByteArray(bytes)
        random.nextBytes(value)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
    private fun reserveInviteSlot(callerId: UUID, nowMillis: Long): Boolean {
        val reserved = AtomicBoolean()
        lastInviteAt.compute(callerId) { _, previous ->
            if (previous == null || nowMillis - previous >= INVITE_COOLDOWN_MILLIS) { reserved.set(true); nowMillis } else previous
        }
        return reserved.get()
    }
    enum class RingDirection { INCOMING, OUTGOING }
    data class GroupDefinition(private val id: UUID, private val name: String, private val password: String?, private val type: String,
                               private val hidden: Boolean, private val permanent: Boolean) {
        fun id() = id
        fun name() = name
        fun password() = password
        fun type() = type
        fun hidden() = hidden
        fun permanent() = permanent
    }
    private data class InviteTasks(val invite: CallInviteRegistry.Invite, val retry: ScheduledTask, val timeout: ScheduledTask) {
        fun cancel() { retry.cancel(); timeout.cancel() }
    }
    private class StopRetry(private val invite: CallInviteRegistry.Invite) {
        private var task: ScheduledTask? = null
        private var cancelled = false
        fun invite() = invite
        @Synchronized fun attach(scheduledTask: ScheduledTask) { if (cancelled) scheduledTask.cancel() else task = scheduledTask }
        @Synchronized fun cancel() { cancelled = true; task?.cancel() }
    }
    companion object {
        const val CONTROL_CHANNEL = "crabcraft:svc:lifecycle"
        const val OP_CALL_JOIN = "CALL_JOIN"
        const val OP_CALL_RING_START = "CALL_RING_START"
        const val OP_CALL_RING_STOP = "CALL_RING_STOP"
        const val OP_GROUP_CHANGED = "GROUP_CHANGED"
        const val GROUPS_REGISTRY_KEY = "crabcraft:svc:groups"
        const val PERMANENT_GROUPS_KEY = "crabcraft:svc:groups:permanent"
        const val PLAYER_GROUP_KEY_PREFIX = "crabcraft:svc:player-group:"
        const val GROUP_MEMBERS_KEY_PREFIX = "crabcraft:svc:group-members:"
        const val CALL_TARGET_KEY_PREFIX = "crabcraft:svc:call-target:"
        const val SEPARATOR = "\u0000"
        private const val CALL_GROUP_NAME = "Private Call"
        const val INVITE_TIMEOUT_MILLIS = 30_000L
        private const val INVITE_COOLDOWN_MILLIS = 2_000L
        private const val MAXIMUM_OUTGOING_INVITES = 5
        private const val PROVISIONAL_CALL_LIFETIME_MILLIS = 180_000L
        private const val PLAYER_GROUP_TTL_SECONDS = 90L
        private const val CALL_TARGET_TTL_SECONDS = 90L
        private val RING_START_RETRY_INTERVAL = Duration.ofSeconds(2L)
        private val RING_STOP_RETRY_INTERVAL = Duration.ofSeconds(2L)
        private val ACCEPT_CALL_SCRIPT = """if redis.call('GET', KEYS[1]) ~= ARGV[1]
        or redis.call('GET', KEYS[2]) ~= ARGV[2] then
    return 0
end
local callerOld = redis.call('GET', KEYS[5])
local targetOld = redis.call('GET', KEYS[6])
if (callerOld or '') ~= ARGV[16] or (targetOld or '') ~= ARGV[17] then
    return 0
end
local currentDefinition = redis.call('HGET', KEYS[3], ARGV[3])
if currentDefinition and currentDefinition ~= ARGV[4] then
    return 0
end

local definitionChanged = currentDefinition ~= ARGV[4]
redis.call('HSET', KEYS[3], ARGV[3], ARGV[4])
redis.call('SREM', KEYS[4], ARGV[3])

local function moveMember(playerKey, playerId, previousGroup)
    if previousGroup and previousGroup ~= ARGV[3] then
        local previousMembers = ARGV[8] .. previousGroup
        redis.call('SREM', previousMembers, playerId)
        if redis.call('SCARD', previousMembers) == 0
                and redis.call('SISMEMBER', KEYS[4], previousGroup) == 0 then
            if redis.call('HDEL', KEYS[3], previousGroup) > 0 then
                redis.call('PUBLISH', ARGV[12], ARGV[13] .. previousGroup)
            end
            redis.call('DEL', previousMembers)
        end
    end
    redis.call('SETEX', playerKey, ARGV[5], ARGV[3])
    redis.call('SADD', ARGV[8] .. ARGV[3], playerId)
end

moveMember(KEYS[5], ARGV[6], callerOld)
moveMember(KEYS[6], ARGV[7], targetOld)
redis.call('SETEX', KEYS[7], ARGV[9], ARGV[10])
redis.call('SETEX', KEYS[8], ARGV[9], ARGV[11])
if definitionChanged then
    redis.call('PUBLISH', ARGV[12], ARGV[13] .. ARGV[3])
end
redis.call('PUBLISH', ARGV[12], ARGV[14])
redis.call('PUBLISH', ARGV[12], ARGV[15])
return 1
"""
        private val RETRY_CALL_RING_STOP_SCRIPT = """local current = redis.call('TIME')
local nowMillis = (tonumber(current[1]) * 1000) + math.floor(tonumber(current[2]) / 1000)
if nowMillis >= tonumber(ARGV[4]) then return 0 end
redis.call('publish', ARGV[1], ARGV[2])
redis.call('publish', ARGV[1], ARGV[3])
return 1
"""

        private fun publishRingtoneStarts(jedis: Jedis, invite: CallInviteRegistry.Invite) {
            jedis.publish(CONTROL_CHANNEL, encodeCallRingStart(invite.token(), invite.callerId(), RingDirection.OUTGOING, invite.expiresAtMillis()))
            jedis.publish(CONTROL_CHANNEL, encodeCallRingStart(invite.token(), invite.targetId(), RingDirection.INCOMING, invite.expiresAtMillis()))
        }
        private fun publishRingtoneStops(jedis: Jedis, invite: CallInviteRegistry.Invite) {
            jedis.publish(CONTROL_CHANNEL, encodeCallRingStop(invite.token(), invite.callerId(), RingDirection.OUTGOING))
            jedis.publish(CONTROL_CHANNEL, encodeCallRingStop(invite.token(), invite.targetId(), RingDirection.INCOMING))
        }
        private fun publishRingtoneStopRetry(jedis: Jedis, invite: CallInviteRegistry.Invite) {
            jedis.eval(RETRY_CALL_RING_STOP_SCRIPT, emptyList(), encodeCallRingStopRetryArguments(invite))
        }
        @JvmStatic fun encodeCallRingStopRetryArguments(invite: CallInviteRegistry.Invite): List<String> =
            listOf(CONTROL_CHANNEL, encodeCallRingStop(invite.token(), invite.callerId(), RingDirection.OUTGOING),
                encodeCallRingStop(invite.token(), invite.targetId(), RingDirection.INCOMING), invite.expiresAtMillis().toString())
        private fun isValidPassword(password: String?): Boolean {
            if (password == null || password.length < 22 || password.length > 64) return false
            for (character in password) if (character !in 'a'..'z' && character !in 'A'..'Z' && character !in '0'..'9' && character != '-' && character != '_') return false
            return true
        }
        @JvmStatic fun encodeCallJoin(groupId: UUID?, playerId: UUID?, generation: String?): String {
            requireValidRingToken(generation)
            require(groupId != null && playerId != null) { "Invalid call join" }
            return listOf(OP_CALL_JOIN, groupId.toString(), playerId.toString(), generation).joinToString(SEPARATOR)
        }
        @JvmStatic fun callTargetValue(groupId: UUID?, generation: String?): String {
            requireValidRingToken(generation)
            require(groupId != null) { "Invalid call target" }
            return groupId.toString() + SEPARATOR + generation
        }
        @JvmStatic fun encodeCallGroupDefinition(call: CallInviteRegistry.CallCredentials?): String {
            require(call != null && isValidPassword(call.password())) { "Invalid call group" }
            return listOf(encodeText(CALL_GROUP_NAME), "1", encodeText(call.password()), "OPEN", "1", "0").joinToString(SEPARATOR)
        }
        @JvmStatic fun decodeGroupDefinition(groupId: UUID?, encoded: String?): GroupDefinition? {
            if (groupId == null || encoded == null) return null
            val fields = encoded.split(SEPARATOR)
            if (fields.size != 6 || fields[1] !in listOf("0", "1") || fields[3] !in listOf("NORMAL", "OPEN", "ISOLATED")
                || fields[4] !in listOf("0", "1") || fields[5] !in listOf("0", "1")) return null
            try {
                val password = if (fields[1] == "1") decodeText(fields[2]) else null
                return GroupDefinition(groupId, decodeText(fields[0]), password, fields[3], fields[4] == "1", fields[5] == "1")
            } catch (_: IllegalArgumentException) { return null }
        }
        @JvmStatic fun isCallGroupDefinition(definition: GroupDefinition?): Boolean = definition != null && definition.name() == CALL_GROUP_NAME
            && definition.hidden() && !definition.permanent() && definition.type() == "OPEN" && isValidPassword(definition.password())
        private fun encodeText(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        private fun decodeText(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
        private fun nullToEmpty(value: String?): String = value ?: ""
        @JvmStatic fun encodeCallRingStart(token: String?, playerId: UUID?, direction: RingDirection?, expiresAtMillis: Long): String {
            requireValidRingToken(token)
            require(playerId != null && direction != null && expiresAtMillis > 0L) { "Invalid call ringtone start" }
            return listOf(OP_CALL_RING_START, token, playerId.toString(), direction.name, expiresAtMillis.toString()).joinToString(SEPARATOR)
        }
        @JvmStatic fun encodeCallRingStop(token: String?, playerId: UUID?, direction: RingDirection?): String {
            requireValidRingToken(token)
            require(playerId != null && direction != null) { "Invalid call ringtone stop" }
            return listOf(OP_CALL_RING_STOP, token, playerId.toString(), direction.name).joinToString(SEPARATOR)
        }
        @JvmStatic fun shouldRetryRingtoneStop(expiresAtMillis: Long, nowMillis: Long) = nowMillis < expiresAtMillis
        @JvmStatic fun ringtoneStopRetryDelayMillis(expiresAtMillis: Long, nowMillis: Long): Long {
            if (!shouldRetryRingtoneStop(expiresAtMillis, nowMillis)) return 0L
            val remainingMillis = expiresAtMillis - nowMillis
            return Math.min(RING_STOP_RETRY_INTERVAL.toMillis(), Math.max(1L, remainingMillis / 2L))
        }
        private fun requireValidRingToken(token: String?) {
            require(token != null && token.length >= 22 && token.length <= 64) { "Invalid call ringtone token" }
            for (character in token) require(character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '-' || character == '_') { "Invalid call ringtone token" }
        }
        @JvmStatic fun acceptCommand(token: String) = "/call accept " + token
        @JvmStatic fun declineCommand(token: String) = "/call decline " + token
        private fun playerGroupKey(playerId: UUID) = PLAYER_GROUP_KEY_PREFIX + playerId
        private fun callTargetKey(playerId: UUID) = CALL_TARGET_KEY_PREFIX + playerId
        private fun error(player: Player, message: String) { player.sendMessage(Component.text(message, NamedTextColor.RED)) }
    }
}
