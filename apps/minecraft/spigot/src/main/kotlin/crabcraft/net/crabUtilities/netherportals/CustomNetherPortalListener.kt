package crabcraft.net.crabUtilities.netherportals

import crabcraft.net.crabUtilities.CrabUtilities
import java.util.ArrayDeque
import java.util.Collections
import java.util.EnumSet
import java.util.IdentityHashMap
import java.util.Optional
import java.util.UUID
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

/**
 * Opt-in support for portals of custom sizes and non-rectangular shapes. Adapted from PaperTweaks' CustomNetherPortals
 * module. Settings are cached until reload; the custom path handles oversized destinations and collapse.
 */
open class CustomNetherPortalListener(private val plugin: CrabUtilities) : Listener {
    private val portalRegistry = CustomPortalRegistry()
    private val pendingPortalCollapses = ArrayDeque<PendingPortalCollapse>()
    private val collapsingPortals = Collections.newSetFromMap(IdentityHashMap<CustomPortalBounds, Boolean>())
    private val pendingNetherPortalExits = HashSet<UUID>()
    private var entityPortalMarkerCleanupScheduled = false
    private var portalCollapseTaskScheduled = false
    @Volatile private var cachedSettings: PortalSettings? = null

    open fun invalidate() {
        cachedSettings = null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onBlockIgnite(event: BlockIgniteEvent) {
        if (!plugin.config.getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val block = event.block
        val world = block.world
        if (!isInValidDimension(world)) return
        val settings = readSettings()
        if (!settings.isPortalFrame(block.getRelative(BlockFace.DOWN))) return
        val axis = findPortalAxis(world, block, settings) ?: return
        event.isCancelled =
            PortalShapeFinder(plugin, block, axis, settings, portalRegistry, event.ignitingEntity).start()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // PlayerPortalEvent precedes the exit search; the separate teleport event has the actual exit.
        if (
            event is PlayerPortalEvent ||
                event.cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL ||
                !plugin.config.getBoolean("tweaks.custom-nether-portals.enabled", false)
        )
            return
        val destination = event.to ?: return
        if (!isInValidDimension(destination.world!!)) return
        findSafePortalDestination(destination, event.player).ifPresent(event::setTo)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onEntityPortal(event: EntityPortalEvent) {
        if (
            event.portalType != PortalType.NETHER ||
                event.entity is Player ||
                !plugin.config.getBoolean("tweaks.custom-nether-portals.enabled", false)
        )
            return
        pendingNetherPortalExits.add(event.entity.uniqueId)
        if (entityPortalMarkerCleanupScheduled) return
        entityPortalMarkerCleanupScheduled = true
        // Resolution is synchronous; next-tick cleanup discards cancelled or unresolved searches.
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                pendingNetherPortalExits.clear()
                entityPortalMarkerCleanupScheduled = false
            },
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onEntityPortalExit(event: EntityPortalExitEvent) {
        val entity = event.entity
        if (
            !pendingNetherPortalExits.remove(entity.uniqueId) ||
                !plugin.config.getBoolean("tweaks.custom-nether-portals.enabled", false)
        )
            return
        val destination = event.to ?: return
        if (!isNetherDimensionPair(event.from.world!!, destination.world!!)) return
        findSafePortalDestination(destination, entity).ifPresent(event::setTo)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (
            event.entityType != EntityType.ZOMBIFIED_PIGLIN ||
                event.spawnReason != CreatureSpawnEvent.SpawnReason.NETHER_PORTAL ||
                !plugin.config.getBoolean("tweaks.custom-nether-portals.enabled", false)
        )
            return
        val location = event.entity.location
        if (!isInValidDimension(location.world!!)) return
        val seed = findNearestPortalBlock(location) ?: return
        portalRegistry
            .findOrDiscover(seed)
            .filter { shouldSuppressPortalSpawn(event.entityType, event.spawnReason, it) }
            .ifPresent { event.isCancelled = true }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onBlockBreak(event: BlockBreakEvent) {
        if (!plugin.config.getBoolean("tweaks.custom-nether-portals.enabled", false)) return
        val settings = readSettings()
        val brokenFrame = event.block
        if (!settings.isPortalFrame(brokenFrame)) return
        val touchingPortals = portalRegistry.findPortalsTouchingFrame(brokenFrame)
        if (touchingPortals.isEmpty()) return
        // Let Paper perform its normal frame break before cleaning up oversized leftovers.
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (event.isCancelled || settings.isPortalFrame(brokenFrame)) return@Runnable
                var collapseQueued = false
                for (bounds in touchingPortals) {
                    if (bounds.exceedsVanillaInteriorLimit()) {
                        val enqueued = enqueuePortalCollapse(brokenFrame.world, bounds, brokenFrame, settings)
                        collapseQueued = collapseQueued or enqueued
                        if (!enqueued) portalRegistry.unregister(brokenFrame.world, bounds)
                    } else {
                        portalRegistry.unregister(brokenFrame.world, bounds)
                    }
                }
                if (collapseQueued) startPortalCollapseQueue()
            },
            1L,
        )
    }

    private fun findPortalAxis(world: World, source: Block, settings: PortalSettings): PortalAxis? {
        for (axis in PortalAxis.entries) if (axis.isEnclosedOn(world, source.location, settings)) return axis
        return null
    }

    private fun findSafePortalDestination(destination: Location, entity: Entity): Optional<Location> {
        val seed = findNearestPortalBlock(destination) ?: return Optional.empty()
        return portalRegistry
            .findOrDiscover(seed)
            .filter(CustomPortalBounds::exceedsVanillaInteriorLimit)
            .flatMap { bounds ->
                BukkitSafety.create(destination.world!!, bounds.axis(), entity).flatMap {
                    bounds.findSafeDestination(destination.x, destination.z, it)
                }
            }
            .map { safe ->
                destination.clone().apply {
                    x = safe.x()
                    y = safe.y()
                    z = safe.z()
                }
            }
    }

    private fun findNearestPortalBlock(destination: Location): Block? {
        val world = destination.world!!
        val originX = destination.blockX
        val originY = destination.blockY
        val originZ = destination.blockZ
        val minY = maxOf(world.minHeight, originY - DESTINATION_PORTAL_SEARCH_RADIUS)
        val maxY = minOf(world.maxHeight - 1, originY + DESTINATION_PORTAL_SEARCH_RADIUS)
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
                    if (distance < closestDistance) {
                        closest = candidate
                        closestDistance = distance
                    }
                }
            }
        }
        return closest
    }

    private fun enqueuePortalCollapse(
        world: World,
        bounds: CustomPortalBounds,
        brokenFrame: Block,
        settings: PortalSettings,
    ): Boolean {
        if (collapsingPortals.any { it.overlaps(bounds) }) return false
        collapsingPortals.add(bounds)
        val orderedBlocks = ArrayList(bounds.blocks())
        orderedBlocks.sortWith(PORTAL_COLLAPSE_ORDER)
        pendingPortalCollapses.addLast(
            PendingPortalCollapse(world, bounds, brokenFrame, settings, ArrayDeque(orderedBlocks))
        )
        return true
    }

    private fun startPortalCollapseQueue() {
        if (portalCollapseTaskScheduled || pendingPortalCollapses.isEmpty()) return
        portalCollapseTaskScheduled = true
        plugin.server.scheduler.runTaskLater(plugin, Runnable(::processPortalCollapseBatch), 1L)
    }

    private fun processPortalCollapseBatch() {
        var remainingBudget = PORTAL_COLLAPSE_BATCH_SIZE
        while (remainingBudget > 0 && pendingPortalCollapses.isNotEmpty()) {
            val collapse = pendingPortalCollapses.first
            if (collapse.settings.isPortalFrame(collapse.brokenFrame)) collapse.aborted = true
            while (remainingBudget > 0 && collapse.remainingBlocks.isNotEmpty()) {
                val position = collapse.remainingBlocks.removeFirst()
                portalRegistry.forget(collapse.world, position)
                val block = collapse.world.getBlockAt(position.x(), position.y(), position.z())
                if (!collapse.aborted && CustomPortalRegistry.axisOf(block) == collapse.bounds.axis()) {
                    block.setType(Material.AIR, false)
                }
                remainingBudget--
            }
            if (collapse.remainingBlocks.isEmpty()) {
                pendingPortalCollapses.removeFirst()
                collapsingPortals.remove(collapse.bounds)
            }
        }
        if (pendingPortalCollapses.isEmpty()) {
            portalCollapseTaskScheduled = false
            return
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable(::processPortalCollapseBatch), 1L)
    }

    private class PendingPortalCollapse(
        val world: World,
        val bounds: CustomPortalBounds,
        val brokenFrame: Block,
        val settings: PortalSettings,
        val remainingBlocks: ArrayDeque<CustomPortalBounds.BlockPosition>,
        var aborted: Boolean = false,
    )

    private data class BukkitSafety(val world: World, val axis: PortalAxis, val occupancy: RelativeBoundingBox) :
        CustomPortalBounds.Safety {
        override fun isPortal(block: CustomPortalBounds.BlockPosition) =
            CustomPortalRegistry.axisOf(world.getBlockAt(block.x(), block.y(), block.z())) == axis

        override fun canOccupy(destination: CustomPortalBounds.Destination) =
            !world.hasCollisionsIn(occupancy.at(destination))

        override fun hasSupport(feet: CustomPortalBounds.BlockPosition): Boolean {
            val support = world.getBlockAt(feet.x(), feet.y() - 1, feet.z())
            return !support.isPassable && support.collisionShape.boundingBoxes.isNotEmpty()
        }

        companion object {
            fun create(world: World, axis: PortalAxis, root: Entity): Optional<BukkitSafety> {
                val rootLocation = root.location
                val combinedBounds = root.boundingBox.clone()
                val pending = ArrayDeque(root.passengers)
                val visited = hashSetOf(root.uniqueId)
                while (pending.isNotEmpty()) {
                    val passenger = pending.removeFirst()
                    if (!visited.add(passenger.uniqueId)) continue
                    if (visited.size > MAX_RIDING_STACK_ENTITIES) return Optional.empty()
                    combinedBounds.union(passenger.boundingBox)
                    pending.addAll(passenger.passengers)
                }
                val occupancy = RelativeBoundingBox.from(combinedBounds, rootLocation)
                if (
                    occupancy.widthX() > MAX_RIDING_STACK_SPAN ||
                        occupancy.height() > MAX_RIDING_STACK_SPAN ||
                        occupancy.widthZ() > MAX_RIDING_STACK_SPAN ||
                        occupancy.volume() > MAX_RIDING_STACK_VOLUME
                )
                    return Optional.empty()
                return Optional.of(BukkitSafety(world, axis, occupancy))
            }
        }
    }

    private data class RelativeBoundingBox(
        val minX: Double,
        val minY: Double,
        val minZ: Double,
        val maxX: Double,
        val maxY: Double,
        val maxZ: Double,
    ) {
        fun widthX() = maxX - minX

        fun height() = maxY - minY

        fun widthZ() = maxZ - minZ

        fun volume() = widthX() * height() * widthZ()

        fun at(destination: CustomPortalBounds.Destination) =
            BoundingBox(
                destination.x() + minX,
                destination.y() + minY,
                destination.z() + minZ,
                destination.x() + maxX,
                destination.y() + maxY,
                destination.z() + maxZ,
            )

        companion object {
            fun from(bounds: BoundingBox, origin: Location): RelativeBoundingBox {
                // A square footprint covers rotated vehicles and passenger offsets.
                val radius =
                    maxOf(
                        maxOf(kotlin.math.abs(bounds.minX - origin.x), kotlin.math.abs(bounds.maxX - origin.x)),
                        maxOf(kotlin.math.abs(bounds.minZ - origin.z), kotlin.math.abs(bounds.maxZ - origin.z)),
                    )
                return RelativeBoundingBox(
                    -radius,
                    bounds.minY - origin.y,
                    -radius,
                    radius,
                    bounds.maxY - origin.y,
                    radius,
                )
            }
        }
    }

    private fun readSettings(): PortalSettings {
        var settings = cachedSettings
        if (settings == null) {
            settings =
                PortalSettings(
                    readFrameMaterials(),
                    clamp(plugin.config.getInt("tweaks.custom-nether-portals.size.min-portal-blocks", 6)),
                    clamp(plugin.config.getInt("tweaks.custom-nether-portals.size.max-portal-width", 23)),
                    clamp(plugin.config.getInt("tweaks.custom-nether-portals.size.max-portal-height", 23)),
                )
            cachedSettings = settings
        }
        return settings
    }

    private fun readFrameMaterials(): Set<Material> {
        val materials = EnumSet.noneOf(Material::class.java)
        for (name in plugin.config.getStringList("tweaks.custom-nether-portals.frame-materials")) {
            val material = Material.matchMaterial(name)
            if (material != null) materials.add(material)
            else plugin.logger.warning("Unknown custom-nether-portals frame material: $name")
        }
        if (materials.isEmpty()) {
            materials.add(Material.OBSIDIAN)
            materials.add(Material.CRYING_OBSIDIAN)
        }
        return materials
    }

    companion object {
        // Bounds the flood budget and avoids integer overflow from absurd configuration values.
        private const val MAX_DIMENSION = 256
        private const val MAX_RIDING_STACK_ENTITIES = 64
        private const val MAX_RIDING_STACK_SPAN = 16.0
        private const val MAX_RIDING_STACK_VOLUME = 512.0
        private const val DESTINATION_PORTAL_SEARCH_RADIUS = 3
        private const val PORTAL_COLLAPSE_BATCH_SIZE = 4096
        private val PORTAL_COLLAPSE_ORDER =
            compareBy<CustomPortalBounds.BlockPosition> { it.x() shr 4 }
                .thenBy { it.z() shr 4 }
                .thenBy { it.y() }
                .thenBy { it.x() }
                .thenBy { it.z() }

        private fun isInValidDimension(world: World) =
            world.environment == World.Environment.NETHER || world.environment == World.Environment.NORMAL

        private fun isNetherDimensionPair(from: World, to: World) =
            from.environment == World.Environment.NETHER && to.environment == World.Environment.NORMAL ||
                from.environment == World.Environment.NORMAL && to.environment == World.Environment.NETHER

        private fun clamp(value: Int) = value.coerceIn(1, MAX_DIMENSION)

        @JvmStatic
        fun shouldSuppressPortalSpawn(
            entityType: EntityType,
            spawnReason: CreatureSpawnEvent.SpawnReason,
            bounds: CustomPortalBounds,
        ) =
            entityType == EntityType.ZOMBIFIED_PIGLIN &&
                spawnReason == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL &&
                bounds.exceedsVanillaInteriorLimit()
    }
}
