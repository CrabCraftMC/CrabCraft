package crabcraft.net.crabUtilities.netherportals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CustomPortalDestinationRegressionTest {

    public static void main(String[] args) {
        normalPortalLinkedToLargePortalUsesLargePortalBottom();
        netherRoofLinkWorksInBothDirections();
        bothAxesAndNegativeCoordinatesUseCompleteBounds();
        obstructedSideFallsBackToTheClearSide();
        portalCollapseExcludesVanillaSizes();
        portalCollapseOnlyAcceptsFrameBlocksInThePortalPlane();
        overlappingCollapseRequestsAreDetectable();
    }

    private static void normalPortalLinkedToLargePortalUsesLargePortalBottom() {
        final CustomPortalBounds normal = rectangle(PortalAxis.X, 10, 4, 70, 2, 3);
        final CustomPortalBounds large = rectangle(PortalAxis.X, 10, 4, 23, 64, 64);

        check(!normal.exceedsVanillaInteriorLimit(), "normal portals must retain Paper placement");
        check(large.exceedsVanillaInteriorLimit(), "64x64 portal was not recognized as custom-sized");
        check(large.minY() == 23 && large.height() == 64 && large.width() == 64,
                "complete 64x64 bounds were not retained");

        final FakeSafety safety = new FakeSafety(large);
        safety.supportInteriorBottom(large);
        final CustomPortalBounds.Destination destination = destination(large, 40.2, 86.0, safety);
        check(destination.y() == 23.0, "arrival should be directly above the lower frame");
        check(safety.hasSupport(destination.feetBlock()), "arrival must have solid support");
    }

    private static void netherRoofLinkWorksInBothDirections() {
        final CustomPortalBounds overworld = rectangle(PortalAxis.Z, -35, -80, 23, 64, 64);
        final CustomPortalBounds netherRoof = rectangle(PortalAxis.Z, -5, -10, 129, 2, 3);

        final FakeSafety overworldSafety = new FakeSafety(overworld);
        overworldSafety.supportInteriorBottom(overworld);
        final CustomPortalBounds.Destination fromNether = destination(overworld, -79.5, -6.1, overworldSafety);
        check(fromNether.y() == 23.0,
                "Nether-roof Y must not pin arrival to the top of the Overworld portal at Y=86");

        check(!netherRoof.exceedsVanillaInteriorLimit(),
                "large-source to normal Nether destination must keep vanilla-like placement");
    }

    private static void bothAxesAndNegativeCoordinatesUseCompleteBounds() {
        for (final PortalAxis axis : PortalAxis.values()) {
            final CustomPortalBounds portal = rectangle(axis, -90, -40, -12, 37, 28);
            final FakeSafety safety = new FakeSafety(portal);
            safety.supportInteriorBottom(portal);
            final double targetX = axis == PortalAxis.X ? -68.2 : -39.5;
            final double targetZ = axis == PortalAxis.X ? -39.5 : -68.2;
            final CustomPortalBounds.Destination destination = destination(portal, targetX, targetZ, safety);

            check(destination.y() == -12.0, axis + " portal did not use its complete lower edge");
            check(destination.feetBlock().x() < 0 && destination.feetBlock().z() < 0,
                    axis + " portal mishandled negative block coordinates");
            check(safety.isPortal(destination.feetBlock()),
                    axis + " fallback should remain in a portal block above its lower frame");
        }
    }

    private static void obstructedSideFallsBackToTheClearSide() {
        for (final PortalAxis axis : PortalAxis.values()) {
            final CustomPortalBounds portal = rectangle(axis, 5, 20, 40, 24, 30);
            final FakeSafety safety = new FakeSafety(portal);
            safety.supportAllBottomLanes(portal);
            safety.blockPositiveOutsideLane(portal);

            final double targetX = axis == PortalAxis.X ? 12.5 : 22.0;
            final double targetZ = axis == PortalAxis.X ? 22.0 : 12.5;
            final CustomPortalBounds.Destination destination = destination(portal, targetX, targetZ, safety);

            if (axis == PortalAxis.X) {
                check(destination.z() == 19.5, "X-aligned portal chose its obstructed positive-Z side");
            } else {
                check(destination.x() == 19.5, "Z-aligned portal chose its obstructed positive-X side");
            }
            check(safety.hasSupport(destination.feetBlock()), axis + " clear-side arrival was unsupported");
            check(safety.isPassable(destination.feetBlock()) && safety.isPassable(destination.feetBlock().above()),
                    axis + " clear-side arrival did not have two blocks of clearance");
        }
    }

    private static void portalCollapseExcludesVanillaSizes() {
        check(!rectangle(PortalAxis.X, 0, 0, 10, 21, 21).exceedsVanillaInteriorLimit(),
                "21x21 portal must remain entirely vanilla-managed when its frame breaks");
        check(rectangle(PortalAxis.X, 0, 0, 10, 22, 3).exceedsVanillaInteriorLimit(),
                "oversized width must use complete custom-portal collapse");
        check(rectangle(PortalAxis.Z, 0, 0, 10, 3, 22).exceedsVanillaInteriorLimit(),
                "oversized height must use complete custom-portal collapse");
    }

    private static void portalCollapseOnlyAcceptsFrameBlocksInThePortalPlane() {
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.UP), "upper X frame was rejected");
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.DOWN), "lower X frame was rejected");
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.EAST), "side X frame was rejected");
        check(PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.WEST), "side X frame was rejected");
        check(!PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.NORTH),
                "block outside the X portal plane could trigger collapse");
        check(!PortalAxis.X.isInPlane(org.bukkit.block.BlockFace.SOUTH),
                "block outside the X portal plane could trigger collapse");

        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.UP), "upper Z frame was rejected");
        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.DOWN), "lower Z frame was rejected");
        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.NORTH), "side Z frame was rejected");
        check(PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.SOUTH), "side Z frame was rejected");
        check(!PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.EAST),
                "block outside the Z portal plane could trigger collapse");
        check(!PortalAxis.Z.isInPlane(org.bukkit.block.BlockFace.WEST),
                "block outside the Z portal plane could trigger collapse");
    }

    private static void overlappingCollapseRequestsAreDetectable() {
        final CustomPortalBounds full = rectangle(PortalAxis.X, 0, 5, 10, 64, 64);
        final CustomPortalBounds remainingSubset = rectangle(PortalAxis.X, 16, 5, 10, 16, 64);
        final CustomPortalBounds separate = rectangle(PortalAxis.X, 80, 5, 10, 16, 64);

        check(full.overlaps(remainingSubset), "partially-cleared portal could be queued twice");
        check(remainingSubset.overlaps(full), "collapse overlap check must be symmetric");
        check(!full.overlaps(separate), "separate custom portals were incorrectly merged for collapse");
    }

    private static CustomPortalBounds rectangle(
            final PortalAxis axis,
            final int flatStart,
            final int plane,
            final int bottomY,
            final int width,
            final int height
    ) {
        final List<CustomPortalBounds.BlockPosition> blocks = new ArrayList<>(width * height);
        for (int flat = flatStart; flat < flatStart + width; flat++) {
            for (int y = bottomY; y < bottomY + height; y++) {
                blocks.add(axis == PortalAxis.X
                        ? new CustomPortalBounds.BlockPosition(flat, y, plane)
                        : new CustomPortalBounds.BlockPosition(plane, y, flat));
            }
        }
        return new CustomPortalBounds(axis, blocks);
    }

    private static CustomPortalBounds.Destination destination(
            final CustomPortalBounds portal,
            final double targetX,
            final double targetZ,
            final FakeSafety safety
    ) {
        return portal.findSafeDestination(targetX, targetZ, safety)
                .orElseThrow(() -> new AssertionError("no safe destination found"));
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeSafety implements CustomPortalBounds.Safety {
        private final Set<CustomPortalBounds.BlockPosition> portalBlocks;
        private final Set<CustomPortalBounds.BlockPosition> supportedFeet = new HashSet<>();
        private final Set<CustomPortalBounds.BlockPosition> obstructed = new HashSet<>();

        private FakeSafety(final CustomPortalBounds portal) {
            this.portalBlocks = portal.blocks();
        }

        private void supportInteriorBottom(final CustomPortalBounds portal) {
            portal.blocks().stream()
                    .filter(block -> block.y() == portal.minY())
                    .forEach(this.supportedFeet::add);
        }

        private void supportAllBottomLanes(final CustomPortalBounds portal) {
            portal.blocks().stream()
                    .filter(block -> block.y() == portal.minY())
                    .forEach(block -> {
                        this.supportedFeet.add(block);
                        if (portal.axis() == PortalAxis.X) {
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() - 1));
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() + 1));
                        } else {
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x() - 1, block.y(), block.z()));
                            this.supportedFeet.add(new CustomPortalBounds.BlockPosition(block.x() + 1, block.y(), block.z()));
                        }
                    });
        }

        private void blockPositiveOutsideLane(final CustomPortalBounds portal) {
            portal.blocks().stream()
                    .filter(block -> block.y() == portal.minY())
                    .map(block -> portal.axis() == PortalAxis.X
                            ? new CustomPortalBounds.BlockPosition(block.x(), block.y(), block.z() + 1)
                            : new CustomPortalBounds.BlockPosition(block.x() + 1, block.y(), block.z()))
                    .forEach(this.obstructed::add);
        }

        @Override
        public boolean isPortal(final CustomPortalBounds.BlockPosition block) {
            return this.portalBlocks.contains(block);
        }

        @Override
        public boolean isPassable(final CustomPortalBounds.BlockPosition block) {
            return !this.obstructed.contains(block);
        }

        @Override
        public boolean hasSupport(final CustomPortalBounds.BlockPosition feet) {
            return this.supportedFeet.contains(feet);
        }
    }
}
