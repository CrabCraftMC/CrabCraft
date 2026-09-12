package crabcraft.net.crabUtilities.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

/** Parses player chat with visual tags only; interactive and data-driven tags stay literal. */
object SafeChatMiniMessage {
    private val ALLOWED_TAGS = TagResolver.resolver(
        StandardTags.color(),
        StandardTags.decorations(TextDecoration.BOLD),
        StandardTags.decorations(TextDecoration.ITALIC),
        StandardTags.decorations(TextDecoration.UNDERLINED),
        StandardTags.decorations(TextDecoration.STRIKETHROUGH),
        StandardTags.reset()
    )
    private val MINI_MESSAGE = MiniMessage.builder().tags(ALLOWED_TAGS).build()

    @JvmStatic
    fun deserialize(input: String): Component = try {
        MINI_MESSAGE.deserialize(input)
    } catch (ignored: RuntimeException) {
        // Malformed allowed tags should not cancel chat or create log spam.
        Component.text(input)
    }
}
