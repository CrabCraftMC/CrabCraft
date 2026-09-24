package crabcraft.net.crabUtilities.chat

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Captures local chat and defers delivery until MONITOR, after normal moderation. Global chat suppresses vanilla
 * viewers without cancelling the event.
 */
open class GlobalChatListener(private val service: GlobalChatService) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onChat(event: AsyncChatEvent) {
        if (service.isEnabled()) {
            event.viewers().clear()
            return
        }
        val rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message())
        event.message(SafeChatMiniMessage.deserialize(rawMessage))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onAcceptedChat(event: AsyncChatEvent) {
        val visibleMessage = PlainTextComponentSerializer.plainText().serialize(event.message())
        val id = event.player.uniqueId
        if (service.isEnabled()) {
            service.handleLocalChat(id, visibleMessage)
            return
        }
        service.publishPublicChat(id, event.player.name, visibleMessage)
    }
}
