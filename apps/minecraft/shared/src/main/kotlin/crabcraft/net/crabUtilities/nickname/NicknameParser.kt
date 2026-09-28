package crabcraft.net.crabUtilities.nickname

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Parses trusted nickname styling without enabling interactive MiniMessage tags. */
object NicknameParser {
    private val ampHexPattern = Pattern.compile("&[#]([0-9a-fA-F]{6})")
    private val safeTags =
        TagResolver.builder()
            .resolver(StandardTags.color())
            .resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient())
            .resolver(StandardTags.rainbow())
            .resolver(StandardTags.reset())
            .build()
    private val miniMessage = MiniMessage.builder().tags(safeTags).build()
    private val legacy =
        LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
    private val plain = PlainTextComponentSerializer.plainText()

    @JvmStatic
    fun parse(raw: String?): Component {
        if (raw.isNullOrEmpty()) return Component.empty()
        try {
            if (miniMessage.stripTags(raw) != raw) return miniMessage.deserialize(raw)
        } catch (_: Exception) {
            // Fall through to the legacy parser.
        }
        val processed = convertAmpersandHex(raw.replace('§', '&'))
        return try {
            legacy.deserialize(processed)
        } catch (_: Exception) {
            Component.text(raw)
        }
    }

    @JvmStatic fun plain(raw: String?): String? = raw?.let { plain.serialize(parse(it)) }

    private fun convertAmpersandHex(input: String): String {
        val result = StringBuffer()
        val matcher = ampHexPattern.matcher(input)
        while (matcher.find()) {
            val hex = matcher.group(1).uppercase(Locale.ROOT)
            val replacement = "&x&${hex[0]}&${hex[1]}&${hex[2]}&${hex[3]}&${hex[4]}&${hex[5]}"
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(result)
        return result.toString()
    }
}
