package crabcraft.net.crabUtilities.chat.bridge

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.regex.Pattern

object StaffChatComponents {
    private val PREFIX = Pattern.compile("^# ?")
    private val PLAIN = PlainTextComponentSerializer.plainText()

    @JvmStatic
    fun hasPrefix(message: Component): Boolean = PLAIN.serialize(message).startsWith("#")

    @JvmStatic
    fun removePrefix(message: Component): Component = message.replaceText { config ->
        config.match(PREFIX).replacement(Component.empty()).times(1)
    }

    @JvmStatic
    fun isEmpty(message: Component): Boolean = PLAIN.serialize(message).all { Character.isWhitespace(it) }
}
