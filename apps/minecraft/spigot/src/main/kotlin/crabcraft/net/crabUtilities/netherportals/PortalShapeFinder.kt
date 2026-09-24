package crabcraft.net.crabUtilities.netherportals

import java.util.ArrayDeque
import java.util.function.Consumer
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.plugin.Plugin

/**
 * Flood-fills the enclosed air on the portal plane, supporting arbitrary shapes. Adapted from PaperTweaks'
 * CustomNetherPortals module; placement waits one tick to avoid changing the world inside the ignite event.
 */
class PortalShapeFinder(
    private val plugin: Plugin,
    first: Block,
    private val axis: PortalAxis,
    private val settings: PortalSettings,
    private val registry: CustomPortalRegistry,
    private val creator: Entity?,
) {
    private val portalInterior = hashSetOf(first)

    fun start(): Boolean {
        val pending = ArrayDeque(portalInterior)
        val first = pending.first
        var minY = first.y
        var maxY = minY
        var minFlat = flat(first)
        var maxFlat = minFlat
        val blockBudget = settings.maxPortalHeight() * settings.maxPortalWidth()
        val neighbours = listOf(BlockFace.UP, BlockFace.DOWN, axis.left, axis.right)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            minY = minOf(minY, current.y)
            maxY = maxOf(maxY, current.y)
            minFlat = minOf(minFlat, flat(current))
            maxFlat = maxOf(maxFlat, flat(current))
            if (
                portalInterior.size > blockBudget ||
                    maxY - minY > settings.maxPortalHeight() ||
                    maxFlat - minFlat > settings.maxPortalWidth()
            )
                return false
            for (face in neighbours) {
                val neighbour = current.getRelative(face)
                if (isReplaceable(neighbour)) {
                    if (portalInterior.add(neighbour)) pending.addLast(neighbour)
                } else if (!settings.isPortalFrame(neighbour)) return false
            }
        }
        if (portalInterior.size < settings.minPortalSize()) return false
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                val world = portalInterior.first().world
                val proposedStates = ArrayList<BlockState>(portalInterior.size)
                portalInterior.forEach { block ->
                    val state = block.state
                    state.type = Material.NETHER_PORTAL
                    axis.applyTo(state)
                    proposedStates.add(state)
                }
                if (
                    fireAndApplyPortal(proposedStates, world, creator) { plugin.server.pluginManager.callEvent(it) } &&
                        portalInterior.all { CustomPortalRegistry.axisOf(it) == axis }
                ) {
                    registry.register(world, axis, portalInterior)
                }
            },
            1L,
        )
        return true
    }

    private fun flat(block: Block) = if (axis == PortalAxis.X) block.x else block.z

    companion object {
        private fun isReplaceable(block: Block): Boolean =
            when (block.type) {
                Material.AIR,
                Material.CAVE_AIR,
                Material.VOID_AIR,
                Material.FIRE -> true
                else -> false
            }

        @JvmStatic
        fun fireAndApplyPortal(
            proposedStates: List<BlockState>,
            world: World,
            creator: Entity?,
            eventDispatcher: Consumer<PortalCreateEvent>,
        ): Boolean {
            val event = PortalCreateEvent(proposedStates, world, creator, PortalCreateEvent.CreateReason.FIRE)
            eventDispatcher.accept(event)
            if (event.isCancelled) return false
            proposedStates.forEach { it.update(true, false) }
            return true
        }
    }
}
