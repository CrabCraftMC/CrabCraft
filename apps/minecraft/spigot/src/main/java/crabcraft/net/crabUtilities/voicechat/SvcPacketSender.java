package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sends fake SVC {@code PlayerStatePacket} and {@code RemovePlayerStatePacket}
 * messages to local clients, making cross-server group members appear in
 * the SVC group GUI.
 *
 * <p>We don't have access to SVC's internal {@code PlayerState} class so
 * we replicate its wire format manually (verified against
 * {@code common/.../PlayerState.java:80-104}):
 * <pre>
 *   boolean disabled
 *   boolean disconnected
 *   UUID    uuid           (16 bytes, big-endian high then low)
 *   String  name           (VarInt length + UTF-8 bytes)
 *   boolean hasGroup
 *   [if hasGroup] UUID group
 * </pre>
 *
 * <p>Channels: {@code voicechat:state} for state updates, {@code
 * voicechat:remove_state} for removals. Bukkit requires the plugin to
 * register these as outgoing channels before {@code sendPluginMessage}
 * works — done in {@link CrabUtilities#onEnable()}.
 */
class SvcPacketSender {

    static final String STATE_CHANNEL = "voicechat:state";
    static final String REMOVE_STATE_CHANNEL = "voicechat:remove_state";

    private final CrabUtilities plugin;

    SvcPacketSender(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    /** Send a PlayerStatePacket to a single recipient. */
    void sendState(Player recipient, UUID playerUuid, String playerName, UUID groupId) {
        byte[] bytes = encodeState(playerUuid, playerName, groupId);
        if (bytes == null) return;
        try {
            recipient.sendPluginMessage(plugin, STATE_CHANNEL, bytes);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to send PlayerStatePacket to "
                    + recipient.getName() + ": " + e.getMessage());
        }
    }

    /** Send a RemovePlayerStatePacket to a single recipient. */
    void sendRemove(Player recipient, UUID playerUuid) {
        byte[] bytes = encodeRemove(playerUuid);
        try {
            recipient.sendPluginMessage(plugin, REMOVE_STATE_CHANNEL, bytes);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to send RemovePlayerStatePacket to "
                    + recipient.getName() + ": " + e.getMessage());
        }
    }

    private static byte[] encodeState(UUID playerUuid, String playerName, UUID groupId) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(out);
            dout.writeBoolean(false);  // disabled — not propagated cross-server
            dout.writeBoolean(false);  // disconnected — not propagated cross-server
            dout.writeLong(playerUuid.getMostSignificantBits());
            dout.writeLong(playerUuid.getLeastSignificantBits());

            byte[] nameBytes = (playerName == null ? "" : playerName)
                    .getBytes(StandardCharsets.UTF_8);
            VarInt.write(out, nameBytes.length);
            out.write(nameBytes);

            dout.writeBoolean(groupId != null);
            if (groupId != null) {
                dout.writeLong(groupId.getMostSignificantBits());
                dout.writeLong(groupId.getLeastSignificantBits());
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] encodeRemove(UUID playerUuid) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        try {
            DataOutputStream dout = new DataOutputStream(out);
            dout.writeLong(playerUuid.getMostSignificantBits());
            dout.writeLong(playerUuid.getLeastSignificantBits());
        } catch (IOException ignored) {
            // ByteArrayOutputStream never throws
        }
        return out.toByteArray();
    }
}
