package crabcraft.net.crabUtilities.chat;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.NicknameComponentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MentionAutocompleteListener implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final CrabUtilities plugin;

    public MentionAutocompleteListener(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (event.isCommand()) {
            return;
        }

        if (!plugin.getConfig().getBoolean("global-chat.mentions.enabled", true)) {
            return;
        }

        String prefix = plugin.getConfig().getString("global-chat.mentions.prefix", "@");
        String token = lastToken(event.getBuffer());
        if (prefix == null || prefix.isEmpty() || !token.startsWith(prefix)) {
            return;
        }

        String query = token.substring(prefix.length()).toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = mentionName(player);
            if (name.toLowerCase(Locale.ROOT).startsWith(query)) {
                completions.add(prefix + name);
            }
        }

        event.setCompletions(completions);
        event.setHandled(true);
    }

    private static String lastToken(String buffer) {
        int start = Math.max(buffer.lastIndexOf(' '), buffer.lastIndexOf('\n')) + 1;
        return buffer.substring(start);
    }

    private String mentionName(Player player) {
        Component nickname = NicknameComponentResolver.forPlayer(plugin.getEssentials(), player);
        if (nickname == null) {
            return player.getName();
        }

        String plain = PLAIN.serialize(nickname).trim();
        return plain.isEmpty() ? player.getName() : plain;
    }
}
