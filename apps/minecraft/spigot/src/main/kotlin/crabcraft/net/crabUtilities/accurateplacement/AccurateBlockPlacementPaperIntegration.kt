package crabcraft.net.crabUtilities.accurateplacement

import java.lang.reflect.Field
import net.minecraft.server.network.ServerGamePacketListenerImpl
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

object AccurateBlockPlacementPaperIntegration {
    private val ACK_SEQUENCE = findAckSequence()

    @JvmStatic fun verify() = Unit

    @JvmStatic
    fun currentSequence(player: Player): Int =
        try {
            ACK_SEQUENCE.getInt((player as CraftPlayer).handle.connection)
        } catch (exception: IllegalAccessException) {
            throw IllegalStateException("Could not read Paper's block-change sequence", exception)
        }

    private fun findAckSequence(): Field =
        try {
            ServerGamePacketListenerImpl::class.java.getDeclaredField("ackBlockChangesUpTo").apply {
                isAccessible = true
            }
        } catch (exception: ReflectiveOperationException) {
            throw IllegalStateException("Paper's block-change sequence is unavailable", exception)
        } catch (exception: RuntimeException) {
            throw IllegalStateException("Paper's block-change sequence is unavailable", exception)
        }
}
