package crabcraft.net.crabUtilities.netherportals;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Flood-fills the air enclosed by a portal frame and, if it forms a fully
 * enclosed region within the configured size limits, converts it to nether
 * portal blocks.
 *
 * <p>Because the fill walks the frame in every direction rather than assuming a
 * rectangle, portals may be any shape — L-shapes, plus-signs, rough circles —
 * not just the vanilla rectangle, and any size between the configured minimum
 * block count and maximum width/height.
 *
 * <p>Ported from PaperTweaks' {@code CustomNetherPortals} module (itself a
 * performant reimplementation of VanillaTweaks' "Custom Nether Portals"
 * datapack), adapted to read its settings from CrabUtilities' {@code config.yml}
 * instead of the upstream module's injected config.
 */
final class PortalShapeFinder {

    private final Plugin plugin;
    private final PortalSettings settings;
    private final PortalAxis axis;
    // Weakly-consistent concurrent sets so the flood can add newly discovered
    // interior blocks while an outer iteration over the same set is in flight,
    // without a ConcurrentModificationException. Everything runs on the main
    // thread; the concurrency here is purely to tolerate self-mutation during
    // iteration.
    private final Set<Block> portalInterior;
    private final Set<Long> checkedLocations;

    PortalShapeFinder(final Plugin plugin, final Block first, final PortalAxis axis, final PortalSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.axis = axis;
        this.portalInterior = ConcurrentHashMap.newKeySet();
        this.portalInterior.add(first);
        this.checkedLocations = ConcurrentHashMap.newKeySet();
    }

    private static boolean isReplaceable(final Block block) {
        final Material type = block.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || type == Material.FIRE;
    }

    private static long toLong(final Block block) {
        final Location location = block.getLocation();
        return (((long) location.getBlockX() & 67108863L) << 38)
                | (((long) location.getBlockY() & 4095L))
                | (((long) location.getBlockZ() & 67108863L) << 12);
    }

    /**
     * Grows the interior outward from the ignited block. Returns {@code true}
     * (and schedules the portal-block placement) only when the region is fully
     * enclosed by frame and within the configured size limits.
     */
    boolean start() {
        boolean enclosed = true;

        while (this.portalInterior.size() <= this.settings.maxPortalHeight() * this.settings.maxPortalWidth()
                && this.checkedLocations.size() < this.portalInterior.size()) {
            final Iterator<Block> interiorIter = this.portalInterior.iterator();

            finished:
            {
                Block currentBlock;
                do {
                    do {
                        if (!interiorIter.hasNext()) {
                            break finished;
                        }
                        currentBlock = interiorIter.next();
                    } while (this.checkedLocations.contains(toLong(currentBlock)));

                    this.checkedLocations.add(toLong(currentBlock));
                } while (this.checkSurrounding(currentBlock));

                // A neighbour was neither replaceable air nor frame: the region
                // leaks, so this is not a valid portal.
                enclosed = false;
            }

            if (!enclosed) {
                break;
            }
        }

        if (enclosed && this.portalInterior.size() >= this.settings.minPortalSize()) {
            final int maxY = Collections.max(this.portalInterior, Comparator.comparingInt(Block::getY)).getY();
            final int minY = Collections.min(this.portalInterior, Comparator.comparingInt(Block::getY)).getY();
            if (maxY - minY > this.settings.maxPortalHeight()) {
                return false;
            }

            final ToIntFunction<Block> flatFunction = this.axis == PortalAxis.X ? Block::getX : Block::getZ;
            final Block maxFlatBlock = Collections.max(this.portalInterior, Comparator.comparingInt(flatFunction));
            final Block minFlatBlock = Collections.min(this.portalInterior, Comparator.comparingInt(flatFunction));
            if (flatFunction.applyAsInt(maxFlatBlock) - flatFunction.applyAsInt(minFlatBlock) > this.settings.maxPortalWidth()) {
                return false;
            }

            // Defer the block changes a tick so we don't mutate the world from
            // inside the ignite event.
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.portalInterior.forEach(block -> {
                block.setType(Material.NETHER_PORTAL);
                this.axis.applyTo(block);
            }), 1L);
            return true;
        }
        return false;
    }

    private boolean checkSurrounding(final Block source) {
        return this.checkValidPortalInterior(source, BlockFace.UP)
                && this.checkValidPortalInterior(source, BlockFace.DOWN)
                && this.checkValidPortalInterior(source, this.axis.left)
                && this.checkValidPortalInterior(source, this.axis.right);
    }

    private boolean checkValidPortalInterior(final Block source, final BlockFace face) {
        final Block toCheck = source.getRelative(face);
        if (isReplaceable(toCheck)) {
            this.portalInterior.add(toCheck);
            return true;
        }
        return this.settings.isPortalFrame(toCheck);
    }
}
