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
 * <p>Runs at {@code HIGHEST} with {@code ignoreCancelled = true} so other chat
 * plugins (e.g. a mute/punishment plugin like LiteBans, or an anti-spam) get to
 * veto a message first — if anything cancelled the event, we never broadcast it.
 */
public class GlobalChatListener implements Listener {

    private final GlobalChatService service;

    public GlobalChatListener(GlobalChatService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String rawMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        // Servers that haven't opted into global chat keep their chat local.
        if (!service.isEnabled()) {
            event.message(SafeChatMiniMessage.deserialize(rawMessage));
            return;
        }

        UUID id = event.getPlayer().getUniqueId();

        // We own delivery on a global server, so cancel the vanilla broadcast.
        event.setCancelled(true);
        service.handleLocalChat(id, rawMessage);
    }
}
