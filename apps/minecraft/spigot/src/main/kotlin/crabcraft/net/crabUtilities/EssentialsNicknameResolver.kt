package crabcraft.net.crabUtilities

import com.earth2me.essentials.Essentials
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Loads EssentialsX types only after the soft dependency has been found. */
class EssentialsNicknameResolver private constructor() {
    companion object {
        @JvmStatic
        fun forPlayer(essentialsPlugin: Plugin, player: Player): Component? {
            if (essentialsPlugin !is Essentials) return null
            val user = essentialsPlugin.getUser(player)
            return if (user == null) null else NicknameComponentResolver.fromRawNick(user.getNickname())
        }

        @JvmStatic
        fun forUniqueId(essentialsPlugin: Plugin, uuid: UUID): Component? {
            if (essentialsPlugin !is Essentials) return null
            val user = essentialsPlugin.getUser(uuid)
            return if (user == null) null else NicknameComponentResolver.fromRawNick(user.getNickname())
        }
    }
}
