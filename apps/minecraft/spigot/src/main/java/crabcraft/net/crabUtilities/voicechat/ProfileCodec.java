package crabcraft.net.crabUtilities.voicechat;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Tiny codec for ferrying a player's {@link PlayerProfile} between
 * backends so the receiving side can reconstruct the same skin via
 * {@code ClientboundPlayerInfoUpdatePacket}.
 *
 * <p>We only carry the {@code textures} property (name + base64 value
 * + base64 signature) because that's all SVC needs to render the head.
 * Encoded as four NUL-separated fields:
 * {@code uuid<NUL>name<NUL>texturesValue<NUL>texturesSignature}, with
 * empty texturesValue/signature for offline-mode players.
 */
final class ProfileCodec {

    private static final String SEP = "\0";

    private ProfileCodec() {}

    /** Captures a snapshot of the player's profile for transport. */
    static Snapshot capture(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        ProfileProperty textures = null;
        for (ProfileProperty prop : profile.getProperties()) {
            if ("textures".equals(prop.getName())) {
                textures = prop;
                break;
            }
        }
        String value = textures == null ? "" : textures.getValue();
        String signature = textures == null || textures.getSignature() == null
                ? "" : textures.getSignature();
        return new Snapshot(profile.getId(), profile.getName(), value, signature);
    }

    static String encode(Snapshot s) {
        return String.join(SEP,
                s.uuid().toString(),
                s.name() == null ? "" : s.name(),
                s.texturesValue(),
                s.texturesSignature());
    }

    @Nullable
    static Snapshot decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        String[] parts = encoded.split(SEP, -1);
        if (parts.length < 4) return null;
        try {
            UUID uuid = UUID.fromString(parts[0]);
            return new Snapshot(uuid, parts[1], parts[2], parts[3]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Builds a Paper {@link PlayerProfile} from a decoded snapshot. */
    static PlayerProfile toPlayerProfile(Snapshot s) {
        PlayerProfile profile = Bukkit.createProfile(s.uuid(), s.name());
        if (!s.texturesValue().isEmpty()) {
            profile.setProperty(new ProfileProperty(
                    "textures",
                    s.texturesValue(),
                    s.texturesSignature().isEmpty() ? null : s.texturesSignature()));
        }
        return profile;
    }

    record Snapshot(UUID uuid, String name, String texturesValue, String texturesSignature) {}
}
