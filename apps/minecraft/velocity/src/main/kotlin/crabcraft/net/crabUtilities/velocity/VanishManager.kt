package crabcraft.net.crabUtilities.velocity

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Fail-closed mirror of the authoritative EssentialsX state on each backend. A player is public only after their
 * current server explicitly reports them visible.
 */
class VanishManager(private val plugin: CrabUtilitiesVelocity) {
    private val states = ConcurrentHashMap<UUID, Snapshot>()
    private val pendingSessions = ConcurrentHashMap<UUID, PendingSession>()

    fun start() {
        plugin.getServer().channelRegistrar.register(CHANNEL)
        plugin.getServer().eventManager.register(plugin, this)
    }

    fun shutdown() {
        plugin.getServer().eventManager.unregisterListener(plugin, this)
        plugin.getServer().channelRegistrar.unregister(CHANNEL)
        states.clear()
        pendingSessions.clear()
    }

    /** Invokes completion once this exact backend supplies the player's visibility state. */
    fun beginSession(player: Player, server: RegisteredServer, completion: Consumer<Boolean>) {
        val serverName = server.serverInfo.name
        val pending = PendingSession(serverName, completion)
        pendingSessions[player.uniqueId] = pending
        val current = states[player.uniqueId]
        if (current != null && current.serverName == serverName && pendingSessions.remove(player.uniqueId, pending)) {
            completion.accept(!current.vanished)
            return
        }
        // Keep public visibility fail-closed without blocking private join processing.
        plugin
            .getServer()
            .scheduler
            .buildTask(
                plugin,
                Runnable {
                    if (pendingSessions.remove(player.uniqueId, pending)) completion.accept(false)
                },
            )
            .delay(SESSION_TIMEOUT)
            .schedule()
    }

    fun isVisible(player: Player): Boolean {
        val connection = player.currentServer.orElse(null) ?: return false
        val state = states[player.uniqueId] ?: return false
        return isPubliclyVisible(state.serverName, state.vanished, connection.server.serverInfo.name)
    }

    fun visiblePlayers(): List<Player> = plugin.getServer().allPlayers.filter(::isVisible)

    fun visiblePlayerCount(server: RegisteredServer): Int = server.playersConnected.count(::isVisible)

    /** Applies EssentialsX vanish on the player's current backend. */
    fun applyVanish(player: Player, server: RegisteredServer) {
        val serverName = server.serverInfo.name
        val connection = player.currentServer.filter { it.server == server }.orElse(null) ?: return
        // Hide proxy-facing surfaces before waiting for Paper to acknowledge.
        states[player.uniqueId] = Snapshot(serverName, true)
        sendVanishRequest(player, connection)
        plugin
            .getServer()
            .scheduler
            .buildTask(
                plugin,
                Runnable {
                    player.currentServer.filter { it.server == server }.ifPresent { sendVanishRequest(player, it) }
                },
            )
            .delay(Duration.ofSeconds(1))
            .schedule()
    }

    private fun sendVanishRequest(player: Player, connection: ServerConnection) {
        if (!connection.sendPluginMessage(CHANNEL, VanishBridgeProtocol.status(true))) {
            plugin
                .getLogger()
                .warn(
                    "Could not request automatic vanish for {} on {}",
                    player.username,
                    connection.server.serverInfo.name,
                )
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    fun onPluginMessage(event: PluginMessageEvent) {
        if (CHANNEL != event.identifier) return
        event.result = PluginMessageEvent.ForwardResult.handled()
        val source = event.source
        val player = event.target
        if (source !is ServerConnection || player !is Player || player.currentServer.filter { it == source }.isEmpty) {
            plugin.getLogger().warn("Ignored vanish status without a matching backend player")
            return
        }
        val vanished =
            try {
                VanishBridgeProtocol.decode(event.data)
            } catch (error: IllegalArgumentException) {
                plugin.getLogger().warn("Ignored malformed vanish status: {}", error.message)
                return
            }
        val serverName = source.server.serverInfo.name
        states[player.uniqueId] = Snapshot(serverName, vanished)
        val pending = pendingSessions[player.uniqueId]
        if (pending != null && pending.serverName == serverName && pendingSessions.remove(player.uniqueId, pending)) {
            plugin.getServer().scheduler.buildTask(plugin, Runnable { pending.completion.accept(!vanished) }).schedule()
        }
    }

    @Subscribe(order = PostOrder.LAST)
    fun onDisconnect(event: DisconnectEvent) {
        states.remove(event.player.uniqueId)
        pendingSessions.remove(event.player.uniqueId)
    }

    @Subscribe
    fun onProxyPing(event: ProxyPingEvent) {
        val ping = event.ping
        val builder = ping.asBuilder().onlinePlayers(visiblePlayers().size)
        val sample = ping.players.map { it.sample }.orElse(emptyList())
        val filtered = sample.filter { entry -> plugin.getServer().getPlayer(entry.id).map(::isVisible).orElse(true) }
        builder.samplePlayers(filtered)
        event.ping = builder.build()
    }

    private data class Snapshot(val serverName: String, val vanished: Boolean)

    private data class PendingSession(val serverName: String, val completion: Consumer<Boolean>)

    companion object {
        private val CHANNEL = MinecraftChannelIdentifier.from(VanishBridgeProtocol.CHANNEL)
        private val SESSION_TIMEOUT = Duration.ofSeconds(2)

        @JvmStatic
        fun isPubliclyVisible(reportedServer: String?, vanished: Boolean, currentServer: String?): Boolean =
            reportedServer != null && currentServer != null && !vanished && reportedServer == currentServer
    }
}
