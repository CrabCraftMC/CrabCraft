package crabcraft.net.crabUtilities.netherportals

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Flood-fills the air enclosed by a portal frame. Complete enclosed regions
 * within the configured size limits become portal blocks, including irregular
 * shapes. Ported from PaperTweaks' CustomNetherPortals module and adapted to
 * read CrabUtilities' modules/tweaks.yml settings.
 */
class PortalShapeFinder(
    private val plugin: Plugin, first: Block, private val axis: PortalAxis,
    private val settings: PortalSettings, private val registry: CustomPortalRegistry,
    private val creator: Entity?
) {
    // Weakly-consistent sets tolerate adding interior blocks during iteration.
    // All operations still run on the main thread.
    private val portalInterior = ConcurrentHashMap.newKeySet<Block>().apply { add(first) }
    private val checkedLocations = ConcurrentHashMap.newKeySet<Long>()

    /** Schedules block placement only for enclosed regions within the size limits. */
    fun start(): Boolean {
        var enclosed = true
        while (portalInterior.size <= settings.maxPortalHeight() * settings.maxPortalWidth()
            && checkedLocations.size < portalInterior.size) {
            val interiorIter = portalInterior.iterator()
            while (interiorIter.hasNext()) {
                val currentBlock = interiorIter.next()
                if (checkedLocations.contains(toLong(currentBlock))) continue
                checkedLocations.add(toLong(currentBlock))
                if (!checkSurrounding(currentBlock)) {
                    // A neighbour was neither replaceable air nor frame, so the region leaks.
                    enclosed = false
                    break
                }
            }
            if (!enclosed) break
        }
        if (enclosed && portalInterior.size >= settings.minPortalSize()) {
            val maxY = portalInterior.maxOf { it.y }
            val minY = portalInterior.minOf { it.y }
            if (maxY - minY > settings.maxPortalHeight()) return false
            val flatFunction: (Block) -> Int = if (axis == PortalAxis.X) ({ it.x }) else ({ it.z })
            val maxFlat = portalInterior.maxOf(flatFunction)
            val minFlat = portalInterior.minOf(flatFunction)
            if (maxFlat - minFlat > settings.maxPortalWidth()) return false
            // Defer changes a tick so the ignite event does not mutate the world.
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val world = portalInterior.iterator().next().world
                val proposedStates = ArrayList<BlockState>(portalInterior.size)
                portalInterior.forEach { block ->
                    val state = block.state
                    state.type = Material.NETHER_PORTAL
                    axis.applyTo(state)
                    proposedStates.add(state)
                }
                if (fireAndApplyPortal(proposedStates, world, creator) { event -> plugin.server.pluginManager.callEvent(event) }
                    && portalInterior.all { CustomPortalRegistry.axisOf(it) == axis }) {
                    registry.register(world, axis, portalInterior)
                }
            }, 1L)
            return true
        }
        return false
    }

    private fun checkSurrounding(source: Block): Boolean =
        checkValidPortalInterior(source, BlockFace.UP) && checkValidPortalInterior(source, BlockFace.DOWN)
            && checkValidPortalInterior(source, axis.left) && checkValidPortalInterior(source, axis.right)

    private fun checkValidPortalInterior(source: Block, face: BlockFace): Boolean {
        val toCheck = source.getRelative(face)
        if (isReplaceable(toCheck)) { portalInterior.add(toCheck); return true }
        return settings.isPortalFrame(toCheck)
    }

    companion object {
        private fun isReplaceable(block: Block): Boolean {
            val type = block.type
            return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR || type == Material.FIRE
        }
        private fun toLong(block: Block): Long {
            val location = block.location
            return ((location.blockX.toLong() and 67108863L) shl 38) or (location.blockY.toLong() and 4095L) or
                ((location.blockZ.toLong() and 67108863L) shl 12)
        }
        @JvmStatic fun fireAndApplyPortal(proposedStates: List<BlockState>, world: World, creator: Entity?,
                                         eventDispatcher: Consumer<PortalCreateEvent>): Boolean {
            val event = PortalCreateEvent(proposedStates, world, creator, PortalCreateEvent.CreateReason.FIRE)
            eventDispatcher.accept(event)
            if (event.isCancelled) return false
            proposedStates.forEach { it.update(true, false) }
            return true
        }
    }
}
