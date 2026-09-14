package crabcraft.net.crabUtilities.xaero

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.util.Arrays
import java.util.UUID

/** Supplies a stable backend identity to Xaero map clients. */
class XaeroIntegration(private val plugin: JavaPlugin, serverId: Int) : Listener {
    private val serverIdPayload = encodeServerId(serverId)
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val joinedPlayer = event.getPlayer()
        val playerId = joinedPlayer.getUniqueId()
        val expectedWorldId = joinedPlayer.getWorld().getUID()
        for (delay in JOIN_SEND_DELAYS_TICKS) {
            if (delay == 0L) { sendWorldInfo(joinedPlayer); continue }
            plugin.getServer().getScheduler().runTaskLater(plugin, Runnable { retryJoinSend(playerId, joinedPlayer, expectedWorldId) }, delay)
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (isXaeroChannel(event.getChannel())) sendOnChannel(event.getPlayer(), event.getChannel())
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) { sendWorldInfo(event.getPlayer()) }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerPostRespawn(event: PlayerPostRespawnEvent) { sendWorldInfo(event.getPlayer()) }
    private fun retryJoinSend(playerId: UUID, joinedPlayer: Player, expectedWorldId: UUID) {
        val currentPlayer = plugin.getServer().getPlayer(playerId)
        if (!canRetryJoinSend(currentPlayer, joinedPlayer, expectedWorldId)) return
        sendWorldInfo(currentPlayer!!)
    }
    private fun sendWorldInfo(player: Player) {
        for (channel in CHANNELS) if (player.getListeningPluginChannels().contains(channel)) sendOnChannel(player, channel)
    }
    private fun sendOnChannel(player: Player, channel: String) { player.sendPluginMessage(plugin, channel, serverIdPayload.copyOf()) }
    companion object {
        const val MINIMAP_CHANNEL = "xaerominimap:main"
        const val WORLDMAP_CHANNEL = "xaeroworldmap:main"
        @JvmField val CHANNELS = listOf(MINIMAP_CHANNEL, WORLDMAP_CHANNEL)
        @JvmField val JOIN_SEND_DELAYS_TICKS = listOf(0L, 20L, 40L)
        @JvmStatic
        fun canRetryJoinSend(currentPlayer: Player?, joinedPlayer: Player, expectedWorldId: UUID): Boolean =
            currentPlayer === joinedPlayer && currentPlayer!!.isOnline && currentPlayer.getWorld().getUID() == expectedWorldId
        @JvmStatic fun isXaeroChannel(channel: String): Boolean = CHANNELS.contains(channel)
        @JvmStatic fun encodeServerId(serverId: Int): ByteArray =
            byteArrayOf(0, (serverId ushr 24).toByte(), (serverId ushr 16).toByte(), (serverId ushr 8).toByte(), serverId.toByte())
    }
}
