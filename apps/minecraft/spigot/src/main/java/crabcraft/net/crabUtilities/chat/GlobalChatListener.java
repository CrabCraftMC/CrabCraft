package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Captures player chat: enforces mutes for every server, and — only on
 * servers where global chat is enabled — formats the line, delivers it
 * locally, and syncs it across the network.
 */
public class GlobalChatListener implements Listener {

    private final CrabUtilities plugin;
    private final MuteCache muteCache;
    private final GlobalChatService service;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final String muteMessage;
    private final String muteMessageTemp;

    public GlobalChatListener(CrabUtilities plugin, MuteCache muteCache, GlobalChatService service) {
        this.plugin = plugin;
        this.muteCache = muteCache;
        this.service = service;
        this.muteMessage = plugin.getConfig().getString("mute.message",
                "<red>You are currently muted and cannot chat.");
        this.muteMessageTemp = plugin.getConfig().getString("mute.message-temp",
                "<red>You are muted for <time>.");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        // Mutes apply on every server, regardless of global-chat state.
        if (muteCache.isMuted(id)) {
            event.setCancelled(true);
            long remaining = muteCache.remainingMillis(id);
            Component notice;
            if (remaining > 0L) {
                notice = miniMessage.deserialize(muteMessageTemp,
                        Placeholder.unparsed("time", humanizeDuration(remaining)));
            } else {
                notice = miniMessage.deserialize(muteMessage);
            }
            player.sendMessage(notice);
            return;
        }

        // Not a global-chat server: leave the message to normal handling.
        if (!service.isEnabled()) {
            return;
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        Component displayName = player.displayName();
        GlobalChatService.RenderedLine rendered = service.renderLine(displayName, player.getName(), plain, id);

        // We own delivery on a global server, so cancel the vanilla broadcast.
        event.setCancelled(true);
        service.deliverLocally(rendered.line(), rendered.mentioned());
        service.publish(id, player.getName(), displayName, plain);
    }

    /**
     * Renders a millis duration as a coarse "1d 2h 3m" string, falling back
     * to "45s" for sub-minute durations. Used for the {@code <time>}
     * placeholder in the temporary-mute message.
     */
    static String humanizeDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        // Only show seconds when there's nothing coarser, to keep it tidy.
        if (sb.length() == 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
