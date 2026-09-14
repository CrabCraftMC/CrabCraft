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
import org.bukkit.inventory.Recipe
import org.jspecify.annotations.Nullable
import java.util.HashSet

/** Caches and unlocks all server recipe keys on join, start and reload. */
open class UnlockAllRecipesManager(private val plugin: CrabUtilities) : Listener {
    @Volatile private var cachedKeys: Set<NamespacedKey>? = null
    private fun isEnabled(): Boolean = plugin.getConfig().getBoolean("tweaks.unlock-all-recipes.enabled", false)
    @EventHandler(priority = EventPriority.MONITOR)
    open fun onPlayerJoin(event: PlayerJoinEvent) { if (isEnabled()) discoverAll(event.getPlayer()) }
    open fun start() { if (isEnabled()) Bukkit.getOnlinePlayers().forEach(::discoverAll) }
    open fun refresh() { cachedKeys = null; start() }
    private fun discoverAll(player: Player) { player.discoverRecipes(recipeKeys()) }
    private fun recipeKeys(): Set<NamespacedKey> {
        cachedKeys?.let { return it }
        val keys = HashSet<NamespacedKey>()
        val iterator = Bukkit.recipeIterator()
        while (iterator.hasNext()) {
            val keyed = iterator.next() as? Keyed ?: continue
            keys.add(keyed.getKey())
        }
        cachedKeys = keys
        return keys
    }
}
