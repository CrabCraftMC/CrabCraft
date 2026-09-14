package crabcraft.net.crabUtilities.media

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect
import crabcraft.net.crabUtilities.media.audio.AudioEngine
import org.bukkit.entity.Player

/** Hides vanilla jukebox packets for blocks whose audio uses Simple Voice Chat. */
class JukeboxPacketFilter private constructor() : PacketListener {
  override fun onPacketSend(event: PacketSendEvent) {
    val blockPosition = affectedJukebox(event) ?: return
    val recipient = event.getPlayer<Player>()
    val block = recipient.world.getBlockAt(blockPosition.x, blockPosition.y, blockPosition.z)
    if (AudioEngine.getInstance().isPlaying(block)) event.isCancelled = true
  }

  companion object {
    @JvmStatic fun install(): AutoCloseable {
      val manager = PacketEvents.getAPI().eventManager
      val registration = manager.registerListener(JukeboxPacketFilter(), PacketListenerPriority.HIGHEST)
      return AutoCloseable { manager.unregisterListener(registration) }
    }

    private fun affectedJukebox(event: PacketSendEvent): Vector3i? {
      if (event.packetType == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
        return WrapperPlayServerBlockEntityData(event).position
      }
      if (event.packetType != PacketType.Play.Server.EFFECT) return null
      val effect = WrapperPlayServerEffect(event)
      return if (effect.type == 1010) effect.position else null
    }
  }
}
