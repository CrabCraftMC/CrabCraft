package crabcraft.net.crabUtilities.restrictedarea

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component

object VerificationReminderRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val now = AtomicLong()
        val reminder = VerificationReminder(now::get)
        val messages = ArrayList<Component>()
        val player =
            object : Audience {
                override fun sendMessage(message: Component) {
                    messages.add(message)
                }
            }
        val first = UUID.randomUUID()
        reminder.send(first, player)
        check(messages.size == 1, "first blocked action did not send a reminder")
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
