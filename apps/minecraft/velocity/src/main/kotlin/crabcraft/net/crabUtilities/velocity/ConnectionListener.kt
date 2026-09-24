package crabcraft.net.crabUtilities.velocity

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.scheduler.ScheduledTask
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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.luckperms.api.node.types.InheritanceNode

open class ConnectionListener(private val plugin: CrabUtilitiesVelocity) {
    private val announcedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val silentJoinPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val nicknameSeeds = ConcurrentHashMap.newKeySet<UUID>()
    private val activeStreakSessions = ConcurrentHashMap<UUID, ActiveStreakSession>()
    private val jadeHandshakes = ConcurrentHashMap<UUID, ByteArray>()

    init {
        plugin.getServer().channelRegistrar.register(JADE_CLIENT_HANDSHAKE, CLIENT_PROTOCOL)
    }

    @Subscribe(order = PostOrder.EARLY)
    open fun onPluginMessage(event: PluginMessageEvent) {
        if (CLIENT_PROTOCOL == event.identifier) {
            event.result = PluginMessageEvent.ForwardResult.handled()
            return
        }
        if (JADE_CLIENT_HANDSHAKE != event.identifier) return
        val player = event.source as? Player ?: return
        val connection = event.target as? ServerConnection ?: return
        if (!sendClientProtocol(player, connection)) {
            event.result = PluginMessageEvent.ForwardResult.handled()
            return
        }
        event.result = PluginMessageEvent.ForwardResult.forward()
        val data = event.data
        if (data.size <= MAX_JADE_HANDSHAKE_SIZE) jadeHandshakes[player.uniqueId] = data
    }

    @Subscribe(order = PostOrder.EARLY)
    open fun onLogin(event: LoginEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val virtualHost = player.virtualHost.map { it.hostString }.orElse("")
        if (isSilentJoinHost(virtualHost, plugin.getConfig().getSilentJoinHosts())) {
            silentJoinPlayers.add(player.uniqueId)
        } else {
            silentJoinPlayers.remove(player.uniqueId)
        }
        // Warm settings and seed the authoritative nickname before backend features read them.
        plugin.getPlayerSettingsService()?.onLogin(player.uniqueId)
        ensureNicknameSeed(player)
        startLoginStreakSession(player)
        val luckPerms = plugin.getLuckPerms() ?: return
        val playerId = player.uniqueId
        val username = player.username
        plugin.runDatabaseTask(
            "alt-status-check",
            Runnable {
                val altQueryService = plugin.getAltQueryService() ?: return@Runnable
                val isAlt = altQueryService.isAlt(uuid)
                // Alts only hold a one-day streak, including accounts registered as alts later.
                if (isAlt) plugin.getLoginStreakService()?.capAltStreak(uuid)
                if (!isPlayerActive(playerId)) return@Runnable
                if (isAlt) {
                    luckPerms.userManager
                        .modifyUser(playerId) { user ->
                            if (isPlayerActive(playerId)) user.data().add(InheritanceNode.builder(ALT_GROUP).build())
                        }
                        .whenComplete { _, e ->
                            if (e != null) {
                                plugin
                                    .getLogger()
                                    .error("Failed to assign '{}' group to alt {} ({})", ALT_GROUP, username, uuid, e)
                            } else if (isPlayerActive(playerId)) {
                                plugin
                                    .getLogger()
                                    .info("Alt account {} ({}) — assigned '{}' group", username, uuid, ALT_GROUP)
                            }
                        }
                } else {
                    luckPerms.userManager
                        .modifyUser(playerId) { user ->
                            if (isPlayerActive(playerId)) user.data().remove(InheritanceNode.builder(ALT_GROUP).build())
                        }
                        .whenComplete { _, e ->
                            if (e != null)
                                plugin
                                    .getLogger()
                                    .error("Failed to remove '{}' group from {} ({})", ALT_GROUP, username, uuid, e)
                        }
                }
            },
        )
    }

    @Subscribe
    open fun onServerPostConnect(event: ServerPostConnectEvent) {
        val player = event.player
        val previousServer = event.previousServer
        val connection = player.currentServer.orElse(null) ?: return
        val currentServer = connection.server
        if (previousServer != null) {
            val handshake = jadeHandshakes[player.uniqueId]
            if (
                handshake != null &&
                    sendClientProtocol(player, connection) &&
                    !connection.sendPluginMessage(JADE_CLIENT_HANDSHAKE, handshake)
            ) {
                plugin
                    .getLogger()
                    .warn(
                        "Could not replay Jade handshake for {} to {}",
                        player.username,
                        currentServer.serverInfo.name,
                    )
            }
        }
        val currentServerName = currentServer.serverInfo.name
        if (player.uniqueId in silentJoinPlayers) plugin.getVanishManager().applyVanish(player, currentServer)
        // Unknown nickname state must not be published as a clear while seeding is in flight.
        if (plugin.getNicknameCache().isLoaded(player.uniqueId)) publishNicknameToBackends(player)
        else ensureNicknameSeed(player)
        if (previousServer == null) {
            // Only the exact backend's visibility report may expose this connection.
            plugin.getVanishManager().beginSession(player, currentServer) { visible ->
                handleInitialJoin(player, currentServerName, visible)
            }
            return
        }
        val previousServerName = previousServer.serverInfo.name
        plugin.getVanishManager().beginSession(player, currentServer) { visible ->
            if (visible) broadcastSwap(player, previousServerName, currentServerName)
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    open fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        jadeHandshakes.remove(player.uniqueId)
        silentJoinPlayers.remove(player.uniqueId)
        finishLoginStreakSession(player.uniqueId)
        plugin.getPlayerSettingsService()?.onDisconnect(player.uniqueId)
        val publiclyVisible = plugin.getVanishManager().isVisible(player)
        if (!announcedPlayers.remove(player.uniqueId) || !publiclyVisible) return
        val lastServer = player.currentServer.map { it.server }.orElse(null)
        if (lastServer != null && isIgnored(lastServer.serverInfo.name)) return
        broadcast(
            MINI_MESSAGE.deserialize(
                "<yellow><name> left the game</yellow>",
                Placeholder.component("name", getDisplayName(player)),
            )
        )
        plugin.getDiscordWebhook().send(formatDiscord(plugin.getConfig().getDiscordLeaveFormat(), player, null))
    }

    open fun shutdown() {
        jadeHandshakes.clear()
        nicknameSeeds.clear()
        silentJoinPlayers.clear()
        plugin.getServer().channelRegistrar.unregister(JADE_CLIENT_HANDSHAKE, CLIENT_PROTOCOL)
        val now = epochSeconds()
        for ((playerId, session) in activeStreakSessions) {
            if (!activeStreakSessions.remove(playerId, session)) continue
            session.close(now)?.let { recordLoginStreakPlaytime(session, it, false) }
        }
    }

    private fun sendClientProtocol(player: Player, connection: ServerConnection): Boolean {
        val sent = connection.sendPluginMessage(CLIENT_PROTOCOL, encodeClientProtocol(player.protocolVersion.protocol))
        if (!sent)
            plugin
                .getLogger()
                .warn(
                    "Could not send client protocol for {} to {}",
                    player.username,
                    connection.server.serverInfo.name,
                )
        return sent
    }

    private fun startLoginStreakSession(player: Player) {
        val playerId = player.uniqueId
        val now = epochSeconds()
        val session = ActiveStreakSession(playerId, now)
        val previous = activeStreakSessions.put(playerId, session)
        previous?.close(now)?.let { recordLoginStreakPlaytime(previous, it, false) }
        val uuid = playerId.toString()
        plugin.runDatabaseTask(
            "login-streak-progress-load",
            Runnable {
                val streakService = plugin.getLoginStreakService() ?: return@Runnable
                val streakPublisher = plugin.getLoginStreakPublisher()
                if (streakPublisher != null) {
                    seedLoginStreakCache(
                        { streakService.get(uuid) },
                        { snapshot ->
                            streakPublisher.publish(uuid, snapshot, streakService.getResetHourUtc())
                        },
                    )
                }
                val progress = streakService.getQualificationProgress(uuid) ?: return@Runnable
                if (!isCurrentStreakSession(session) || !isPlayerActive(playerId)) return@Runnable
                scheduleNextQualificationCheck(session, progress)
            },
        )
    }

    private fun finishLoginStreakSession(playerId: UUID) {
        val session = activeStreakSessions.remove(playerId) ?: return
        session.close(epochSeconds())?.let { recordLoginStreakPlaytime(session, it, false) }
    }

    private fun creditActiveLoginStreakSession(session: ActiveStreakSession) {
        if (!isCurrentStreakSession(session) || !isPlayerActive(session.playerId)) return
        val segment = session.takeSegment(epochSeconds())
        if (segment != null) {
            recordLoginStreakPlaytime(session, segment, true)
        } else {
            val retry =
                plugin
                    .getServer()
                    .scheduler
                    .buildTask(plugin, Runnable { creditActiveLoginStreakSession(session) })
                    .delay(Duration.ofSeconds(1))
                    .schedule()
            session.setTask(retry)
        }
    }

    private fun recordLoginStreakPlaytime(session: ActiveStreakSession, segment: CreditSegment, reschedule: Boolean) {
        val streakService = plugin.getLoginStreakService() ?: return
        val streakPublisher = plugin.getLoginStreakPublisher()
        val playerId = session.playerId
        val uuid = playerId.toString()
        plugin.runDatabaseTask(
            "login-streak-playtime",
            Runnable {
                val result = streakService.recordPlaytime(uuid, segment.from, segment.to) ?: return@Runnable
                val snapshot = result.streakSnapshot
                if (snapshot != null && streakPublisher != null)
                    streakPublisher.publish(uuid, snapshot, streakService.getResetHourUtc())
                val progress = result.progress
                if (reschedule && progress != null && isCurrentStreakSession(session) && isPlayerActive(playerId)) {
                    scheduleNextQualificationCheck(session, progress)
                }
            },
        )
    }

    private fun scheduleNextQualificationCheck(
        session: ActiveStreakSession,
        progress: LoginStreakService.QualificationProgress,
    ) {
        val streakService = plugin.getLoginStreakService() ?: return
        val now = epochSeconds()
        val delaySeconds =
            if (progress.qualified) {
                streakService.secondsUntilNextStreakDay(now) + streakService.getRequiredPlaySeconds()
            } else progress.remainingSeconds() - session.secondsSinceLastCredit(now)
        val builder =
            plugin.getServer().scheduler.buildTask(plugin, Runnable { creditActiveLoginStreakSession(session) })
        val task =
            if (delaySeconds <= 0L) builder.schedule() else builder.delay(Duration.ofSeconds(delaySeconds)).schedule()
        session.setTask(task)
    }

    private fun isCurrentStreakSession(session: ActiveStreakSession) =
        activeStreakSessions[session.playerId] === session

    private fun handleInitialJoin(player: Player, serverName: String, visible: Boolean) {
        if (isIgnored(serverName)) return
        if (plugin.getNicknameCache().isLoaded(player.uniqueId)) {
            recordJoin(player, visible && player.uniqueId !in silentJoinPlayers)
            return
        }
        val pending = plugin.getPendingJoinManager().register(player.uniqueId)
        ensureNicknameSeed(player)
        pending.orTimeout(2, TimeUnit.SECONDS).whenComplete { _, _ ->
            plugin
                .getServer()
                .scheduler
                .buildTask(
                    plugin,
                    Runnable {
                        recordJoin(player, visible && player.uniqueId !in silentJoinPlayers)
                    },
                )
                .schedule()
        }
    }

    private fun broadcastSwap(player: Player, previousServerName: String, currentServerName: String) {
        if (
            !player.isActive ||
                !plugin.getVanishManager().isVisible(player) ||
                isIgnored(currentServerName) ||
                isIgnored(previousServerName)
        )
            return
        val actualServer = player.currentServer.map { it.server.serverInfo.name }.orElse(null)
        if (currentServerName != actualServer) return
        broadcast(
            MINI_MESSAGE.deserialize(
                "<yellow><name> swapped to the <server> server</yellow>",
                Placeholder.component("name", getDisplayName(player)),
                Placeholder.unparsed("server", currentServerName),
            )
        )
        plugin
            .getDiscordWebhook()
            .send(formatDiscord(plugin.getConfig().getDiscordSwapFormat(), player, currentServerName))
    }

    private fun recordJoin(player: Player, announce: Boolean) {
        if (!player.isActive) return
        // Keep database work off-thread; a slow lookup must not stall the broadcast.
        val playerId = player.uniqueId
        val playerUuid = playerId.toString()
        val playerName = player.username
        plugin.runDatabaseTask(
            "join-broadcast",
            Runnable {
                val firstJoin = !plugin.getPgWriter()!!.hasJoinedBefore(playerUuid)
                if (!player.isActive) return@Runnable
                if (announce && plugin.getVanishManager().isVisible(player) && announcedPlayers.add(playerId)) {
                    val inGameFormat =
                        if (firstJoin) plugin.getConfig().getFirstJoinFormat()
                        else "<yellow><name> joined the game</yellow>"
                    broadcast(
                        MINI_MESSAGE.deserialize(
                            inGameFormat,
                            Placeholder.component("name", getDisplayName(player)),
                            Placeholder.unparsed("username", player.username),
                        )
                    )
                    val discordFormat =
                        if (firstJoin) plugin.getConfig().getDiscordFirstJoinFormat()
                        else plugin.getConfig().getDiscordJoinFormat()
                    plugin.getDiscordWebhook().send(formatDiscord(discordFormat, player, null))
                }
                // Capture first-join status before updating the player's own login information.
                val plain = plugin.getNicknameCache().getPlainNickname(playerId)
                val raw = plugin.getNicknameCache().getRawNickname(playerId)
                val reactivated = plugin.getPgWriter()!!.upsertPlayer(playerUuid, playerName, plain, raw)
                val awardDbWriter = plugin.getAwardDbWriter()
                if (reactivated && awardDbWriter != null) {
                    awardDbWriter.recomputeAllMedals()
                    plugin.getLogger().info("Restored leaderboard eligibility after login for {}", playerName)
                }
                plugin.getPgWriter()!!.upsertAltUsername(playerUuid, playerName)
                plugin.getPgWriter()!!.recordMcLogin(playerUuid)
            },
        )
    }

    private fun getDisplayName(player: Player): Component =
        plugin.getNicknameCache().getRawNickname(player.uniqueId)?.let(NicknameComponentParser::parse)
            ?: Component.text(player.username)

    private fun getPlainDisplayName(player: Player) =
        plugin.getNicknameCache().getPlainNickname(player.uniqueId) ?: player.username

    private fun formatDiscord(template: String, player: Player, serverName: String?): String {
        val result = template.replace("{name}", getPlainDisplayName(player)).replace("{username}", player.username)
        return if (serverName == null) result else result.replace("{server}", serverName)
    }

    private fun ensureNicknameSeed(player: Player) {
        val id = player.uniqueId
        if (plugin.getNicknameCache().isLoaded(id)) {
            plugin.getPendingJoinManager().complete(id)
            return
        }
        if (!nicknameSeeds.add(id)) return
        val queued =
            plugin.runDatabaseTask(
                "nickname-seed",
                Runnable {
                    try {
                        seedNickname(player)
                    } finally {
                        finishNicknameSeed(player)
                    }
                },
            )
        if (!queued) {
            nicknameSeeds.remove(id)
            plugin.getPendingJoinManager().complete(id)
        }
    }

    private fun finishNicknameSeed(seededPlayer: Player) {
        val id = seededPlayer.uniqueId
        nicknameSeeds.remove(id)
        val currentPlayer = plugin.getServer().getPlayer(id).filter(Player::isActive).orElse(null)
        if (currentPlayer != null && currentPlayer !== seededPlayer && !plugin.getNicknameCache().isLoaded(id)) {
            ensureNicknameSeed(currentPlayer)
            return
        }
        plugin.getPendingJoinManager().complete(id)
    }

    private fun seedNickname(player: Player) {
        if (!player.isActive) return
        val id = player.uniqueId
        val cache = plugin.getNicknameCache()
        val started = cache.beginLoad(id)
        try {
            if (started.loaded()) {
                publishNickname(id, started)
                return
            }
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
        } finally {
            if (!player.isActive && !started.loaded()) cache.discardIfUnloadedVersion(id, started.version())
        }
    }

    private fun publishNicknameToBackends(player: Player) {
        val id = player.uniqueId
        val snapshot = plugin.getNicknameCache().snapshot(id)
        if (snapshot.loaded()) publishNickname(id, snapshot)
    }

    private fun publishNickname(uuid: UUID, snapshot: NicknameCache.Snapshot) {
        plugin.getNicknameListener()?.publishNickname(uuid, snapshot.rawNickname(), snapshot.version())
    }

    private fun isIgnored(serverName: String) =
        plugin.getConfig().getIgnoredServers().contains(serverName.lowercase(Locale.getDefault()))

    private fun isPlayerActive(playerId: UUID) =
        plugin.getServer().getPlayer(playerId).map(Player::isActive).orElse(false)

    private fun broadcast(message: Component) {
        plugin.getServer().allPlayers.forEach { it.sendMessage(message) }
    }

    private class ActiveStreakSession(val playerId: UUID, startedAt: Long) {
        private var lastCreditedAt = startedAt
        private var closed = false
        private var qualificationTask: ScheduledTask? = null

        @Synchronized
        fun takeSegment(now: Long): CreditSegment? {
            if (closed || now <= lastCreditedAt) return null
            val from = lastCreditedAt
            lastCreditedAt = now
            return CreditSegment(from, now)
        }

        @Synchronized
        fun close(now: Long): CreditSegment? {
            if (closed) return null
            closed = true
            qualificationTask?.cancel()
            qualificationTask = null
            if (now <= lastCreditedAt) return null
            val from = lastCreditedAt
            lastCreditedAt = now
            return CreditSegment(from, now)
        }

        @Synchronized
        fun setTask(task: ScheduledTask) {
            if (closed) {
                task.cancel()
                return
            }
            qualificationTask?.cancel()
            qualificationTask = task
        }

        @Synchronized fun secondsSinceLastCredit(now: Long) = maxOf(0L, now - lastCreditedAt)
    }

    private class CreditSegment(val from: Long, val to: Long)

    companion object {
        private val MINI_MESSAGE = MiniMessage.miniMessage()
        private const val ALT_GROUP = "alt"
        private const val MAX_JADE_HANDSHAKE_SIZE = 256
        private val JADE_CLIENT_HANDSHAKE = MinecraftChannelIdentifier.from("jade:client_handshake")
        // The backend needs the pre-translation protocol for Jade's embedded registry IDs.
        private val CLIENT_PROTOCOL = MinecraftChannelIdentifier.from("crabcraft:client_protocol")

        @JvmStatic
        fun encodeClientProtocol(protocol: Int): ByteArray =
            ByteBuffer.allocate(Int.SIZE_BYTES).putInt(protocol).array()

        @JvmStatic
        fun seedLoginStreakCache(
            load: Supplier<LoginStreakService.StreakSnapshot?>,
            publish: Consumer<LoginStreakService.StreakSnapshot>,
        ) {
            load.get()?.let(publish::accept)
        }

        @JvmStatic
        fun commitNicknameLoad(
            cache: NicknameCache,
            id: UUID,
            expectedVersion: Long,
            result: PostgresStatsWriter.NicknameLoadResult,
        ): Boolean =
            when (result.status()) {
                PostgresStatsWriter.NicknameLoadStatus.FOUND ->
                    cache.commitIfVersion(id, expectedVersion, result.rawNickname())
                PostgresStatsWriter.NicknameLoadStatus.ABSENT -> cache.commitIfVersion(id, expectedVersion, "")
                PostgresStatsWriter.NicknameLoadStatus.FAILED -> false
            }

        @JvmStatic
        fun isSilentJoinHost(host: String?, silentHosts: List<String>?): Boolean {
            if (host == null || silentHosts == null) return false
            var normalised = host.trim { Character.isWhitespace(it) }.lowercase(Locale.ROOT)
            while (normalised.endsWith('.')) normalised = normalised.dropLast(1)
            return normalised in silentHosts
        }

        private fun epochSeconds() = System.currentTimeMillis() / 1000L
    }
}
