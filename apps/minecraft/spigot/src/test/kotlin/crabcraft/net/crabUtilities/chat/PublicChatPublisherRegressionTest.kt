package crabcraft.net.crabUtilities.chat

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import java.util.UUID

object PublicChatPublisherRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val token = UUID.randomUUID().toString().take(8)
        val rawMessage = "<#12abef>Signal</#12abef> <bold>$token</bold>"
        check(GlobalChatService.visiblePlainText(rawMessage) == "Signal $token",
            "public chat did not strip visual MiniMessage formatting")

        val uuid = UUID.randomUUID()
        val username = "Fict$token"
        val fields = GlobalChatService.publicChatFields(uuid, username, rawMessage)
        check(fields.keys.toList() == listOf("uuid", "username", "message"),
            "public chat stream fields changed")
        check(fields["uuid"] == uuid.toString(), "public chat UUID changed")
        check(fields["username"] == username, "public chat username changed")
        check(fields["message"] == "Signal $token", "public chat message was not visible text")

        val acceptedChat = GlobalChatListener::class.java
            .getDeclaredMethod("onAcceptedChat", AsyncChatEvent::class.java)
        val handler = acceptedChat.getAnnotation(EventHandler::class.java)
        check(handler != null, "accepted-chat handler lost its event handler annotation")
        check(handler!!.priority == EventPriority.MONITOR,
            "global chat delivery or public publication can run before final moderation")
        check(handler.ignoreCancelled,
            "cancelled chat could be delivered or reach the public stream")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
