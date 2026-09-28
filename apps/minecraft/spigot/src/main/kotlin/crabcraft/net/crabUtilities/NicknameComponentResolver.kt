package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.nickname.NicknameParser
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Resolves EssentialsX nicknames consistently for chat, death and advancement messages. */
object NicknameComponentResolver {
    private val PLAIN = PlainTextComponentSerializer.plainText()

    /** Returns null when EssentialsX is absent or the player has no nickname. */
    @JvmStatic
    fun forPlayer(essentialsPlugin: Plugin?, player: Player): Component? = essentialsPlugin?.let {
        EssentialsNicknameResolver.forPlayer(it, player)
    }

    /** Includes offline users. */
    @JvmStatic
    fun forUniqueId(essentialsPlugin: Plugin?, uuid: UUID?): Component? =
        if (essentialsPlugin == null || uuid == null) null
        else EssentialsNicknameResolver.forUniqueId(essentialsPlugin, uuid)

    @JvmStatic
    fun plainNicknameOrName(essentialsPlugin: Plugin?, player: Player): String =
        plain(forPlayer(essentialsPlugin, player)) ?: player.name

    @JvmStatic
    fun fromRawNick(raw: String?): Component? =
        if (raw == null || raw.codePoints().allMatch(Character::isWhitespace)) null else NicknameParser.parse(raw)

    private fun plain(component: Component?): String? = component?.let {
        PLAIN.serialize(it).trim { character -> character <= ' ' }.ifEmpty { null }
    }
}
