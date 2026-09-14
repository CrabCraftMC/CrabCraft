package crabcraft.net.crabUtilities

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage

/** Shared player-facing colours and message components for CrabUtilities. */
object CrabMessages {
    const val ACCENT_TAG = "<#FC835C>"
    const val HIGHLIGHT_TAG = "<#FCD05C>"
    const val SUCCESS_TAG = "<#77dd77>"
    const val ERROR_TAG = "<#f77069>"
    const val TEXT_TAG = "<#F4F1EA>"
    const val MUTED_TAG = "<#b0b0b0>"

    @JvmField val ACCENT: TextColor = TextColor.color(0xFC835C)
    @JvmField val HIGHLIGHT: TextColor = TextColor.color(0xFCD05C)
    @JvmField val SUCCESS: TextColor = TextColor.color(0x77DD77)
    @JvmField val ERROR: TextColor = TextColor.color(0xF77069)
    @JvmField val TEXT: TextColor = TextColor.color(0xF4F1EA)
    @JvmField val MUTED: TextColor = TextColor.color(0xB0B0B0)

    private val MINI_MESSAGE = MiniMessage.miniMessage()

    @JvmStatic
    fun text(message: String): Component = component(message, TEXT)

    @JvmStatic
    fun muted(message: String): Component = component(message, MUTED)

    @JvmStatic
    fun accent(message: String): Component = component(message, ACCENT)

    @JvmStatic
    fun highlight(message: String): Component = component(message, HIGHLIGHT)

    @JvmStatic
    fun success(message: String): Component = component(message, SUCCESS)

    @JvmStatic
    fun error(message: String): Component = component(message, ERROR)

    @JvmStatic
    fun warning(message: String): Component = component(message, HIGHLIGHT)

    @JvmStatic
    fun label(label: String, value: Any): Component =
        component("$label: ", ACCENT).append(component(value.toString(), TEXT))

    @JvmStatic
    fun label(label: String, value: Component): Component =
        component("$label: ", ACCENT).append(value)

    @JvmStatic
    fun mini(value: String): Component =
        MINI_MESSAGE.deserialize(value).decoration(TextDecoration.ITALIC, false)

    private fun component(message: String, colour: TextColor): Component =
        Component.text(message, colour).decoration(TextDecoration.ITALIC, false)
}
