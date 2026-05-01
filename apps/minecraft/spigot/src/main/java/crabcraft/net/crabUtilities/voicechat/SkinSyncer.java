package crabcraft.net.crabUtilities.voicechat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Wraps PacketEvents to inject {@code ClientboundPlayerInfoUpdatePacket}
 * for cross-server group members so SVC's GUI can render their real
 * skin via {@code GameProfileUtils.getSkin()}.
 *
 * <p>Each entry is sent with {@code listed=false} (UPDATE_LISTED action)
 * so the player goes into the receiving client's {@code playerInfoMap}
 * but does NOT appear in the vanilla tab list. SVC reads from
 * {@code playerInfoMap} directly so it sees the head; vanilla tab list
 * iterates only listed entries so it doesn't.
 *
 * <p>PacketEvents is a soft-depend. If it's not installed at runtime
 * we silently degrade to default-skin heads (the SVC client falls back
 * to {@code DefaultPlayerSkin.get(uuid)} when no PlayerInfo is in the
 * map). Construction is gated by {@link #isAvailable()}.
 */
class SkinSyncer {

    private final CrabUtilities plugin;
    private final boolean available;

    SkinSyncer(CrabUtilities plugin) {
        this.plugin = plugin;
        this.available = plugin.getServer().getPluginManager().getPlugin("packetevents") != null;
        if (!available) {
            plugin.getLogger().info("PacketEvents not installed — cross-server group members "
                    + "will appear with default skins (install PacketEvents for real skins)");
        }
    }

    boolean isAvailable() {
        return available;
    }

    void addRemotePlayer(Player recipient, ProfileCodec.Snapshot snapshot) {
        if (!available || snapshot == null) return;
        try {
            UserProfile profile = new UserProfile(snapshot.uuid(), snapshot.name());
            if (!snapshot.texturesValue().isEmpty()) {
                profile.getTextureProperties().add(new TextureProperty(
                        "textures",
                        snapshot.texturesValue(),
                        snapshot.texturesSignature().isEmpty() ? null : snapshot.texturesSignature()));
            }

            WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
                    new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                            profile,
                            /* listed */ false,
                            /* latency */ 0,
                            GameMode.SURVIVAL,
                            /* displayName */ null,
                            /* chatSession */ null);

            WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(
                            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                    List.of(info));

            PacketEvents.getAPI().getPlayerManager().sendPacket(recipient, packet);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE,
                    "Failed to send PlayerInfoUpdate for " + snapshot.uuid()
                            + " to " + recipient.getName(), t);
        }
    }

    void removeRemotePlayer(Player recipient, UUID playerUuid) {
        if (!available) return;
        try {
            WrapperPlayServerPlayerInfoRemove packet =
                    new WrapperPlayServerPlayerInfoRemove(Collections.singletonList(playerUuid));
            PacketEvents.getAPI().getPlayerManager().sendPacket(recipient, packet);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE,
                    "Failed to send PlayerInfoRemove for " + playerUuid
                            + " to " + recipient.getName(), t);
        }
    }
}
