package crabcraft.net.crabUtilities

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Soft-dependency-safe access to EssentialsX vanish state. */
object VanishStatus {
    @JvmStatic
    fun isVanished(essentialsPlugin: Plugin?, player: Player?): Boolean =
        essentialsPlugin != null && player != null && EssentialsVanishResolver.isVanished(essentialsPlugin, player)

    @JvmStatic
    fun setVanished(essentialsPlugin: Plugin?, player: Player?, vanished: Boolean): Boolean =
        essentialsPlugin != null &&
            player != null &&
            EssentialsVanishResolver.setVanished(essentialsPlugin, player, vanished)
}
