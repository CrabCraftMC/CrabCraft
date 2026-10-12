package crabcraft.net.crabUtilities.endportals;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.function.BooleanSupplier;

/**
 * Prevents entities from entering End portals while the tweak is enabled.
 *
 * <p>The entry event fires before the entity is marked as being inside the
 * portal, so cancelling it stops players, vehicles, and other entities in both
 * directions. Spectator interactions are blocked before the portal destination
 * is resolved. Nether portals and End gateways remain unaffected.
 *
 * <p>The config is read on every End portal entry so
 * {@code /crabutilities reload tweaks} takes effect immediately.
 */
public final class EndPortalBlockerListener implements Listener {

    static final String CONFIG_PATH = "tweaks.end-portals.prevent-entry";

    private final BooleanSupplier preventEntry;

    public EndPortalBlockerListener(final CrabUtilities plugin) {
        this(() -> plugin.getConfig().getBoolean(CONFIG_PATH, false));
    }

    EndPortalBlockerListener(final BooleanSupplier preventEntry) {
        this.preventEntry = preventEntry;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortalEnter(final EntityPortalEnterEvent event) {
        if (event.getPortalType() == PortalType.ENDER && this.preventEntry.getAsBoolean()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorInteract(final PlayerInteractEvent event) {
        // Spectators bypass portal entry events; exiting the End also skips PlayerPortalEvent.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getPlayer().getGameMode() == GameMode.SPECTATOR
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.END_PORTAL
                && this.preventEntry.getAsBoolean()) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }
}
