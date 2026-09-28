package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.bukkit.entity.Player

/**
 * Sends SVC PlayerStatePacket and RemovePlayerStatePacket messages to local clients, making cross-server group members
 * appear in the SVC group GUI.
 *
 * The state wire format is: disabled boolean, disconnected boolean, UUID (big-endian high then low), name (VarInt byte
 * length + UTF-8), hasGroup boolean, optional group UUID. CrabUtilities registers both outgoing plugin channels during
 * enable.
 */
open class SvcPacketSender(private val plugin: CrabUtilities?) {
    /** Send a PlayerStatePacket to a single recipient. */
    open fun sendState(recipient: Player, playerUuid: UUID, playerName: String?, groupId: UUID?) {
        val bytes = encodeState(playerUuid, playerName, groupId) ?: return
        try {
            recipient.sendPluginMessage(plugin!!, STATE_CHANNEL, bytes)
        } catch (e: Exception) {
            plugin!!.getLogger().fine("Failed to send PlayerStatePacket to ${recipient.getName()}: ${e.message}")
        }
    }

    /** Send a RemovePlayerStatePacket to a single recipient. */
    open fun sendRemove(recipient: Player, playerUuid: UUID) {
        val bytes = encodeRemove(playerUuid)
        try {
            recipient.sendPluginMessage(plugin!!, REMOVE_STATE_CHANNEL, bytes)
        } catch (e: Exception) {
            plugin!!.getLogger().fine("Failed to send RemovePlayerStatePacket to ${recipient.getName()}: ${e.message}")
        }
    }

    companion object {
        const val STATE_CHANNEL = "voicechat:state"
        const val REMOVE_STATE_CHANNEL = "voicechat:remove_state"

        private fun encodeState(playerUuid: UUID, playerName: String?, groupId: UUID?): ByteArray? {
            return try {
                val out = ByteArrayOutputStream()
                val data = DataOutputStream(out)
                data.writeBoolean(false) // disabled — not propagated cross-server
                data.writeBoolean(false) // disconnected — not propagated cross-server
                data.writeLong(playerUuid.mostSignificantBits)
                data.writeLong(playerUuid.leastSignificantBits)
                val nameBytes = (playerName ?: "").toByteArray(StandardCharsets.UTF_8)
                VarInt.write(out, nameBytes.size)
                out.write(nameBytes)
                data.writeBoolean(groupId != null)
                if (groupId != null) {
                    data.writeLong(groupId.mostSignificantBits)
                    data.writeLong(groupId.leastSignificantBits)
                }
                out.toByteArray()
            } catch (_: IOException) {
                null
            }
        }

        private fun encodeRemove(playerUuid: UUID): ByteArray {
            val out = ByteArrayOutputStream(16)
            try {
                val data = DataOutputStream(out)
                data.writeLong(playerUuid.mostSignificantBits)
                data.writeLong(playerUuid.leastSignificantBits)
            } catch (_: IOException) {
                // ByteArrayOutputStream never throws.
            }
            return out.toByteArray()
        }
    }
}
