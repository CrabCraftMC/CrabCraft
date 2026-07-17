package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MentionAutocompleteListener implements Listener {

    private final CrabUtilities plugin;
    private final Map<UUID, Set<String>> sentCompletions = new HashMap<>();

    public MentionAutocompleteListener(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAll, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sentCompletions.remove(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    public void refreshAll() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::refreshAll);
            return;
        }

        Set<String> completions = mentionCompletions();
        Set<UUID> onlineIds = new LinkedHashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            onlineIds.add(viewer.getUniqueId());
            syncCompletions(viewer, completions);
        }
        sentCompletions.keySet().retainAll(onlineIds);
    }

    private Set<String> mentionCompletions() {
        if (!plugin.getConfig().getBoolean("global-chat.mentions.enabled", true)) {
            return Set.of();
        }

        String prefix = plugin.getConfig().getString("global-chat.mentions.prefix", "@");
        if (prefix == null || prefix.isEmpty()) {
            return Set.of();
        }

        Set<String> completions = new LinkedHashSet<>();
        MentionProcessor.AliasIndex aliases = MentionProcessor.aliasIndex(
                Bukkit.getOnlinePlayers(), plugin.getEssentials());
        for (String name : aliases.completionNames().values()) {
            if (!name.isEmpty()) {
                completions.add(prefix + name);
            }
        }
        return completions;
    }

    private void syncCompletions(Player viewer, Set<String> completions) {
        Set<String> previous = sentCompletions.getOrDefault(viewer.getUniqueId(), Set.of());
        ArrayList<String> toRemove = new ArrayList<>(previous);
        toRemove.removeAll(completions);
        if (!toRemove.isEmpty()) {
            viewer.removeCustomChatCompletions(toRemove);
        }

        ArrayList<String> toAdd = new ArrayList<>(completions);
        toAdd.removeAll(previous);
        if (!toAdd.isEmpty()) {
            viewer.addCustomChatCompletions(toAdd);
        }

        if (completions.isEmpty()) {
            sentCompletions.remove(viewer.getUniqueId());
        } else {
            sentCompletions.put(viewer.getUniqueId(), Set.copyOf(completions));
        }
    }

}
