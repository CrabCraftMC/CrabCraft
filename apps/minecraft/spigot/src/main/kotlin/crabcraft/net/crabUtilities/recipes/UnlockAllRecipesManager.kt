package crabcraft.net.crabUtilities.recipes

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** Unlocks every server recipe on join and refresh, caching keys until reload. */
open class UnlockAllRecipesManager(private val plugin: CrabUtilities) : Listener {
    @Volatile private var cachedKeys: Set<NamespacedKey>? = null

    private fun isEnabled() = plugin.config.getBoolean("tweaks.unlock-all-recipes.enabled", false)

    @EventHandler(priority = EventPriority.MONITOR)
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        if (isEnabled()) discoverAll(event.player)
    }

    open fun start() {
        if (isEnabled()) Bukkit.getOnlinePlayers().forEach(::discoverAll)
    }

    open fun refresh() {
        cachedKeys = null
        start()
    }

    private fun discoverAll(player: Player) {
        player.discoverRecipes(recipeKeys())
    }

    private fun recipeKeys(): Set<NamespacedKey> {
        cachedKeys?.let {
            return it
        }
        val keys = HashSet<NamespacedKey>()
        val iterator = Bukkit.recipeIterator()
        while (iterator.hasNext()) (iterator.next() as? Keyed)?.let { keys.add(it.key) }
        cachedKeys = keys
        return keys
    }
}
