package crabcraft.net.crabUtilities.chat

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Captures chat on servers where global chat is enabled, delivering it locally
 * and across the network. At HIGHEST, global chat removes vanilla viewers;
 * delivery and publication wait for MONITOR after moderation may cancel.
 */
open class GlobalChatListener(private val service: GlobalChatService) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onChat(event: AsyncChatEvent) {
        if (service.isEnabled()) {
            // Suppress vanilla delivery while leaving cancellation available to
            // later moderation handlers at the same priority.
            event.viewers().clear()
            return
        }
        val rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message())
        event.message(SafeChatMiniMessage.deserialize(rawMessage))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onAcceptedChat(event: AsyncChatEvent) {
        val visibleMessage = PlainTextComponentSerializer.plainText().serialize(event.message())
        val id = event.getPlayer().getUniqueId()
        if (service.isEnabled()) {
            service.handleLocalChat(id, visibleMessage)
            return
        }
        service.publishPublicChat(id, event.getPlayer().getName(), visibleMessage)
    }
}
