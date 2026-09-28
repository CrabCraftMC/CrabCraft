package crabcraft.net.crabUtilities.xaero

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin

class XaeroIntegration(private val plugin: JavaPlugin, serverId: Int) : Listener {
    private val serverIdPayload = encodeServerId(serverId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val joinedPlayer = event.player
        val playerId = joinedPlayer.uniqueId
        val expectedWorldId = joinedPlayer.world.uid
        for (delay in JOIN_SEND_DELAYS_TICKS) {
            if (delay == 0L) sendWorldInfo(joinedPlayer)
            else
                plugin.server.scheduler.runTaskLater(
                    plugin,
                    Runnable { retryJoinSend(playerId, joinedPlayer, expectedWorldId) },
                    delay,
                )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (isXaeroChannel(event.channel)) sendOnChannel(event.player, event.channel)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        sendWorldInfo(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerPostRespawn(event: PlayerPostRespawnEvent) {
        sendWorldInfo(event.player)
    }

    private fun retryJoinSend(playerId: UUID, joinedPlayer: Player, expectedWorldId: UUID) {
        val currentPlayer = plugin.server.getPlayer(playerId)
        if (canRetryJoinSend(currentPlayer, joinedPlayer, expectedWorldId)) sendWorldInfo(currentPlayer!!)
    }

    private fun sendWorldInfo(player: Player) {
        for (channel in CHANNELS) if (player.listeningPluginChannels.contains(channel)) sendOnChannel(player, channel)
    }

    private fun sendOnChannel(player: Player, channel: String) {
        player.sendPluginMessage(plugin, channel, serverIdPayload.copyOf())
    }

    companion object {
        const val MINIMAP_CHANNEL = "xaerominimap:main"
        const val WORLDMAP_CHANNEL = "xaeroworldmap:main"
        @JvmField val CHANNELS = listOf(MINIMAP_CHANNEL, WORLDMAP_CHANNEL)
        @JvmField val JOIN_SEND_DELAYS_TICKS = listOf(0L, 20L, 40L)

        @JvmStatic
        fun canRetryJoinSend(currentPlayer: Player?, joinedPlayer: Player, expectedWorldId: UUID) =
            currentPlayer === joinedPlayer && currentPlayer!!.isOnline && currentPlayer.world.uid == expectedWorldId

        @JvmStatic fun isXaeroChannel(channel: String) = CHANNELS.contains(channel)

        @JvmStatic
        fun encodeServerId(serverId: Int) =
            byteArrayOf(
                0,
                (serverId ushr 24).toByte(),
                (serverId ushr 16).toByte(),
                (serverId ushr 8).toByte(),
                serverId.toByte(),
            )
    }
}
