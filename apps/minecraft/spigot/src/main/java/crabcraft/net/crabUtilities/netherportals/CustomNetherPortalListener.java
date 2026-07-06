package crabcraft.net.crabUtilities.netherportals;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Lets players build nether portals of custom sizes and non-rectangular shapes.
 *
 * <p>When a block is ignited on top of a frame block, the {@link PortalShapeFinder}
 * floods the enclosed air along the portal's axis. Any region fully bounded by
 * frame blocks — of any shape — becomes a portal, as long as it satisfies the
 * configured minimum block count and maximum width/height. The event is
 * cancelled when a custom portal is created so vanilla's rectangle-only handler
 * doesn't also run.
 *
 * <p>Opt-in and disabled by default; the config is read live on every ignite so
 * {@code /crabutilities reload} takes effect without re-registration (matching
 * {@code SleepBroadcastListener}).
 *
 * <p>Ported from PaperTweaks' {@code CustomNetherPortals} module (VanillaTweaks'
 * "Custom Nether Portals" datapack).
 */
public class CustomNetherPortalListener implements Listener {

    private final CrabUtilities plugin;

    public CustomNetherPortalListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    private static boolean isInValidDimension(final World world) {
        return world.getEnvironment() == World.Environment.NETHER || world.getEnvironment() == World.Environment.NORMAL;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(final BlockIgniteEvent event) {
        if (!this.plugin.getConfig().getBoolean("custom-nether-portals.enabled", false)) {
            return;
        }

        final Block block = event.getBlock();
        final World world = block.getWorld();
        if (!isInValidDimension(world)) {
            return;
        }

        final PortalSettings settings = this.readSettings();
        if (!settings.isPortalFrame(block.getRelative(BlockFace.DOWN))) {
            return;
        }

        final @Nullable PortalAxis axis = this.findPortalAxis(world, block, settings);
        if (axis == null) {
            return;
        }

        event.setCancelled(new PortalShapeFinder(this.plugin, block, axis, settings).start());
    }

    private @Nullable PortalAxis findPortalAxis(final World world, final Block source, final PortalSettings settings) {
        for (final PortalAxis axis : PortalAxis.values()) {
            if (axis.isEnclosedOn(world, source.getLocation(), settings)) {
                return axis;
            }
        }
        return null;
    }

    private PortalSettings readSettings() {
        return new PortalSettings(
                this.readFrameMaterials(),
                Math.max(1, this.plugin.getConfig().getInt("custom-nether-portals.size.min-portal-blocks", 6)),
                Math.max(1, this.plugin.getConfig().getInt("custom-nether-portals.size.max-portal-width", 23)),
                Math.max(1, this.plugin.getConfig().getInt("custom-nether-portals.size.max-portal-height", 23))
        );
    }

    private Set<Material> readFrameMaterials() {
        final List<String> names = this.plugin.getConfig().getStringList("custom-nether-portals.frame-materials");
        final Set<Material> materials = EnumSet.noneOf(Material.class);
        for (final String name : names) {
            final @Nullable Material material = Material.matchMaterial(name);
            if (material != null) {
                materials.add(material);
            } else {
                this.plugin.getLogger().warning("Unknown custom-nether-portals frame material: " + name);
            }
        }
        if (materials.isEmpty()) {
            materials.add(Material.OBSIDIAN);
            materials.add(Material.CRYING_OBSIDIAN);
        }
        return materials;
    }
}
