package crabcraft.net.crabUtilities;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Loads EssentialsX types only after the soft dependency has been found. */
final class EssentialsNicknameResolver {

    private EssentialsNicknameResolver() {
    }

    static Component forPlayer(Plugin essentialsPlugin, Player player) {
        if (!(essentialsPlugin instanceof Essentials essentials)) {
            return null;
        }
        User user = essentials.getUser(player);
        return user == null ? null : NicknameComponentResolver.fromRawNick(user.getNickname());
    }

    static Component forUniqueId(Plugin essentialsPlugin, UUID uuid) {
        if (!(essentialsPlugin instanceof Essentials essentials)) {
            return null;
        }
        User user = essentials.getUser(uuid);
        return user == null ? null : NicknameComponentResolver.fromRawNick(user.getNickname());
    }
}
