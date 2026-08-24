package crabcraft.net.crabUtilities.netherportals;

import org.bukkit.Axis;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The two horizontal axes a nether portal can lie on. A portal is oriented on
 * the axis whose two opposite horizontal directions both hit a frame block from
 * the ignited block, giving the {@link PortalShapeFinder} the plane to flood the
 * interior in and the orientation to stamp onto the placed portal blocks.
 */
enum PortalAxis {

    X(Axis.X, new Vector(1, 0, 0), new Vector(-1, 0, 0), BlockFace.EAST, BlockFace.WEST),
    Z(Axis.Z, new Vector(0, 0, 1), new Vector(0, 0, -1), BlockFace.NORTH, BlockFace.SOUTH);

    final Axis axis;
    final Vector positive;
    final Vector negative;
    final BlockFace left;
    final BlockFace right;

    PortalAxis(final Axis axis, final Vector positive, final Vector negative, final BlockFace left, final BlockFace right) {
        this.axis = axis;
        this.positive = positive;
        this.negative = negative;
        this.left = left;
        this.right = right;
    }

    static @Nullable PortalAxis from(final Axis axis) {
        return switch (axis) {
            case X -> X;
            case Z -> Z;
            default -> null;
        };
    }

    boolean isInPlane(final BlockFace face) {
        return face == BlockFace.UP || face == BlockFace.DOWN || face == this.left || face == this.right;
    }

    /**
     * True when a frame block is reached in both directions along this axis
     * within the configured max width — i.e. the ignited block sits between two
     * walls on this axis.
     */
    boolean isEnclosedOn(final World world, final Location source, final PortalSettings settings) {
        final @Nullable RayTraceResult positiveHit =
                world.rayTraceBlocks(source, this.positive, settings.maxPortalWidth(), FluidCollisionMode.ALWAYS, false);
        final @Nullable RayTraceResult negativeHit =
                world.rayTraceBlocks(source, this.negative, settings.maxPortalWidth(), FluidCollisionMode.ALWAYS, false);
        return isFrameHit(positiveHit, settings) && isFrameHit(negativeHit, settings);
    }

    private static boolean isFrameHit(final @Nullable RayTraceResult result, final PortalSettings settings) {
        return result != null && settings.isPortalFrame(result.getHitBlock());
    }

    void applyTo(final BlockState state) {
        final BlockData data = state.getBlockData();
        if (data instanceof final Orientable orientable && orientable.getAxis() != this.axis) {
            orientable.setAxis(this.axis);
            state.setBlockData(orientable);
        }
    }
}
