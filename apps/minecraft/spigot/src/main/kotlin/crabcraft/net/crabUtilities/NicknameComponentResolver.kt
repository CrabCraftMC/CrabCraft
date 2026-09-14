package crabcraft.net.crabUtilities

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Locale
import java.util.UUID
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Resolves an EssentialsX nickname into a styled component, honouring both
 * MiniMessage and legacy colours, including hex codes.
 * Shared by chat formatting and death/advancement rewriting.
 */
class NicknameComponentResolver private constructor() {
    companion object {
        private val AMP_HEX_PATTERN = Pattern.compile("&[#]([0-9a-fA-F]{6})")
        private val LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
        private val MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(),
                StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset())).build()
        private val PLAIN = PlainTextComponentSerializer.plainText()

        /** Returns the styled EssentialsX nickname, or null if unavailable. */
        @JvmStatic
        fun forPlayer(essentialsPlugin: Plugin?, player: Player): Component? {
            if (essentialsPlugin == null) return null
            return EssentialsNicknameResolver.forPlayer(essentialsPlugin, player)
        }

        /** Resolves an EssentialsX user by UUID, including offline users. */
        @JvmStatic
        fun forUniqueId(essentialsPlugin: Plugin?, uuid: UUID?): Component? {
            if (essentialsPlugin == null || uuid == null) return null
            return EssentialsNicknameResolver.forUniqueId(essentialsPlugin, uuid)
        }

        /** Returns the player's plain nickname, falling back to their account name. */
        @JvmStatic
        fun plainNicknameOrName(essentialsPlugin: Plugin?, player: Player): String {
            return plain(forPlayer(essentialsPlugin, player)) ?: player.getName()
        }

        @JvmStatic
        fun fromRawNick(raw: String?): Component? {
            if (raw == null || raw.all { Character.isWhitespace(it) }) return null
            // Only enable formatting tags that are safe and useful in a nickname.
            try {
                if (MINI_MESSAGE.stripTags(raw) != raw) return MINI_MESSAGE.deserialize(raw)
            } catch (ignored: Exception) {
                // Fall through to legacy parsing.
            }
            val processed = convertAmpersandHex(raw.replace('§', '&'))
            return try {
                LEGACY.deserialize(processed)
            } catch (ex: Exception) {
                Component.text(raw)
            }
        }

        private fun convertAmpersandHex(input: String): String {
            val sb = StringBuffer()
            val m = AMP_HEX_PATTERN.matcher(input)
            while (m.find()) {
                val hex = m.group(1).uppercase(Locale.ROOT)
                val replacement = "&x&" + hex[0] + "&" + hex[1] + "&" + hex[2] +
                    "&" + hex[3] + "&" + hex[4] + "&" + hex[5]
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement))
            }
            m.appendTail(sb)
            return sb.toString()
        }

        private fun plain(component: Component?): String? {
            if (component == null) return null
            val plain = PLAIN.serialize(component).trim { it <= ' ' }
            return if (plain.isEmpty()) null else plain
        }
    }
}
