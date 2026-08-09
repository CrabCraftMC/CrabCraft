package crabcraft.net.crabUtilities.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Captures player chat on servers where global chat is enabled: formats the
 * line, delivers it locally, and syncs it across the network.
 *
 * <p>At {@code HIGHEST}, global chat removes the vanilla viewers without
 * cancelling the event, while local formatting is sanitised. Delivery and
 * public publication are deferred until observation at {@code MONITOR}, after
 * normal moderation handlers have had their final opportunity to cancel.
 */
public class GlobalChatListener implements Listener {

    private final GlobalChatService service;

    public GlobalChatListener(GlobalChatService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (service.isEnabled()) {
            // Suppress vanilla delivery while leaving cancellation available to
            // later moderation handlers at the same priority.
            event.viewers().clear();
            return;
        }

        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.message(SafeChatMiniMessage.deserialize(rawMessage));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAcceptedChat(AsyncChatEvent event) {
        String visibleMessage = PlainTextComponentSerializer.plainText()
                .serialize(event.message());
        UUID id = event.getPlayer().getUniqueId();

        if (service.isEnabled()) {
            service.handleLocalChat(id, visibleMessage);
            return;
        }

        service.publishPublicChat(id, event.getPlayer().getName(), visibleMessage);
    }
}
