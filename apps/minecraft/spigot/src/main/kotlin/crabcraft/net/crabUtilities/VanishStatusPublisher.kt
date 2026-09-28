package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.messaging.PluginMessageListener

/** Reports the authoritative EssentialsX state of each backend player to Velocity. */
class VanishStatusPublisher(private val plugin: CrabUtilities) : Listener, PluginMessageListener {
    fun start() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL, this)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        for (player in Bukkit.getOnlinePlayers()) publishLater(player)
    }

    fun shutdown() {
        HandlerList.unregisterAll(this)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL)
    }

    @EventHandler(priority = EventPriority.MONITOR) fun onJoin(event: PlayerJoinEvent) = publishLater(event.player)

    fun publish(player: Player) = publish(player, plugin.isVanished(player))

    fun publish(player: Player, vanished: Boolean) {
        if (!player.isOnline) return
        player.sendPluginMessage(plugin, VanishBridgeProtocol.CHANNEL, VanishBridgeProtocol.status(vanished))
    }

    override fun onPluginMessageReceived(channel: String, carrier: Player, message: ByteArray) {
        if (channel != VanishBridgeProtocol.CHANNEL) return
        val vanished =
            try {
                VanishBridgeProtocol.decode(message)
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("Ignored malformed proxy vanish request: ${e.message}")
                return
            }
        if (!VanishStatus.setVanished(plugin.getEssentials(), carrier, vanished)) {
            plugin.logger.warning("Could not apply proxy-requested vanish state for ${carrier.name}")
            return
        }
        plugin.onVanishStatusChanged(carrier)
        publish(carrier)
    }

    private fun publishLater(player: Player) {
        // Let EssentialsX restore persisted state, then retry after channels settle.
        Bukkit.getScheduler().runTask(plugin, Runnable { publish(player) })
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { publish(player) }, 20L)
    }
}
