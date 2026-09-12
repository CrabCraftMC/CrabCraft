package crabcraft.net.crabUtilities.velocity

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Parses trusted nickname styling without enabling interactive MiniMessage tags. */
class NicknameComponentParser private constructor() {
    companion object {
        private val AMP_HEX_PATTERN = Pattern.compile("&[#]([0-9a-fA-F]{6})")
        private val SAFE_TAGS = TagResolver.builder().resolver(StandardTags.color()).resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient()).resolver(StandardTags.rainbow()).resolver(StandardTags.reset()).build()
        private val MINI_MESSAGE = MiniMessage.builder().tags(SAFE_TAGS).build()
        private val LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
        private val PLAIN = PlainTextComponentSerializer.plainText()
        @JvmStatic fun parse(raw: String?): Component {
            if (raw.isNullOrEmpty()) return Component.empty()
            try { if (MINI_MESSAGE.stripTags(raw) != raw) return MINI_MESSAGE.deserialize(raw) }
            catch (_: Exception) { /* Fall through to the legacy parser. */ }
            val processed = convertAmpersandHex(raw.replace('§', '&'))
            return try { LEGACY.deserialize(processed) } catch (_: Exception) { Component.text(raw) }
        }
        @JvmStatic fun plain(raw: String?): String? = if (raw == null) null else PLAIN.serialize(parse(raw))
        private fun convertAmpersandHex(input: String): String {
            val result = StringBuffer()
            val matcher = AMP_HEX_PATTERN.matcher(input)
            while (matcher.find()) {
                val hex = matcher.group(1).uppercase(Locale.ROOT)
                val replacement = "&x&" + hex[0] + "&" + hex[1] + "&" + hex[2] + "&" + hex[3] + "&" + hex[4] + "&" + hex[5]
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement))
            }
            matcher.appendTail(result)
            return result.toString()
        }
    }
}
