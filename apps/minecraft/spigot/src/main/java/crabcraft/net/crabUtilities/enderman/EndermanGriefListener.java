package crabcraft.net.crabUtilities.enderman;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * Stops endermen from picking up or placing blocks.
 *
 * <p>The vanilla {@code mobGriefing} game rule is all-or-nothing: turning it off
 * also disables creeper/ghast/wither block damage, villager farming, sheep
 * eating grass, and more. This targets endermen specifically by cancelling the
 * {@link EntityChangeBlockEvent} they fire when grabbing or setting down a
 * block, leaving every other {@code mobGriefing} behaviour intact.
 *
 * <p>Disabled by default; the config is read live so a reload toggles it
 * without re-registration.
 */
public class EndermanGriefListener implements Listener {

    private final CrabUtilities plugin;

    public EndermanGriefListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        if (event.getEntity().getType() != EntityType.ENDERMAN) {
            return;
        }
        if (this.plugin.getConfig().getBoolean("enderman-grief.prevent", false)) {
            event.setCancelled(true);
        }
    }
}
