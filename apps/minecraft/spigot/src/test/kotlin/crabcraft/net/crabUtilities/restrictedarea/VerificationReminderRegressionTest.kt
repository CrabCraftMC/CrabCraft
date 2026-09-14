package crabcraft.net.crabUtilities.restrictedarea

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

object VerificationReminderRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val now = AtomicLong()
        val reminder = VerificationReminder(now::get)
        val messages = ArrayList<Component>()
        val player = object : Audience {
            override fun sendMessage(message: Component) { messages.add(message) }
        }
        val first = UUID.randomUUID()
        reminder.send(first, player)
        check(messages.size == 1, "first blocked action did not send a reminder")
        val message = messages.first()
        check(NamedTextColor.RED == message.color(), "reminder is not red")
        check((message as TextComponent).content().contains("complete the short form"),
            "reminder does not explain how to get verified")
        val link = message.children().first()
        check((link as TextComponent).content() == "discord.crabcraft.net", "Discord link label is incorrect")
        check(ClickEvent.openUrl("https://discord.crabcraft.net") == link.clickEvent(), "Discord link is not clickable")
        check(link.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE, "Discord link is not underlined")
        now.set(4_999_999_999L)
        reminder.send(first, player)
        check(messages.size == 1, "repeated blocked actions flooded chat")
        reminder.send(UUID.randomUUID(), player)
        check(messages.size == 2, "one player's cooldown silenced another player")
        now.set(5_000_000_000L)
        reminder.send(first, player)
        check(messages.size == 3, "reminder was still throttled after five seconds")
        reminder.forget(first)
        reminder.send(first, player)
        check(messages.size == 4, "disconnect cleanup retained the cooldown")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
