package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.Player
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Sends SVC player-state wire packets so remote members appear in the local group GUI. */
open class SvcPacketSender(private val plugin: CrabUtilities?) {
    open fun sendState(recipient: Player, playerUuid: UUID, playerName: String?, groupId: UUID?) {
        val bytes = encodeState(playerUuid, playerName, groupId) ?: return
        try { recipient.sendPluginMessage(plugin!!, STATE_CHANNEL, bytes) }
        catch (e: Exception) { plugin!!.getLogger().fine("Failed to send PlayerStatePacket to " + recipient.getName() + ": " + e.message) }
    }

    open fun sendRemove(recipient: Player, playerUuid: UUID) {
        val bytes = encodeRemove(playerUuid)
        try { recipient.sendPluginMessage(plugin!!, REMOVE_STATE_CHANNEL, bytes) }
        catch (e: Exception) { plugin!!.getLogger().fine("Failed to send RemovePlayerStatePacket to " + recipient.getName() + ": " + e.message) }
    }

    companion object {
        const val STATE_CHANNEL = "voicechat:state"
        const val REMOVE_STATE_CHANNEL = "voicechat:remove_state"
        @JvmStatic private fun encodeState(playerUuid: UUID, playerName: String?, groupId: UUID?): ByteArray? {
            try {
                val out = ByteArrayOutputStream()
                val dout = DataOutputStream(out)
                dout.writeBoolean(false) // disabled — not propagated cross-server
                dout.writeBoolean(false) // disconnected — not propagated cross-server
                dout.writeLong(playerUuid.mostSignificantBits)
                dout.writeLong(playerUuid.leastSignificantBits)
                val nameBytes = (playerName ?: "").toByteArray(StandardCharsets.UTF_8)
                VarInt.write(out, nameBytes.size)
                out.write(nameBytes)
                dout.writeBoolean(groupId != null)
                if (groupId != null) {
                    dout.writeLong(groupId.mostSignificantBits)
                    dout.writeLong(groupId.leastSignificantBits)
                }
                return out.toByteArray()
            } catch (e: IOException) { return null }
        }
        @JvmStatic private fun encodeRemove(playerUuid: UUID): ByteArray {
            val out = ByteArrayOutputStream(16)
            try {
                val dout = DataOutputStream(out)
                dout.writeLong(playerUuid.mostSignificantBits)
                dout.writeLong(playerUuid.leastSignificantBits)
            } catch (_: IOException) {
                // ByteArrayOutputStream never throws.
            }
            return out.toByteArray()
        }
    }
}
