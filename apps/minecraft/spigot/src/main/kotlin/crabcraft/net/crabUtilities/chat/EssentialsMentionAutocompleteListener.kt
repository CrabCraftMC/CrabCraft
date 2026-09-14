package crabcraft.net.crabUtilities.chat

import crabcraft.net.crabUtilities.CrabUtilities
import net.ess3.api.events.NickChangeEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** Loads the EssentialsX nickname event only when the soft dependency is present. */
class EssentialsMentionAutocompleteListener(
    private val plugin: CrabUtilities,
    private val autocomplete: MentionAutocompleteListener
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNickChange(event: NickChangeEvent) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { autocomplete.refreshAll() }, 2L)
    }
}
