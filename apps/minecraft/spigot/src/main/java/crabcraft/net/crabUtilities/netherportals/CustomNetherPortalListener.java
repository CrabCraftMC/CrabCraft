package crabcraft.net.crabUtilities.netherportals;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
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

    // Hard ceiling on the configurable portal dimensions. Keeps maxWidth *
    // maxHeight (the flood-fill's block budget) well within int range and bounds
    // the worst-case flood so an absurd config value can't overflow the loop
    // bound or hang the server on ignite.
    private static final int MAX_DIMENSION = 256;
    private static final int DESTINATION_PORTAL_SEARCH_RADIUS = 3;
    private static final int PORTAL_COLLAPSE_BATCH_SIZE = 4096;
    private static final Comparator<CustomPortalBounds.BlockPosition> PORTAL_COLLAPSE_ORDER = Comparator
            .comparingInt((CustomPortalBounds.BlockPosition block) -> block.x() >> 4)
            .thenComparingInt(block -> block.z() >> 4)
            .thenComparingInt(CustomPortalBounds.BlockPosition::y)
            .thenComparingInt(CustomPortalBounds.BlockPosition::x)
            .thenComparingInt(CustomPortalBounds.BlockPosition::z);

    private final CrabUtilities plugin;
    private final CustomPortalRegistry portalRegistry = new CustomPortalRegistry();
    private final Deque<PendingPortalCollapse> pendingPortalCollapses = new ArrayDeque<>();
    private final Set<CustomPortalBounds> collapsingPortals =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean portalCollapseTaskScheduled;
    // Parsed config snapshot, cached so we don't rebuild the frame-material set
    // on every ignite. Rebuilt on demand and cleared by invalidate() on reload.
    private volatile @Nullable PortalSettings cachedSettings;

    public CustomNetherPortalListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    /** Drops the cached settings so the next ignite re-reads config (reload). */
    public void invalidate() {
        this.cachedSettings = null;
    }

    private static boolean isInValidDimension(final World world) {
        return world.getEnvironment() == World.Environment.NETHER || world.getEnvironment() == World.Environment.NORMAL;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(final BlockIgniteEvent event) {
        if (!this.plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) {
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

        event.setCancelled(new PortalShapeFinder(this.plugin, block, axis, settings, this.portalRegistry).start());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(final PlayerTeleportEvent event) {
        // Paper raises PlayerPortalEvent before it searches for an exit, then a
        // separate PlayerTeleportEvent after it calculates the actual exit.
        if (event instanceof PlayerPortalEvent
                || event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                || !this.plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) {
            return;
        }

        final @Nullable Location vanillaDestination = event.getTo();
        if (vanillaDestination == null || !isInValidDimension(vanillaDestination.getWorld())) {
            return;
        }

        final @Nullable Block portalSeed = this.findNearestPortalBlock(vanillaDestination);
        if (portalSeed == null) {
            return;
        }

        this.portalRegistry.findOrDiscover(portalSeed)
                .filter(CustomPortalBounds::exceedsVanillaInteriorLimit)
                .flatMap(bounds -> bounds.findSafeDestination(
                        vanillaDestination.getX(),
                        vanillaDestination.getZ(),
                        new BukkitSafety(vanillaDestination.getWorld(), bounds.axis())
                ))
                .ifPresent(destination -> {
                    final Location safeDestination = vanillaDestination.clone();
                    safeDestination.setX(destination.x());
                    safeDestination.setY(destination.y());
                    safeDestination.setZ(destination.z());
                    event.setTo(safeDestination);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        if (!this.plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) {
            return;
        }

        final PortalSettings settings = this.readSettings();
        final Block brokenFrame = event.getBlock();
        if (!settings.isPortalFrame(brokenFrame)) {
            return;
        }

        final List<CustomPortalBounds> touchingPortals = this.portalRegistry.findPortalsTouchingFrame(brokenFrame);
        if (touchingPortals.isEmpty()) {
            return;
        }

        // Wait for Paper to perform its normal frame break and portal updates.
        // The custom path only cleans up oversized portal blocks left behind.
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (event.isCancelled() || settings.isPortalFrame(brokenFrame)) {
                return;
            }

            boolean collapseQueued = false;
            for (final CustomPortalBounds bounds : touchingPortals) {
                if (bounds.exceedsVanillaInteriorLimit()) {
                    final boolean enqueued = this.enqueuePortalCollapse(
                            brokenFrame.getWorld(), bounds, brokenFrame, settings);
                    collapseQueued |= enqueued;
                    if (!enqueued) {
                        this.portalRegistry.unregister(brokenFrame.getWorld(), bounds);
                    }
                } else {
                    // Normal portals remain Paper-managed; only discard their
                    // now-stale cached membership after the permitted break.
                    this.portalRegistry.unregister(brokenFrame.getWorld(), bounds);
                }
            }
            if (collapseQueued) {
                this.startPortalCollapseQueue();
            }
        }, 1L);
    }

    private @Nullable PortalAxis findPortalAxis(final World world, final Block source, final PortalSettings settings) {
        for (final PortalAxis axis : PortalAxis.values()) {
            if (axis.isEnclosedOn(world, source.getLocation(), settings)) {
                return axis;
            }
        }
        return null;
    }

    private @Nullable Block findNearestPortalBlock(final Location destination) {
        final World world = destination.getWorld();
        final int originX = destination.getBlockX();
        final int originY = destination.getBlockY();
        final int originZ = destination.getBlockZ();
        final int minY = Math.max(world.getMinHeight(), originY - DESTINATION_PORTAL_SEARCH_RADIUS);
        final int maxY = Math.min(world.getMaxHeight() - 1, originY + DESTINATION_PORTAL_SEARCH_RADIUS);
        Block closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (int x = originX - DESTINATION_PORTAL_SEARCH_RADIUS;
             x <= originX + DESTINATION_PORTAL_SEARCH_RADIUS; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = originZ - DESTINATION_PORTAL_SEARCH_RADIUS;
                     z <= originZ + DESTINATION_PORTAL_SEARCH_RADIUS; z++) {
                    final Block candidate = world.getBlockAt(x, y, z);
                    if (CustomPortalRegistry.axisOf(candidate) == null) {
                        continue;
                    }

                    final double deltaX = x + 0.5 - destination.getX();
                    final double deltaY = y - destination.getY();
                    final double deltaZ = z + 0.5 - destination.getZ();
                    final double distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                    if (distance < closestDistance) {
                        closest = candidate;
                        closestDistance = distance;
                    }
                }
            }
        }
        return closest;
    }

    private boolean enqueuePortalCollapse(
            final World world,
            final CustomPortalBounds bounds,
            final Block brokenFrame,
            final PortalSettings settings
    ) {
        if (this.collapsingPortals.stream().anyMatch(existing -> existing.overlaps(bounds))) {
            return false;
        }
        this.collapsingPortals.add(bounds);

        final List<CustomPortalBounds.BlockPosition> orderedBlocks = new ArrayList<>(bounds.blocks());
        orderedBlocks.sort(PORTAL_COLLAPSE_ORDER);
        this.pendingPortalCollapses.addLast(new PendingPortalCollapse(
                world, bounds, brokenFrame, settings, new ArrayDeque<>(orderedBlocks)));
        return true;
    }

    private void startPortalCollapseQueue() {
        if (this.portalCollapseTaskScheduled || this.pendingPortalCollapses.isEmpty()) {
            return;
        }
        this.portalCollapseTaskScheduled = true;
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, this::processPortalCollapseBatch, 1L);
    }

    private void processPortalCollapseBatch() {
        int remainingBudget = PORTAL_COLLAPSE_BATCH_SIZE;
        while (remainingBudget > 0 && !this.pendingPortalCollapses.isEmpty()) {
            final PendingPortalCollapse collapse = this.pendingPortalCollapses.getFirst();
            if (collapse.settings.isPortalFrame(collapse.brokenFrame)) {
                collapse.aborted = true;
            }

            while (remainingBudget > 0 && !collapse.remainingBlocks.isEmpty()) {
                final CustomPortalBounds.BlockPosition position = collapse.remainingBlocks.removeFirst();
                this.portalRegistry.forget(collapse.world, position);
                final Block block = collapse.world.getBlockAt(position.x(), position.y(), position.z());
                if (!collapse.aborted && CustomPortalRegistry.axisOf(block) == collapse.bounds.axis()) {
                    block.setType(Material.AIR, false);
                }
                remainingBudget--;
            }

            if (collapse.remainingBlocks.isEmpty()) {
                this.pendingPortalCollapses.removeFirst();
                this.collapsingPortals.remove(collapse.bounds);
            }
        }

        if (this.pendingPortalCollapses.isEmpty()) {
            this.portalCollapseTaskScheduled = false;
            return;
        }
        this.plugin.getServer().getScheduler().runTaskLater(this.plugin, this::processPortalCollapseBatch, 1L);
    }

    private static final class PendingPortalCollapse {
        private final World world;
        private final CustomPortalBounds bounds;
        private final Block brokenFrame;
        private final PortalSettings settings;
        private final Deque<CustomPortalBounds.BlockPosition> remainingBlocks;
        private boolean aborted;

        private PendingPortalCollapse(
                final World world,
                final CustomPortalBounds bounds,
                final Block brokenFrame,
                final PortalSettings settings,
                final Deque<CustomPortalBounds.BlockPosition> remainingBlocks
        ) {
            this.world = world;
            this.bounds = bounds;
            this.brokenFrame = brokenFrame;
            this.settings = settings;
            this.remainingBlocks = remainingBlocks;
        }
    }

    private record BukkitSafety(World world, PortalAxis axis) implements CustomPortalBounds.Safety {
        @Override
        public boolean isPortal(final CustomPortalBounds.BlockPosition block) {
            return CustomPortalRegistry.axisOf(this.world.getBlockAt(block.x(), block.y(), block.z())) == this.axis;
        }

        @Override
        public boolean isPassable(final CustomPortalBounds.BlockPosition block) {
            return this.world.getBlockAt(block.x(), block.y(), block.z()).isPassable();
        }

        @Override
        public boolean hasSupport(final CustomPortalBounds.BlockPosition feet) {
            final Block support = this.world.getBlockAt(feet.x(), feet.y() - 1, feet.z());
            return !support.isPassable() && !support.getCollisionShape().getBoundingBoxes().isEmpty();
        }
    }

    private PortalSettings readSettings() {
        PortalSettings settings = this.cachedSettings;
        if (settings == null) {
            settings = new PortalSettings(
                    this.readFrameMaterials(),
                    clamp(this.plugin.getConfig().getInt("tweaks.custom-nether-portals.size.min-portal-blocks", 6)),
                    clamp(this.plugin.getConfig().getInt("tweaks.custom-nether-portals.size.max-portal-width", 23)),
                    clamp(this.plugin.getConfig().getInt("tweaks.custom-nether-portals.size.max-portal-height", 23))
            );
            this.cachedSettings = settings;
        }
        return settings;
    }

    private static int clamp(final int value) {
        return Math.max(1, Math.min(MAX_DIMENSION, value));
    }

    private Set<Material> readFrameMaterials() {
        final List<String> names = this.plugin.getConfig().getStringList("tweaks.custom-nether-portals.frame-materials");
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
