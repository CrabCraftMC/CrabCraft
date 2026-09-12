package crabcraft.net.crabUtilities.netherportals

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.PortalType
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityPortalExitEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.BoundingBox
import java.util.ArrayDeque
import java.util.Collections
import java.util.EnumSet
import java.util.IdentityHashMap
import java.util.Optional
import java.util.UUID

/**
 * Supports Nether portals of custom sizes and irregular shapes, using the
 * enclosed-region search in PortalShapeFinder. Opt-in settings are refreshed
 * after module reload. Ported from PaperTweaks' CustomNetherPortals module.
 */
open class CustomNetherPortalListener(private val plugin: CrabUtilities) : Listener {
    private val portalRegistry = CustomPortalRegistry()
    private val pendingPortalCollapses = ArrayDeque<PendingPortalCollapse>()
    private val collapsingPortals = Collections.newSetFromMap(IdentityHashMap<CustomPortalBounds, Boolean>())
    private val pendingNetherPortalExits = HashSet<UUID>()
    private var entityPortalMarkerCleanupScheduled = false
    private var portalCollapseTaskScheduled = false
    // Parsed settings are rebuilt on demand after invalidate() on reload.
    @Volatile private var cachedSettings: PortalSettings? = null

    /** Drops the cached settings so the next ignite re-reads config. */
    open fun invalidate() { cachedSettings = null }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onBlockIgnite(event: BlockIgniteEvent) {
        if (!plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val block = event.block
        val world = block.world
        if (!isInValidDimension(world)) return
        val settings = readSettings()
        if (!settings.isPortalFrame(block.getRelative(BlockFace.DOWN))) return
        val axis = findPortalAxis(world, block, settings) ?: return
        event.isCancelled = PortalShapeFinder(plugin, block, axis, settings, portalRegistry, event.ignitingEntity).start()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // Paper raises PlayerPortalEvent before searching, then a separate
        // PlayerTeleportEvent after calculating the actual exit.
        if (event is PlayerPortalEvent || event.cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
            || !plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val vanillaDestination: Location? = event.to
        if (vanillaDestination == null || !isInValidDimension(vanillaDestination.world)) return
        findSafePortalDestination(vanillaDestination, event.player).ifPresent(event::setTo)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onEntityPortal(event: EntityPortalEvent) {
        if (event.portalType != PortalType.NETHER || event.entity is Player
            || !plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val entityId = event.entity.uniqueId
        pendingNetherPortalExits.add(entityId)
        if (entityPortalMarkerCleanupScheduled) return
        entityPortalMarkerCleanupScheduled = true
        // Exit resolution is synchronous. Next-tick cleanup clears markers
        // left by later cancellation or a search which found no exit.
        plugin.server.scheduler.runTask(plugin, Runnable {
            pendingNetherPortalExits.clear()
            entityPortalMarkerCleanupScheduled = false
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onEntityPortalExit(event: EntityPortalExitEvent) {
        val entity = event.entity
        if (!pendingNetherPortalExits.remove(entity.uniqueId)
            || !plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val vanillaDestination: Location? = event.to
        if (vanillaDestination == null || !isNetherDimensionPair(event.from.world, vanillaDestination.world)) return
        findSafePortalDestination(vanillaDestination, entity).ifPresent(event::setTo)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.entityType != EntityType.ZOMBIFIED_PIGLIN
            || event.spawnReason != CreatureSpawnEvent.SpawnReason.NETHER_PORTAL
            || !plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val spawnLocation = event.entity.location
        if (!isInValidDimension(spawnLocation.world)) return
        val portalSeed = findNearestPortalBlock(spawnLocation) ?: return
        portalRegistry.findOrDiscover(portalSeed)
            .filter { shouldSuppressPortalSpawn(event.entityType, event.spawnReason, it) }
            .ifPresent { event.isCancelled = true }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onBlockBreak(event: BlockBreakEvent) {
        if (!plugin.getConfig().getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val settings = readSettings()
        val brokenFrame = event.block
        if (!settings.isPortalFrame(brokenFrame)) return
        val touchingPortals = portalRegistry.findPortalsTouchingFrame(brokenFrame)
        if (touchingPortals.isEmpty()) return
        // Wait for Paper's normal break and updates before removing oversized remnants.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (event.isCancelled || settings.isPortalFrame(brokenFrame)) return@Runnable
            var collapseQueued = false
            for (bounds in touchingPortals) {
                if (bounds.exceedsVanillaInteriorLimit()) {
                    val enqueued = enqueuePortalCollapse(brokenFrame.world, bounds, brokenFrame, settings)
                    collapseQueued = collapseQueued or enqueued
                    if (!enqueued) portalRegistry.unregister(brokenFrame.world, bounds)
                } else {
                    // Normal portals stay Paper-managed; discard stale membership.
                    portalRegistry.unregister(brokenFrame.world, bounds)
                }
            }
            if (collapseQueued) startPortalCollapseQueue()
        }, 1L)
    }

    private fun findPortalAxis(world: World, source: Block, settings: PortalSettings): PortalAxis? {
        for (axis in PortalAxis.values()) if (axis.isEnclosedOn(world, source.location, settings)) return axis
        return null
    }

    private fun findSafePortalDestination(vanillaDestination: Location, entity: Entity): Optional<Location> {
        val portalSeed = findNearestPortalBlock(vanillaDestination) ?: return Optional.empty()
        return portalRegistry.findOrDiscover(portalSeed).filter(CustomPortalBounds::exceedsVanillaInteriorLimit)
            .flatMap { bounds -> BukkitSafety.create(vanillaDestination.world, bounds.axis(), entity)
                .flatMap { safety -> bounds.findSafeDestination(vanillaDestination.x, vanillaDestination.z, safety) } }
            .map { destination -> vanillaDestination.clone().apply {
                x = destination.x(); y = destination.y(); z = destination.z()
            } }
    }

    private fun findNearestPortalBlock(destination: Location): Block? {
        val world = destination.world
        val originX = destination.blockX
        val originY = destination.blockY
        val originZ = destination.blockZ
        val minY = Math.max(world.minHeight, originY - DESTINATION_PORTAL_SEARCH_RADIUS)
        val maxY = Math.min(world.maxHeight - 1, originY + DESTINATION_PORTAL_SEARCH_RADIUS)
        var closest: Block? = null
        var closestDistance = Double.MAX_VALUE
        for (x in originX - DESTINATION_PORTAL_SEARCH_RADIUS..originX + DESTINATION_PORTAL_SEARCH_RADIUS) {
            for (y in minY..maxY) {
                for (z in originZ - DESTINATION_PORTAL_SEARCH_RADIUS..originZ + DESTINATION_PORTAL_SEARCH_RADIUS) {
                    val candidate = world.getBlockAt(x, y, z)
                    if (CustomPortalRegistry.axisOf(candidate) == null) continue
                    val deltaX = x + 0.5 - destination.x
                    val deltaY = y - destination.y
                    val deltaZ = z + 0.5 - destination.z
                    val distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    if (distance < closestDistance) { closest = candidate; closestDistance = distance }
                }
            }
        }
        return closest
    }

    private fun enqueuePortalCollapse(world: World, bounds: CustomPortalBounds, brokenFrame: Block, settings: PortalSettings): Boolean {
        if (collapsingPortals.any { it.overlaps(bounds) }) return false
        collapsingPortals.add(bounds)
        val orderedBlocks = ArrayList(bounds.blocks())
        orderedBlocks.sortWith(PORTAL_COLLAPSE_ORDER)
        pendingPortalCollapses.addLast(PendingPortalCollapse(world, bounds, brokenFrame, settings, ArrayDeque(orderedBlocks)))
        return true
    }

    private fun startPortalCollapseQueue() {
        if (portalCollapseTaskScheduled || pendingPortalCollapses.isEmpty()) return
        portalCollapseTaskScheduled = true
        plugin.server.scheduler.runTaskLater(plugin, Runnable(::processPortalCollapseBatch), 1L)
    }

    private fun processPortalCollapseBatch() {
        var remainingBudget = PORTAL_COLLAPSE_BATCH_SIZE
        while (remainingBudget > 0 && !pendingPortalCollapses.isEmpty()) {
            val collapse = pendingPortalCollapses.first
            if (collapse.settings.isPortalFrame(collapse.brokenFrame)) collapse.aborted = true
            while (remainingBudget > 0 && !collapse.remainingBlocks.isEmpty()) {
                val position = collapse.remainingBlocks.removeFirst()
                portalRegistry.forget(collapse.world, position)
                val block = collapse.world.getBlockAt(position.x(), position.y(), position.z())
                if (!collapse.aborted && CustomPortalRegistry.axisOf(block) == collapse.bounds.axis()) block.setType(Material.AIR, false)
                remainingBudget--
            }
            if (collapse.remainingBlocks.isEmpty()) {
                pendingPortalCollapses.removeFirst()
                collapsingPortals.remove(collapse.bounds)
            }
        }
        if (pendingPortalCollapses.isEmpty()) { portalCollapseTaskScheduled = false; return }
        plugin.server.scheduler.runTaskLater(plugin, Runnable(::processPortalCollapseBatch), 1L)
    }

    private class PendingPortalCollapse(val world: World, val bounds: CustomPortalBounds,
        val brokenFrame: Block, val settings: PortalSettings,
        val remainingBlocks: ArrayDeque<CustomPortalBounds.BlockPosition>) {
        var aborted = false
    }

    private data class BukkitSafety(val world: World, val axis: PortalAxis, val occupancy: RelativeBoundingBox) : CustomPortalBounds.Safety {
        override fun isPortal(block: CustomPortalBounds.BlockPosition): Boolean =
            CustomPortalRegistry.axisOf(world.getBlockAt(block.x(), block.y(), block.z())) == axis
        override fun canOccupy(destination: CustomPortalBounds.Destination): Boolean = !world.hasCollisionsIn(occupancy.at(destination))
        override fun hasSupport(feet: CustomPortalBounds.BlockPosition): Boolean {
            val support = world.getBlockAt(feet.x(), feet.y() - 1, feet.z())
            return !support.isPassable && !support.collisionShape.boundingBoxes.isEmpty()
        }
        companion object {
            fun create(world: World, axis: PortalAxis, root: Entity): Optional<BukkitSafety> {
                val rootLocation = root.location
                val combinedBounds = root.boundingBox.clone()
                val pending = ArrayDeque(root.passengers)
                val visited = HashSet<UUID>()
                visited.add(root.uniqueId)
                while (!pending.isEmpty()) {
                    val passenger = pending.removeFirst()
                    if (!visited.add(passenger.uniqueId)) continue
                    if (visited.size > MAX_RIDING_STACK_ENTITIES) return Optional.empty()
                    combinedBounds.union(passenger.boundingBox)
                    pending.addAll(passenger.passengers)
                }
                val occupancy = RelativeBoundingBox.from(combinedBounds, rootLocation)
                if (occupancy.widthX() > MAX_RIDING_STACK_SPAN || occupancy.height() > MAX_RIDING_STACK_SPAN
                    || occupancy.widthZ() > MAX_RIDING_STACK_SPAN || occupancy.volume() > MAX_RIDING_STACK_VOLUME) return Optional.empty()
                return Optional.of(BukkitSafety(world, axis, occupancy))
            }
        }
    }

    private data class RelativeBoundingBox(val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double) {
        fun widthX(): Double = maxX - minX
        fun height(): Double = maxY - minY
        fun widthZ(): Double = maxZ - minZ
        fun volume(): Double = widthX() * height() * widthZ()
        fun at(destination: CustomPortalBounds.Destination): BoundingBox = BoundingBox(
            destination.x() + minX, destination.y() + minY, destination.z() + minZ,
            destination.x() + maxX, destination.y() + maxY, destination.z() + maxZ)
        companion object {
            fun from(bounds: BoundingBox, origin: Location): RelativeBoundingBox {
                // A square footprint covers portal rotations and passenger offsets.
                val horizontalRadius = Math.max(
                    Math.max(Math.abs(bounds.minX - origin.x), Math.abs(bounds.maxX - origin.x)),
                    Math.max(Math.abs(bounds.minZ - origin.z), Math.abs(bounds.maxZ - origin.z)))
                return RelativeBoundingBox(-horizontalRadius, bounds.minY - origin.y, -horizontalRadius,
                    horizontalRadius, bounds.maxY - origin.y, horizontalRadius)
            }
        }
    }

    private fun readSettings(): PortalSettings {
        var settings = cachedSettings
        if (settings == null) {
            settings = PortalSettings(readFrameMaterials(),
                clamp(plugin.getConfig().getInt("tweaks.custom-nether-portals.size.min-portal-blocks", 6)),
                clamp(plugin.getConfig().getInt("tweaks.custom-nether-portals.size.max-portal-width", 23)),
                clamp(plugin.getConfig().getInt("tweaks.custom-nether-portals.size.max-portal-height", 23)))
            cachedSettings = settings
        }
        return settings
    }

    private fun readFrameMaterials(): Set<Material> {
        val names = plugin.getConfig().getStringList("tweaks.custom-nether-portals.frame-materials")
        val materials = EnumSet.noneOf(Material::class.java)
        for (name in names) {
            val material = Material.matchMaterial(name)
            if (material != null) materials.add(material)
            else plugin.logger.warning("Unknown custom-nether-portals frame material: " + name)
        }
        if (materials.isEmpty()) { materials.add(Material.OBSIDIAN); materials.add(Material.CRYING_OBSIDIAN) }
        return materials
    }

    companion object {
        // Bound the flood-fill budget and riding-stack collision work.
        private const val MAX_DIMENSION = 256
        private const val MAX_RIDING_STACK_ENTITIES = 64
        private const val MAX_RIDING_STACK_SPAN = 16.0
        private const val MAX_RIDING_STACK_VOLUME = 512.0
        private const val DESTINATION_PORTAL_SEARCH_RADIUS = 3
        private const val PORTAL_COLLAPSE_BATCH_SIZE = 4096
        private val PORTAL_COLLAPSE_ORDER = compareBy<CustomPortalBounds.BlockPosition> { it.x() shr 4 }
            .thenBy { it.z() shr 4 }.thenBy { it.y() }.thenBy { it.x() }.thenBy { it.z() }
        private fun isInValidDimension(world: World): Boolean = world.environment == World.Environment.NETHER || world.environment == World.Environment.NORMAL
        private fun isNetherDimensionPair(from: World, to: World): Boolean =
            from.environment == World.Environment.NETHER && to.environment == World.Environment.NORMAL ||
                from.environment == World.Environment.NORMAL && to.environment == World.Environment.NETHER
        @JvmStatic fun shouldSuppressPortalSpawn(entityType: EntityType, spawnReason: CreatureSpawnEvent.SpawnReason,
                                                bounds: CustomPortalBounds): Boolean =
            entityType == EntityType.ZOMBIFIED_PIGLIN && spawnReason == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL && bounds.exceedsVanillaInteriorLimit()
        private fun clamp(value: Int): Int = Math.max(1, Math.min(MAX_DIMENSION, value))
    }
}
