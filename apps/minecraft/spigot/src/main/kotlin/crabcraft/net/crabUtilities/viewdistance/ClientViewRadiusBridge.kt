package crabcraft.net.crabUtilities.viewdistance

import crabcraft.net.crabUtilities.CrabUtilities
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Keeps the advertised render radius stable while Paper changes actual load and send radii. */
class ClientViewRadiusBridge(private val plugin: CrabUtilities, private val advertisedRadius: Int) : Listener {
    private val handlers = ConcurrentHashMap<UUID, RadiusPacketHandler>()
    @Volatile private var running = false

    fun start() {
        check(!running) { "Client view radius bridge is already running" }
        running = true
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach(::attachAndAdvertise)
    }

    fun shutdown(restoreActualRadius: Boolean) {
        if (!running) return
        running = false
        HandlerList.unregisterAll(this)
        Bukkit.getOnlinePlayers().forEach { detach(it, restoreActualRadius) }
        handlers.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        attachAndAdvertise(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        attachAndAdvertise(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        detach(event.player, false)
    }

    private fun attachAndAdvertise(player: Player) {
        if (!running) return
        val serverPlayer = (player as CraftPlayer).handle
        val channel = serverPlayer.connection?.connection?.channel ?: return
        val playerId = player.uniqueId
        val handler = handlers.computeIfAbsent(playerId) { RadiusPacketHandler(advertisedRadius) }
        runOnChannel(
            channel,
            Runnable {
                if (!running || handlers[playerId] !== handler || !channel.isActive) return@Runnable
                val pipeline = channel.pipeline()
                val existing = pipeline.get(HANDLER_NAME)
                if (existing !== handler) {
                    if (existing != null) pipeline.remove(HANDLER_NAME)
                    pipeline.addLast(HANDLER_NAME, handler)
                }
                serverPlayer.connection.send(ClientboundSetChunkCacheRadiusPacket(advertisedRadius))
            },
        )
    }

    private fun detach(player: Player, restoreActualRadius: Boolean) {
        val handler = handlers.remove(player.uniqueId) ?: return
        val serverPlayer = (player as CraftPlayer).handle
        val channel = serverPlayer.connection?.connection?.channel ?: return
        val actualRadius = if (restoreActualRadius) player.sendViewDistance else -1
        runOnChannel(
            channel,
            Runnable {
                val pipeline = channel.pipeline()
                if (pipeline.get(HANDLER_NAME) === handler) pipeline.remove(HANDLER_NAME)
                if (restoreActualRadius && channel.isActive) {
                    serverPlayer.connection.send(ClientboundSetChunkCacheRadiusPacket(actualRadius))
                }
            },
        )
    }

    private fun runOnChannel(channel: Channel, action: Runnable) {
        if (channel.eventLoop().inEventLoop()) action.run() else channel.eventLoop().execute(action)
    }

    private class RadiusPacketHandler(private val advertisedRadius: Int) : ChannelDuplexHandler() {
        override fun write(context: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            super.write(context, keepAdvertisedRadius(message, advertisedRadius), promise)
        }
    }

    companion object {
        private const val HANDLER_NAME = "crabutilities_view_radius"

        @JvmStatic
        fun keepAdvertisedRadius(message: Any?, advertisedRadius: Int): Any? =
            if (message is ClientboundSetChunkCacheRadiusPacket && message.radius != advertisedRadius)
                ClientboundSetChunkCacheRadiusPacket(advertisedRadius)
            else message
    }
}
