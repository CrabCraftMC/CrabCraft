package crabcraft.net.crabUtilities

import com.earth2me.essentials.Essentials
import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Loads EssentialsX types only after the soft dependency has been found. */
internal object EssentialsNicknameResolver {
    @JvmStatic
    fun forPlayer(essentialsPlugin: Plugin, player: Player): Component? {
        val essentials = essentialsPlugin as? Essentials ?: return null
        return NicknameComponentResolver.fromRawNick(essentials.getUser(player)?.nickname)
    }

    @JvmStatic
    fun forUniqueId(essentialsPlugin: Plugin, uuid: UUID): Component? {
        val essentials = essentialsPlugin as? Essentials ?: return null
        return NicknameComponentResolver.fromRawNick(essentials.getUser(uuid)?.nickname)
    }
}
