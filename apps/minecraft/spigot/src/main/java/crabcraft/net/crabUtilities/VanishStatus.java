package crabcraft.net.crabUtilities;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Soft-dependency-safe access to EssentialsX vanish state. */
public final class VanishStatus {

    private VanishStatus() {
    }

    public static boolean isVanished(Plugin essentialsPlugin, Player player) {
        if (essentialsPlugin == null || player == null) {
            return false;
        }
        return EssentialsVanishResolver.isVanished(essentialsPlugin, player);
    }

    public static boolean setVanished(Plugin essentialsPlugin, Player player, boolean vanished) {
        if (essentialsPlugin == null || player == null) {
            return false;
        }
        return EssentialsVanishResolver.setVanished(essentialsPlugin, player, vanished);
    }
}
