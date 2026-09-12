package crabcraft.net.crabUtilities.accurateplacement

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement
import org.bukkit.entity.Player

/** Loads PacketEvents types only after the soft dependency has been confirmed. */
object AccurateBlockPlacementPacketEventsIntegration {
    @JvmStatic
    fun register(manager: AccurateBlockPlacementManager): AutoCloseable {
        val eventManager = PacketEvents.getAPI().eventManager
        val listener = eventManager.registerListener(object : PacketListener {
            override fun onPacketReceive(event: PacketReceiveEvent) {
                if (event.packetType != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) return
                val player = event.getPlayer<Any>() as? Player ?: return
                val packet = WrapperPlayClientPlayerBlockPlacement(event)
                val position = packet.blockPosition
                val cursor = packet.cursorPosition
                if (!manager.capture(player, position.x, position.y, position.z, cursor.x, packet.sequence)) return
                packet.cursorPosition = cursor.withX(0.5f)
                event.markForReEncode(true)
            }
        }, PacketListenerPriority.LOWEST)
        return AutoCloseable { eventManager.unregisterListener(listener) }
    }
}
