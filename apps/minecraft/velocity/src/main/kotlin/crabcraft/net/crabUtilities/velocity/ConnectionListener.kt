package crabcraft.net.crabUtilities.velocity

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.scheduler.ScheduledTask
import net.luckperms.api.node.types.InheritanceNode
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import crabcraft.net.crabUtilities.velocity.db.LoginStreakService
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Supplier

open class ConnectionListener(private val plugin: CrabUtilitiesVelocity) {
    private val announcedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val nicknameSeeds = ConcurrentHashMap.newKeySet<UUID>()
    private val activeStreakSessions = ConcurrentHashMap<UUID, ActiveStreakSession>()
    private val jadeHandshakes = ConcurrentHashMap<UUID, ByteArray>()
    init { plugin.getServer().channelRegistrar.register(JADE_CLIENT_HANDSHAKE, CLIENT_PROTOCOL) }

    @Subscribe(order = PostOrder.EARLY)
    open fun onPluginMessage(event: PluginMessageEvent) {
        if (CLIENT_PROTOCOL == event.identifier) { event.result = PluginMessageEvent.ForwardResult.handled(); return }
        val player = event.source as? Player
        val connection = event.target as? ServerConnection
        if (JADE_CLIENT_HANDSHAKE != event.identifier || player == null || connection == null) return
        if (!sendClientProtocol(player, connection)) { event.result = PluginMessageEvent.ForwardResult.handled(); return }
        event.result = PluginMessageEvent.ForwardResult.forward()
        val data = event.data
        if (data.size <= MAX_JADE_HANDSHAKE_SIZE) jadeHandshakes[player.uniqueId] = data
    }
    @Subscribe(order = PostOrder.EARLY)
    open fun onLogin(event: LoginEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        // Warm settings and authoritative nicknames before backends need them.
        plugin.getPlayerSettingsService()?.onLogin(player.uniqueId)
        ensureNicknameSeed(player)
        // Credit streaks only after cumulative play time reaches the requirement.
        startLoginStreakSession(player)
        val luckPerms = plugin.getLuckPerms() ?: return
        val playerId = player.uniqueId
        val username = player.username
        plugin.runDatabaseTask("alt-status-check", Runnable {
            val altQueryService = plugin.getAltQueryService()
            val isAlt = altQueryService.isAlt(uuid)
            // Alts only ever hold a one-day streak.
            if (isAlt) plugin.getLoginStreakService().capAltStreak(uuid)
            if (!isPlayerActive(playerId)) return@Runnable
            if (isAlt) {
                luckPerms.userManager.modifyUser(playerId) { user ->
                    if (!isPlayerActive(playerId)) return@modifyUser
                    user.data().add(InheritanceNode.builder(ALT_GROUP).build())
                }.whenComplete { _, e ->
                    if (e != null) plugin.getLogger().error("Failed to assign '{}' group to alt {} ({})", ALT_GROUP, username, uuid, e)
                    else if (isPlayerActive(playerId)) plugin.getLogger().info("Alt account {} ({}) — assigned '{}' group", username, uuid, ALT_GROUP)
                }
            } else {
                // Remove stale membership when an alt was removed from the database.
                luckPerms.userManager.modifyUser(playerId) { user ->
                    if (!isPlayerActive(playerId)) return@modifyUser
                    user.data().remove(InheritanceNode.builder(ALT_GROUP).build())
                }.whenComplete { _, e ->
                    if (e != null) plugin.getLogger().error("Failed to remove '{}' group from {} ({})", ALT_GROUP, username, uuid, e)
                }
            }
        })
    }
    @Subscribe open fun onServerPostConnect(event: ServerPostConnectEvent) {
        val player = event.player
        val previousServer = event.previousServer
        val currentConnection = player.currentServer.orElse(null) ?: return
        val currentServer = currentConnection.server
        if (previousServer != null) {
            val handshake = jadeHandshakes[player.uniqueId]
            if (handshake != null && sendClientProtocol(player, currentConnection) && !currentConnection.sendPluginMessage(JADE_CLIENT_HANDSHAKE, handshake)) {
                plugin.getLogger().warn("Could not replay Jade handshake for {} to {}", player.username, currentServer.serverInfo.name)
            }
        }
        val currentServerName = currentServer.serverInfo.name
        // Unknown nickname state must not be sent as a clear while its seed is in flight.
        if (plugin.getNicknameCache().isLoaded(player.uniqueId)) publishNicknameToBackends(player) else ensureNicknameSeed(player)
        if (previousServer == null) {
            if (isIgnored(currentServerName)) return
            if (plugin.getNicknameCache().isLoaded(player.uniqueId)) { broadcastJoin(player); return }
            val pending = plugin.getPendingJoinManager().register(player.uniqueId)
            ensureNicknameSeed(player)
            pending.orTimeout(2, TimeUnit.SECONDS).whenComplete { _, _ ->
                plugin.getServer().scheduler.buildTask(plugin, Runnable { broadcastJoin(player) }).schedule()
            }
        } else {
            val previousServerName = previousServer.serverInfo.name
            if (isIgnored(currentServerName) || isIgnored(previousServerName)) return
            val displayName = getDisplayName(player)
            val message = MINI_MESSAGE.deserialize("<yellow><name> swapped to the <server> server</yellow>",
                Placeholder.component("name", displayName), Placeholder.unparsed("server", currentServerName))
            broadcast(message)
            val discordMsg = formatDiscord(plugin.getConfig().getDiscordSwapFormat(), player, currentServerName)
            plugin.getDiscordWebhook().send(discordMsg)
        }
    }
    @Subscribe(order = PostOrder.EARLY)
    open fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        jadeHandshakes.remove(player.uniqueId)
        finishLoginStreakSession(player.uniqueId)
        plugin.getPlayerSettingsService()?.onDisconnect(player.uniqueId)
        if (!announcedPlayers.remove(player.uniqueId)) return
        val lastServer = player.currentServer.map { conn -> conn.server }.orElse(null)
        if (lastServer != null && isIgnored(lastServer.serverInfo.name)) return
        val displayName = getDisplayName(player)
        val message = MINI_MESSAGE.deserialize("<yellow><name> left the game</yellow>", Placeholder.component("name", displayName))
        broadcast(message)
        val discordMsg = formatDiscord(plugin.getConfig().getDiscordLeaveFormat(), player, null)
        plugin.getDiscordWebhook().send(discordMsg)
    }
    open fun shutdown() {
        jadeHandshakes.clear(); nicknameSeeds.clear()
        plugin.getServer().channelRegistrar.unregister(JADE_CLIENT_HANDSHAKE, CLIENT_PROTOCOL)
        val now = epochSeconds()
        for ((playerId, session) in activeStreakSessions.entries) {
            if (!activeStreakSessions.remove(playerId, session)) continue
            val segment = session.close(now)
            if (segment != null) recordLoginStreakPlaytime(session, segment, false)
        }
    }
    private fun sendClientProtocol(player: Player, connection: ServerConnection): Boolean {
        val sent = connection.sendPluginMessage(CLIENT_PROTOCOL, encodeClientProtocol(player.protocolVersion.protocol))
        if (!sent) plugin.getLogger().warn("Could not send client protocol for {} to {}", player.username, connection.server.serverInfo.name)
        return sent
    }
    private fun startLoginStreakSession(player: Player) {
        val playerId = player.uniqueId
        val now = epochSeconds()
        val session = ActiveStreakSession(playerId, now)
        val previous = activeStreakSessions.put(playerId, session)
        if (previous != null) {
            val previousSegment = previous.close(now)
            if (previousSegment != null) recordLoginStreakPlaytime(previous, previousSegment, false)
        }
        val uuid = playerId.toString()
        plugin.runDatabaseTask("login-streak-progress-load", Runnable {
            val streakService = plugin.getLoginStreakService()
            val streakPublisher = plugin.getLoginStreakPublisher()
            if (streakPublisher != null) {
                seedLoginStreakCache(Supplier { streakService.get(uuid) }, Consumer { snapshot -> streakPublisher.publish(uuid, snapshot, streakService.getResetHourUtc()) })
            }
            val progress = streakService.getQualificationProgress(uuid) ?: return@Runnable
            if (!isCurrentStreakSession(session) || !isPlayerActive(playerId)) return@Runnable
            scheduleNextQualificationCheck(session, progress)
        })
    }
    private fun finishLoginStreakSession(playerId: UUID) {
        val session = activeStreakSessions.remove(playerId) ?: return
        val segment = session.close(epochSeconds())
        if (segment != null) recordLoginStreakPlaytime(session, segment, false)
    }
    private fun creditActiveLoginStreakSession(session: ActiveStreakSession) {
        val playerId = session.playerId
        if (!isCurrentStreakSession(session) || !isPlayerActive(playerId)) return
        val segment = session.takeSegment(epochSeconds())
        if (segment != null) recordLoginStreakPlaytime(session, segment, true)
        else {
            val retry = plugin.getServer().scheduler.buildTask(plugin, Runnable { creditActiveLoginStreakSession(session) }).delay(Duration.ofSeconds(1)).schedule()
            session.setTask(retry)
        }
    }
    private fun recordLoginStreakPlaytime(session: ActiveStreakSession, segment: CreditSegment, reschedule: Boolean) {
        val streakService = plugin.getLoginStreakService()
        val streakPublisher = plugin.getLoginStreakPublisher()
        val playerId = session.playerId
        val uuid = playerId.toString()
        plugin.runDatabaseTask("login-streak-playtime", Runnable {
            val result = streakService.recordPlaytime(uuid, segment.from, segment.to) ?: return@Runnable
            if (result.streakSnapshot != null && streakPublisher != null) streakPublisher.publish(uuid, result.streakSnapshot, streakService.getResetHourUtc())
            if (reschedule && result.progress != null && isCurrentStreakSession(session) && isPlayerActive(playerId)) {
                scheduleNextQualificationCheck(session, result.progress)
            }
        })
    }
    private fun scheduleNextQualificationCheck(session: ActiveStreakSession, progress: LoginStreakService.QualificationProgress) {
        val streakService = plugin.getLoginStreakService()
        val now = epochSeconds()
        val delaySeconds = if (progress.qualified) streakService.secondsUntilNextStreakDay(now) + streakService.getRequiredPlaySeconds()
            else progress.remainingSeconds() - session.secondsSinceLastCredit(now)
        val taskBody = Runnable { creditActiveLoginStreakSession(session) }
        val task = if (delaySeconds <= 0L) plugin.getServer().scheduler.buildTask(plugin, taskBody).schedule()
            else plugin.getServer().scheduler.buildTask(plugin, taskBody).delay(Duration.ofSeconds(delaySeconds)).schedule()
        session.setTask(task)
    }
    private fun isCurrentStreakSession(session: ActiveStreakSession) = activeStreakSessions[session.playerId] === session
    private fun broadcastJoin(player: Player) {
        if (!player.isActive) return
        // Database reads and writes run off-thread; messaging is thread-safe.
        val playerId = player.uniqueId
        val playerUuid = playerId.toString()
        val playerName = player.username
        plugin.runDatabaseTask("join-broadcast", Runnable {
            val firstJoin = !plugin.getPgWriter()!!.hasJoinedBefore(playerUuid)
            if (!player.isActive) return@Runnable
            // Atomically suppress duplicate announcements for a quick relog.
            if (!announcedPlayers.add(playerId)) return@Runnable
            val displayName = getDisplayName(player)
            val inGameFormat = if (firstJoin) plugin.getConfig().getFirstJoinFormat() else "<yellow><name> joined the game</yellow>"
            val message = MINI_MESSAGE.deserialize(inGameFormat, Placeholder.component("name", displayName), Placeholder.unparsed("username", player.username))
            broadcast(message)
            val discordFormat = if (firstJoin) plugin.getConfig().getDiscordFirstJoinFormat() else plugin.getConfig().getDiscordJoinFormat()
            val discordMsg = formatDiscord(discordFormat, player, null)
            plugin.getDiscordWebhook().send(discordMsg)
            // Only write after hasJoinedBefore, preserving first-login detection.
            val plain = plugin.getNicknameCache().getPlainNickname(playerId)
            val raw = plugin.getNicknameCache().getRawNickname(playerId)
            val leaderboardReactivated = plugin.getPgWriter()!!.upsertPlayer(playerUuid, playerName, plain, raw)
            if (leaderboardReactivated) {
                plugin.getAwardDbWriter().recomputeAllMedals()
                plugin.getLogger().info("Restored leaderboard eligibility after login for {}", playerName)
            }
            plugin.getPgWriter()!!.upsertAltUsername(playerUuid, playerName)
            plugin.getPgWriter()!!.recordMcLogin(playerUuid)
        })
    }
    private fun getDisplayName(player: Player): Component {
        val raw = plugin.getNicknameCache().getRawNickname(player.uniqueId)
        return if (raw != null) NicknameComponentParser.parse(raw) else Component.text(player.username)
    }
    private fun getPlainDisplayName(player: Player): String = plugin.getNicknameCache().getPlainNickname(player.uniqueId) ?: player.username
    private fun formatDiscord(template: String, player: Player, serverName: String?): String {
        var result = template.replace("{name}", getPlainDisplayName(player)).replace("{username}", player.username)
        if (serverName != null) result = result.replace("{server}", serverName)
        return result
    }
    private fun ensureNicknameSeed(player: Player) {
        val id = player.uniqueId
        if (plugin.getNicknameCache().isLoaded(id)) { plugin.getPendingJoinManager().complete(id); return }
        if (!nicknameSeeds.add(id)) return
        val queued = plugin.runDatabaseTask("nickname-seed", Runnable { try { seedNickname(player) } finally { finishNicknameSeed(player) } })
        if (!queued) { nicknameSeeds.remove(id); plugin.getPendingJoinManager().complete(id) }
    }
    private fun finishNicknameSeed(seededPlayer: Player) {
        val id = seededPlayer.uniqueId
        nicknameSeeds.remove(id)
        val currentPlayer = plugin.getServer().getPlayer(id).filter { it.isActive }.orElse(null)
        if (currentPlayer != null && currentPlayer !== seededPlayer && !plugin.getNicknameCache().isLoaded(id)) { ensureNicknameSeed(currentPlayer); return }
        plugin.getPendingJoinManager().complete(id)
    }
    private fun seedNickname(player: Player) {
        if (!player.isActive) return
        val id = player.uniqueId
        val cache = plugin.getNicknameCache()
        val started = cache.beginLoad(id)
        try {
            if (started.loaded()) { publishNickname(id, started); return }
            val listener = plugin.getNicknameListener()
            if (listener != null) {
                val redisRaw = listener.loadRawNickname(id)
                if (redisRaw != null) {
                    if (player.isActive && cache.commitIfVersion(id, started.version(), redisRaw)) listener.persist(id)
                    return
                }
            }
            val result = plugin.getPgWriter()!!.loadRawNickname(id.toString())
            if (!player.isActive) return
            if (commitNicknameLoad(cache, id, started.version(), result)) publishNicknameToBackends(player)
        } finally { if (!player.isActive && !started.loaded()) cache.discardIfUnloadedVersion(id, started.version()) }
    }
    private fun publishNicknameToBackends(player: Player) {
        val id = player.uniqueId
        val snapshot = plugin.getNicknameCache().snapshot(id)
        if (snapshot.loaded()) publishNickname(id, snapshot)
    }
    private fun publishNickname(uuid: UUID, snapshot: NicknameCache.Snapshot) {
        plugin.getNicknameListener()?.publishNickname(uuid, snapshot.rawNickname(), snapshot.version())
    }
    private fun isIgnored(serverName: String) = plugin.getConfig().getIgnoredServers().contains(serverName.lowercase(Locale.getDefault()))
    private fun isPlayerActive(playerId: UUID) = plugin.getServer().getPlayer(playerId).map { it.isActive }.orElse(false)
    private fun broadcast(message: Component) { for (player in plugin.getServer().allPlayers) player.sendMessage(message) }
    private class ActiveStreakSession(val playerId: UUID, startedAt: Long) {
        private var lastCreditedAt = startedAt
        private var closed = false
        private var qualificationTask: ScheduledTask? = null
        @Synchronized fun takeSegment(now: Long): CreditSegment? {
            if (closed || now <= lastCreditedAt) return null
            val from = lastCreditedAt
            lastCreditedAt = now
            return CreditSegment(from, now)
        }
        @Synchronized fun close(now: Long): CreditSegment? {
            if (closed) return null
            closed = true
            qualificationTask?.cancel(); qualificationTask = null
            if (now <= lastCreditedAt) return null
            val from = lastCreditedAt
            lastCreditedAt = now
            return CreditSegment(from, now)
        }
        @Synchronized fun setTask(task: ScheduledTask) {
            if (closed) { task.cancel(); return }
            qualificationTask?.cancel()
            qualificationTask = task
        }
        @Synchronized fun secondsSinceLastCredit(now: Long) = Math.max(0L, now - lastCreditedAt)
    }
    private class CreditSegment(val from: Long, val to: Long)
    companion object {
        private val MINI_MESSAGE = MiniMessage.miniMessage()
        private const val ALT_GROUP = "alt"
        private const val MAX_JADE_HANDSHAKE_SIZE = 256
        private val JADE_CLIENT_HANDSHAKE = MinecraftChannelIdentifier.from("jade:client_handshake")
        // The backend needs the original protocol for Jade's embedded registry IDs.
        private val CLIENT_PROTOCOL = MinecraftChannelIdentifier.from("crabcraft:client_protocol")
        @JvmStatic fun encodeClientProtocol(protocol: Int): ByteArray = ByteBuffer.allocate(Integer.BYTES).putInt(protocol).array()
        @JvmStatic fun seedLoginStreakCache(load: Supplier<LoginStreakService.StreakSnapshot?>, publish: Consumer<LoginStreakService.StreakSnapshot>) {
            val snapshot = load.get()
            if (snapshot != null) publish.accept(snapshot)
        }
        @JvmStatic fun commitNicknameLoad(cache: NicknameCache, id: UUID, expectedVersion: Long, result: PostgresStatsWriter.NicknameLoadResult): Boolean =
            when (result.status()) {
                PostgresStatsWriter.NicknameLoadStatus.FOUND -> cache.commitIfVersion(id, expectedVersion, result.rawNickname())
                PostgresStatsWriter.NicknameLoadStatus.ABSENT -> cache.commitIfVersion(id, expectedVersion, "")
                PostgresStatsWriter.NicknameLoadStatus.FAILED -> false
            }
        private fun epochSeconds() = System.currentTimeMillis() / 1000L
    }
}
