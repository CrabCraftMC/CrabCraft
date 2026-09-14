package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.media.language.MediaMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration

object CrabMessagesRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        checkColour(CrabMessages.success("Success"), CrabMessages.SUCCESS, "success")
        checkColour(CrabMessages.error("Error"), CrabMessages.ERROR, "error")
        checkColour(CrabMessages.warning("Warning"), CrabMessages.HIGHLIGHT, "warning")
        checkColour(CrabMessages.text("Text"), CrabMessages.TEXT, "neutral")
        checkColour(CrabMessages.muted("Muted"), CrabMessages.MUTED, "muted")

        val label = CrabMessages.label("State", "running")
        check(label.color() == CrabMessages.ACCENT, "labels must use the media accent")
        check(label.children().size == 1, "label value component is missing")
        check(label.children()[0].color() == CrabMessages.TEXT,
            "label values must use off-white")

        val media = MediaMessages()
        checkColour(
            media.component("command.create.messages.created"),
            CrabMessages.SUCCESS,
            "media success")
        checkColour(
            media.component("error.command.no-permission"),
            CrabMessages.ERROR,
            "media error")
        checkColour(
            media.component("command.help.messages.header"),
            CrabMessages.ACCENT,
            "media help heading")
    }

    private fun checkColour(component: Component, expected: Any, role: String) {
        check(expected == component.color(), "$role colour changed")
        check(component.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE,
            "$role message inherited italics")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }
}
