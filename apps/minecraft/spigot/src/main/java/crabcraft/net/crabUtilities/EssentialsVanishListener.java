package crabcraft.net.crabUtilities;

import net.ess3.api.events.VanishStatusChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Loads the EssentialsX vanish event only when the soft dependency is present. */
public final class EssentialsVanishListener implements Listener {

    private final CrabUtilities plugin;
    private final VanishStatusPublisher publisher;

    public EssentialsVanishListener(CrabUtilities plugin, VanishStatusPublisher publisher) {
        this.plugin = plugin;
        this.publisher = publisher;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanishChange(VanishStatusChangeEvent event) {
        Player player = event.getAffected().getBase();
        if (player == null || !player.isOnline()) return;

        // Send the event's committed value immediately so Velocity closes any
        // public exposure window before other derived state is refreshed.
        publisher.publish(player, event.getValue());
        Bukkit.getScheduler().runTask(plugin, () -> {
            publisher.publish(player);
            plugin.onVanishStatusChanged(player);
        });
    }
}
