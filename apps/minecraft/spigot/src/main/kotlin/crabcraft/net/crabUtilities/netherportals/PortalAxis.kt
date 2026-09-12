package crabcraft.net.crabUtilities.netherportals

import org.bukkit.Axis
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.Orientable
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector

/** The two horizontal planes a Nether portal can occupy. */
enum class PortalAxis(
    @JvmField val axis: Axis, @JvmField val positive: Vector, @JvmField val negative: Vector,
    @JvmField val left: BlockFace, @JvmField val right: BlockFace
) {
    X(Axis.X, Vector(1, 0, 0), Vector(-1, 0, 0), BlockFace.EAST, BlockFace.WEST),
    Z(Axis.Z, Vector(0, 0, 1), Vector(0, 0, -1), BlockFace.NORTH, BlockFace.SOUTH);

    fun isInPlane(face: BlockFace): Boolean = face == BlockFace.UP || face == BlockFace.DOWN || face == left || face == right

    /** Checks that frame blocks enclose both directions within the configured width. */
    fun isEnclosedOn(world: World, source: Location, settings: PortalSettings): Boolean {
        val positiveHit = world.rayTraceBlocks(source, positive, settings.maxPortalWidth().toDouble(), FluidCollisionMode.ALWAYS, false)
        val negativeHit = world.rayTraceBlocks(source, negative, settings.maxPortalWidth().toDouble(), FluidCollisionMode.ALWAYS, false)
        return isFrameHit(positiveHit, settings) && isFrameHit(negativeHit, settings)
    }

    fun applyTo(state: BlockState) {
        val data = state.blockData
        if (data is Orientable && data.axis != axis) {
            data.axis = axis
            state.blockData = data
        }
    }

    companion object {
        @JvmStatic fun from(axis: Axis): PortalAxis? = when (axis) { Axis.X -> X; Axis.Z -> Z; else -> null }
        private fun isFrameHit(result: RayTraceResult?, settings: PortalSettings): Boolean =
            result != null && settings.isPortalFrame(result.hitBlock)
    }
}
