package crabcraft.net.crabUtilities.netherportals;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Immutable snapshot of the {@code custom-nether-portals} config, read fresh
 * from {@code config.yml} on each ignite so {@code /crabutilities reload} takes
 * effect without re-registering the listener.
 *
 * @param frameMaterials blocks that count as portal frame (e.g. obsidian)
 * @param minPortalSize  minimum number of interior blocks for a valid portal
 * @param maxPortalWidth maximum portal width (flat axis span)
 * @param maxPortalHeight maximum portal height (vertical span)
 */
record PortalSettings(Set<Material> frameMaterials, int minPortalSize, int maxPortalWidth, int maxPortalHeight) {

    boolean isPortalFrame(final @Nullable Block block) {
        return block != null && this.frameMaterials.contains(block.getType());
    }
}
