package crabcraft.net.crabUtilities;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Loads EssentialsX types only after the soft dependency has been found. */
final class EssentialsVanishResolver {

    private EssentialsVanishResolver() {
    }

    static boolean isVanished(Plugin essentialsPlugin, Player player) {
        if (!(essentialsPlugin instanceof Essentials essentials)) {
            return false;
        }
        User user = essentials.getUser(player);
        return user != null && user.isVanished();
    }

    static boolean setVanished(Plugin essentialsPlugin, Player player, boolean vanished) {
        if (!(essentialsPlugin instanceof Essentials essentials)) {
            return false;
        }
        User user = essentials.getUser(player);
        if (user == null) return false;
        if (user.isVanished() != vanished) {
            user.setVanished(vanished);
        }
        return true;
    }
}
