package crabcraft.net.crabUtilities.accurateplacement

import net.minecraft.server.network.ServerGamePacketListenerImpl
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.lang.reflect.Field

object AccurateBlockPlacementPaperIntegration {
    private val ACK_SEQUENCE = findAckSequence()

    @JvmStatic fun verify() {}

    @JvmStatic
    fun currentSequence(player: Player): Int {
        try {
            return ACK_SEQUENCE.getInt((player as CraftPlayer).handle.connection)
        } catch (exception: IllegalAccessException) {
            throw IllegalStateException("Could not read Paper's block-change sequence", exception)
        }
    }

    private fun findAckSequence(): Field {
        try {
            val field = ServerGamePacketListenerImpl::class.java.getDeclaredField("ackBlockChangesUpTo")
            field.isAccessible = true
            return field
        } catch (exception: ReflectiveOperationException) {
            throw IllegalStateException("Paper's block-change sequence is unavailable", exception)
        } catch (exception: RuntimeException) {
            throw IllegalStateException("Paper's block-change sequence is unavailable", exception)
        }
    }
}
