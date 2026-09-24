package crabcraft.net.crabUtilities

import com.earth2me.essentials.Essentials
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Loads EssentialsX types only after the soft dependency has been found. */
internal object EssentialsVanishResolver {
    @JvmStatic
    fun isVanished(essentialsPlugin: Plugin, player: Player): Boolean {
        val essentials = essentialsPlugin as? Essentials ?: return false
        return essentials.getUser(player)?.isVanished == true
    }

    @JvmStatic
    fun setVanished(essentialsPlugin: Plugin, player: Player, vanished: Boolean): Boolean {
        val essentials = essentialsPlugin as? Essentials ?: return false
        val user = essentials.getUser(player) ?: return false
        if (user.isVanished != vanished) user.setVanished(vanished)
        return true
    }
}
