package crabcraft.net.crabUtilities.netherportals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Complete, uncapped bounds and block membership for one custom portal. */
final class CustomPortalBounds {

    static final int VANILLA_MAX_INTERIOR_SIZE = 21;

    private final PortalAxis axis;
    private final Set<BlockPosition> blocks;
    private final List<BlockPosition> bottomBlocks;
    private final int minY;
    private final int maxY;
    private final int minFlat;
    private final int maxFlat;

    CustomPortalBounds(final PortalAxis axis, final Collection<BlockPosition> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("A portal must contain at least one block");
        }

        this.axis = axis;
        this.blocks = Set.copyOf(blocks);
        this.minY = this.blocks.stream().mapToInt(BlockPosition::y).min().orElseThrow();
        this.maxY = this.blocks.stream().mapToInt(BlockPosition::y).max().orElseThrow();
        this.minFlat = this.blocks.stream().mapToInt(this::flat).min().orElseThrow();
        this.maxFlat = this.blocks.stream().mapToInt(this::flat).max().orElseThrow();
        this.bottomBlocks = this.blocks.stream()
                .filter(block -> block.y() == this.minY)
                .toList();
    }

    PortalAxis axis() {
        return this.axis;
    }

    Set<BlockPosition> blocks() {
        return this.blocks;
    }

    boolean contains(final BlockPosition block) {
        return this.blocks.contains(block);
    }

    boolean overlaps(final CustomPortalBounds other) {
        final Set<BlockPosition> smaller = this.blocks.size() <= other.blocks.size() ? this.blocks : other.blocks;
        final Set<BlockPosition> larger = smaller == this.blocks ? other.blocks : this.blocks;
        return smaller.stream().anyMatch(larger::contains);
    }

    int minY() {
        return this.minY;
    }

    int width() {
        return this.maxFlat - this.minFlat + 1;
    }

    int height() {
        return this.maxY - this.minY + 1;
    }

    boolean exceedsVanillaInteriorLimit() {
        return this.width() > VANILLA_MAX_INTERIOR_SIZE || this.height() > VANILLA_MAX_INTERIOR_SIZE;
    }

    Optional<Destination> findSafeDestination(
            final double targetX,
            final double targetZ,
            final Safety safety
    ) {
        final List<Candidate> outside = new ArrayList<>(this.bottomBlocks.size() * 2);
        final List<Candidate> inside = new ArrayList<>(this.bottomBlocks.size());

        for (final BlockPosition portalBlock : this.bottomBlocks) {
            if (this.axis == PortalAxis.X) {
                outside.add(new Candidate(portalBlock, new Destination(
                        portalBlock.x() + 0.5, portalBlock.y(), portalBlock.z() - 0.5)));
                outside.add(new Candidate(portalBlock, new Destination(
                        portalBlock.x() + 0.5, portalBlock.y(), portalBlock.z() + 1.5)));
            } else {
                outside.add(new Candidate(portalBlock, new Destination(
                        portalBlock.x() - 0.5, portalBlock.y(), portalBlock.z() + 0.5)));
                outside.add(new Candidate(portalBlock, new Destination(
                        portalBlock.x() + 1.5, portalBlock.y(), portalBlock.z() + 0.5)));
            }
            inside.add(new Candidate(portalBlock, new Destination(
                    portalBlock.x() + 0.5, portalBlock.y(), portalBlock.z() + 0.5)));
        }

        final Comparator<Candidate> closestToVanillaTarget = Comparator
                .comparingDouble((Candidate candidate) -> candidate.destination().horizontalDistanceSquared(targetX, targetZ))
                .thenComparingDouble(candidate -> candidate.destination().x())
                .thenComparingDouble(candidate -> candidate.destination().z());

        final Optional<Destination> outsideDestination = outside.stream()
                .filter(candidate -> isSafe(candidate, safety))
                .min(closestToVanillaTarget)
                .map(Candidate::destination);
        if (outsideDestination.isPresent()) {
            return outsideDestination;
        }

        // The lower portal block itself is the reliable fallback: its lower
        // frame is directly beneath it even when no exterior platform exists.
        return inside.stream()
                .filter(candidate -> isSafe(candidate, safety))
                .min(closestToVanillaTarget)
                .map(Candidate::destination);
    }

    private static boolean isSafe(final Candidate candidate, final Safety safety) {
        final BlockPosition feet = candidate.destination().feetBlock();
        return safety.isPortal(candidate.portalBlock())
                && safety.canOccupy(candidate.destination())
                && safety.hasSupport(feet);
    }

    private int flat(final BlockPosition block) {
        return this.axis == PortalAxis.X ? block.x() : block.z();
    }

    interface Safety {
        boolean isPortal(BlockPosition block);

        boolean canOccupy(Destination destination);

        boolean hasSupport(BlockPosition feet);
    }

    record BlockPosition(int x, int y, int z) {
    }

    record Destination(double x, double y, double z) {
        BlockPosition feetBlock() {
            return new BlockPosition(floor(this.x), floor(this.y), floor(this.z));
        }

        private double horizontalDistanceSquared(final double targetX, final double targetZ) {
            final double deltaX = this.x - targetX;
            final double deltaZ = this.z - targetZ;
            return deltaX * deltaX + deltaZ * deltaZ;
        }

        private static int floor(final double value) {
            final int integer = (int) value;
            return value < integer ? integer - 1 : integer;
        }
    }

    private record Candidate(BlockPosition portalBlock, Destination destination) {
    }
}
