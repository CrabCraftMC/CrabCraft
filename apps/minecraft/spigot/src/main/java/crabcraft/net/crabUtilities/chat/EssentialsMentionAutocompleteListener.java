package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.CrabUtilities;
import net.ess3.api.events.NickChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Loads the EssentialsX nickname event only when the soft dependency is present. */
public final class EssentialsMentionAutocompleteListener implements Listener {

    private final CrabUtilities plugin;
    private final MentionAutocompleteListener autocomplete;

    public EssentialsMentionAutocompleteListener(
            CrabUtilities plugin,
            MentionAutocompleteListener autocomplete) {
        this.plugin = plugin;
        this.autocomplete = autocomplete;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNickChange(NickChangeEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, autocomplete::refreshAll, 2L);
    }
}
