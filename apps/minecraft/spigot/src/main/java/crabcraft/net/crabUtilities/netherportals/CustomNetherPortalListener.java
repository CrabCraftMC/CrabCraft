package crabcraft.net.crabUtilities.netherportals;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityPortalExitEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private static final int MAX_RIDING_STACK_ENTITIES = 64;
    private static final double MAX_RIDING_STACK_SPAN = 16.0;
    private static final double MAX_RIDING_STACK_VOLUME = 512.0;
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
    private final Set<UUID> pendingNetherPortalExits = new HashSet<>();
    private boolean entityPortalMarkerCleanupScheduled;
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

    private static boolean isNetherDimensionPair(final World from, final World to) {
        return from.getEnvironment() == World.Environment.NETHER && to.getEnvironment() == World.Environment.NORMAL
                || from.getEnvironment() == World.Environment.NORMAL && to.getEnvironment() == World.Environment.NETHER;
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

        event.setCancelled(new PortalShapeFinder(
                this.plugin,
                block,
                axis,
                settings,
                this.portalRegistry,
                event.getIgnitingEntity()).start());
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

        this.findSafePortalDestination(vanillaDestination, event.getPlayer()).ifPresent(event::setTo);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPortal(final EntityPortalEvent event) {
        if (event.getPortalType() != PortalType.NETHER
                || event.getEntity() instanceof Player
                || !this.plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) {
            return;
        }

        final UUID entityId = event.getEntity().getUniqueId();
        this.pendingNetherPortalExits.add(entityId);
        if (this.entityPortalMarkerCleanupScheduled) {
            return;
        }
        this.entityPortalMarkerCleanupScheduled = true;

        // Portal resolution and EntityPortalExitEvent are synchronous. This
        // next-tick removal only clears markers left by a later cancellation or
        // by a portal search that did not find/create an exit.
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            this.pendingNetherPortalExits.clear();
            this.entityPortalMarkerCleanupScheduled = false;
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortalExit(final EntityPortalExitEvent event) {
        final Entity entity = event.getEntity();
        if (!this.pendingNetherPortalExits.remove(entity.getUniqueId())
                || !this.plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) {
            return;
        }

        final @Nullable Location vanillaDestination = event.getTo();
        if (vanillaDestination == null
                || !isNetherDimensionPair(event.getFrom().getWorld(), vanillaDestination.getWorld())) {
            return;
        }

        this.findSafePortalDestination(vanillaDestination, entity).ifPresent(event::setTo);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.ZOMBIFIED_PIGLIN
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NETHER_PORTAL
                || !this.plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) {
            return;
        }

        final Location spawnLocation = event.getEntity().getLocation();
        if (!isInValidDimension(spawnLocation.getWorld())) {
            return;
        }

        final @Nullable Block portalSeed = this.findNearestPortalBlock(spawnLocation);
        if (portalSeed == null) {
            return;
        }

        this.portalRegistry.findOrDiscover(portalSeed)
                .filter(bounds -> shouldSuppressPortalSpawn(
                        event.getEntityType(), event.getSpawnReason(), bounds))
                .ifPresent(bounds -> event.setCancelled(true));
    }

    static boolean shouldSuppressPortalSpawn(
            final EntityType entityType,
            final CreatureSpawnEvent.SpawnReason spawnReason,
            final CustomPortalBounds bounds
    ) {
        return entityType == EntityType.ZOMBIFIED_PIGLIN
                && spawnReason == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL
                && bounds.exceedsVanillaInteriorLimit();
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

    private Optional<Location> findSafePortalDestination(
            final Location vanillaDestination,
            final Entity entity
    ) {
        final @Nullable Block portalSeed = this.findNearestPortalBlock(vanillaDestination);
        if (portalSeed == null) {
            return Optional.empty();
        }

        return this.portalRegistry.findOrDiscover(portalSeed)
                .filter(CustomPortalBounds::exceedsVanillaInteriorLimit)
                .flatMap(bounds -> BukkitSafety.create(
                                vanillaDestination.getWorld(), bounds.axis(), entity)
                        .flatMap(safety -> bounds.findSafeDestination(
                                vanillaDestination.getX(), vanillaDestination.getZ(), safety)))
                .map(destination -> {
                    final Location safeDestination = vanillaDestination.clone();
                    safeDestination.setX(destination.x());
                    safeDestination.setY(destination.y());
                    safeDestination.setZ(destination.z());
                    return safeDestination;
                });
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

    private record BukkitSafety(
            World world,
            PortalAxis axis,
            RelativeBoundingBox occupancy
    ) implements CustomPortalBounds.Safety {

        private static Optional<BukkitSafety> create(
                final World world,
                final PortalAxis axis,
                final Entity root
        ) {
            final Location rootLocation = root.getLocation();
            final BoundingBox combinedBounds = root.getBoundingBox().clone();
            final Deque<Entity> pending = new ArrayDeque<>(root.getPassengers());
            final Set<UUID> visited = new HashSet<>();
            visited.add(root.getUniqueId());

            while (!pending.isEmpty()) {
                final Entity passenger = pending.removeFirst();
                if (!visited.add(passenger.getUniqueId())) {
                    continue;
                }
                if (visited.size() > MAX_RIDING_STACK_ENTITIES) {
                    return Optional.empty();
                }

                combinedBounds.union(passenger.getBoundingBox());
                pending.addAll(passenger.getPassengers());
            }

            final RelativeBoundingBox occupancy = RelativeBoundingBox.from(combinedBounds, rootLocation);
            if (occupancy.widthX() > MAX_RIDING_STACK_SPAN
                    || occupancy.height() > MAX_RIDING_STACK_SPAN
                    || occupancy.widthZ() > MAX_RIDING_STACK_SPAN
                    || occupancy.volume() > MAX_RIDING_STACK_VOLUME) {
                return Optional.empty();
            }

            return Optional.of(new BukkitSafety(world, axis, occupancy));
        }

        @Override
        public boolean isPortal(final CustomPortalBounds.BlockPosition block) {
            return CustomPortalRegistry.axisOf(this.world.getBlockAt(block.x(), block.y(), block.z())) == this.axis;
        }

        @Override
        public boolean canOccupy(final CustomPortalBounds.Destination destination) {
            return !this.world.hasCollisionsIn(this.occupancy.at(destination));
        }

        @Override
        public boolean hasSupport(final CustomPortalBounds.BlockPosition feet) {
            final Block support = this.world.getBlockAt(feet.x(), feet.y() - 1, feet.z());
            return !support.isPassable() && !support.getCollisionShape().getBoundingBoxes().isEmpty();
        }
    }

    private record RelativeBoundingBox(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        private static RelativeBoundingBox from(final BoundingBox bounds, final Location origin) {
            // Portal exits may rotate vehicles by 90 degrees. A square
            // horizontal footprint safely covers either orientation and any
            // passenger offsets without having to predict Paper's rotation.
            final double horizontalRadius = Math.max(
                    Math.max(Math.abs(bounds.getMinX() - origin.getX()),
                            Math.abs(bounds.getMaxX() - origin.getX())),
                    Math.max(Math.abs(bounds.getMinZ() - origin.getZ()),
                            Math.abs(bounds.getMaxZ() - origin.getZ()))
            );
            return new RelativeBoundingBox(
                    -horizontalRadius,
                    bounds.getMinY() - origin.getY(),
                    -horizontalRadius,
                    horizontalRadius,
                    bounds.getMaxY() - origin.getY(),
                    horizontalRadius
            );
        }

        private double widthX() {
            return this.maxX - this.minX;
        }

        private double height() {
            return this.maxY - this.minY;
        }

        private double widthZ() {
            return this.maxZ - this.minZ;
        }

        private double volume() {
            return this.widthX() * this.height() * this.widthZ();
        }

        private BoundingBox at(final CustomPortalBounds.Destination destination) {
            return new BoundingBox(
                    destination.x() + this.minX,
                    destination.y() + this.minY,
                    destination.z() + this.minZ,
                    destination.x() + this.maxX,
                    destination.y() + this.maxY,
                    destination.z() + this.maxZ
            );
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
