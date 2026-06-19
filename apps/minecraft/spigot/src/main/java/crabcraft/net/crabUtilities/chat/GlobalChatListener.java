package crabcraft.net.crabUtilities.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
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
        // Servers that haven't opted into global chat keep their chat local.
        if (!service.isEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component displayName = player.displayName();
        GlobalChatService.RenderedLine rendered = service.renderLine(displayName, player.getName(), plain, id);

        // We own delivery on a global server, so cancel the vanilla broadcast.
        event.setCancelled(true);
        service.deliverLocally(rendered.line(), rendered.mentioned());
        service.publish(id, player.getName(), displayName, plain);
    }
}
