package crabcraft.net.crabUtilities.endportals;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.PortalType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;

import java.util.function.BooleanSupplier;

/**
 * Prevents entities from entering End portals while the tweak is enabled.
 *
 * <p>The entry event fires before the entity is marked as being inside the
 * portal, so cancelling it stops players, vehicles, and other entities in both
 * directions. Nether portals and End gateways use different portal types and
 * remain unaffected.
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
}
