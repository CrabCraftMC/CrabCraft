package crabcraft.net.crabUtilities.chat

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.PlayerVisibility
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

open class MentionAutocompleteListener(private val plugin: CrabUtilities) : Listener {
    private val sentCompletions = HashMap<UUID, Set<String>>()

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { refreshAll() }, 20L)
    }

    @EventHandler
    open fun onPlayerQuit(event: PlayerQuitEvent) {
        sentCompletions.remove(event.getPlayer().getUniqueId())
        Bukkit.getScheduler().runTask(plugin, Runnable { refreshAll() })
    }

    open fun refreshAll() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, Runnable { refreshAll() })
            return
        }
        val onlineIds = LinkedHashSet<UUID>()
        for (viewer in Bukkit.getOnlinePlayers()) {
            onlineIds.add(viewer.getUniqueId())
            syncCompletions(viewer, mentionCompletions(viewer))
        }
        sentCompletions.keys.retainAll(onlineIds)
    }

    private fun mentionCompletions(viewer: Player): Set<String> {
        if (!plugin.getConfig().getBoolean("global-chat.mentions.enabled", true)) return emptySet()
        val prefix = plugin.getConfig().getString("global-chat.mentions.prefix", "@")
        if (prefix.isNullOrEmpty()) return emptySet()
        val completions = LinkedHashSet<String>()
        val aliases = MentionProcessor.aliasIndex(
            PlayerVisibility.visibleTo(viewer, Bukkit.getOnlinePlayers()), plugin.getEssentials())
        for (name in aliases.completionNames().values) {
            if (name.isNotEmpty()) completions.add(prefix + name)
        }
        return completions
    }

    private fun syncCompletions(viewer: Player, completions: Set<String>) {
        val previous = sentCompletions.getOrDefault(viewer.getUniqueId(), emptySet())
        val toRemove = ArrayList(previous)
        toRemove.removeAll(completions)
        if (toRemove.isNotEmpty()) viewer.removeCustomChatCompletions(toRemove)
        val toAdd = ArrayList(completions)
        toAdd.removeAll(previous)
        if (toAdd.isNotEmpty()) viewer.addCustomChatCompletions(toAdd)
        if (completions.isEmpty()) {
            sentCompletions.remove(viewer.getUniqueId())
        } else {
            sentCompletions[viewer.getUniqueId()] = java.util.Set.copyOf(completions)
        }
    }
}
