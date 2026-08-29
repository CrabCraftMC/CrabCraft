package crabcraft.net.crabUtilities.bingo;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/** Pure regression checks for Bingo #5 world construction and delayed confirmations. */
public final class BingoCardFiveWorldListenerRegressionTest {
    private BingoCardFiveWorldListenerRegressionTest() {}

    public static void main(String[] args) {
        recognizesBothBigDripleafBlocks();
        protectsSnowLayerOwnership();
        recognizesBoundCompassDroppedByFullInventory();
        requiresActualMinecartFuelGrowth();
        invalidatesDelayedAttemptsAcrossResets();
        preservesBlockOwnershipAcrossSameCardRestarts();
        preservesBlockKeyGeometry();
    }

    private static void recognizesBothBigDripleafBlocks() {
        check(
                BingoCardFiveWorldListener.isBigDripleaf(Material.BIG_DRIPLEAF),
                "A Big Dripleaf top must contribute to the ten-block column");
        check(
                BingoCardFiveWorldListener.isBigDripleaf(Material.BIG_DRIPLEAF_STEM),
                "A Big Dripleaf stem must contribute to the ten-block column");
        check(
                !BingoCardFiveWorldListener.isBigDripleaf(Material.SMALL_DRIPLEAF),
                "A Small Dripleaf must not contribute to the ten-block column");
    }

    private static void protectsSnowLayerOwnership() {
        check(
                BingoCardFiveWorldListener.canOwnSnowLayer(true, false),
                "A player may claim a newly placed Snow block");
        check(
                BingoCardFiveWorldListener.canOwnSnowLayer(false, true),
                "A player may add layers to their own tracked Snow block");
        check(
                !BingoCardFiveWorldListener.canOwnSnowLayer(false, false),
                "Adding a layer to someone else's Snow block must not steal ownership");
    }

    private static void requiresActualMinecartFuelGrowth() {
        check(
                BingoCardFiveWorldListener.fuelIncreased(0, 3600),
                "Fuel added to a Furnace Minecart must satisfy confirmation");
        check(
                !BingoCardFiveWorldListener.fuelIncreased(3600, 3600),
                "An unchanged Furnace Minecart fuel level must not count");
        check(
                !BingoCardFiveWorldListener.fuelIncreased(3600, 3599),
                "Consumed Furnace Minecart fuel must not look like a new fueling action");
    }

    private static void recognizesBoundCompassDroppedByFullInventory() {
        check(
                BingoCardFiveWorldListener.compassBindingSucceeded(0, 1, false),
                "A newly bound Compass retained in the inventory must satisfy confirmation");
        check(
                BingoCardFiveWorldListener.compassBindingSucceeded(0, 0, true),
                "A newly bound Compass dropped by a full inventory must satisfy confirmation");
        check(
                !BingoCardFiveWorldListener.compassBindingSucceeded(1, 1, false),
                "An unchanged inventory without a matching automatic drop must not count");
    }

    private static void invalidatesDelayedAttemptsAcrossResets() {
        check(
                BingoCardFiveWorldListener.attemptIsCurrent(4, 7, 4, 7),
                "An unchanged detector and player generation must remain current");
        check(
                !BingoCardFiveWorldListener.attemptIsCurrent(5, 7, 4, 7),
                "A detector clear must invalidate delayed world confirmations");
        check(
                !BingoCardFiveWorldListener.attemptIsCurrent(4, 8, 4, 7),
                "A player reset must invalidate that player's delayed world confirmations");
    }

    private static void preservesBlockOwnershipAcrossSameCardRestarts() {
        UUID playerId = UUID.randomUUID();
        BingoCardFiveWorldListener.BlockOwnership ownership =
                new BingoCardFiveWorldListener.BlockOwnership(51, playerId, 9_000L);
        String encoded = BingoCardFiveWorldListener.encodeOwnership(ownership);
        check(
                ownership.equals(BingoCardFiveWorldListener.decodeOwnership(encoded)),
                "Chunk PDC ownership must round-trip across a server restart");
        check(
                BingoCardFiveWorldListener.ownershipIsCurrent(
                        ownership, 51, 0L, 10_000L),
                "Ownership from the same active card must survive detector recreation");
        check(
                !BingoCardFiveWorldListener.ownershipIsCurrent(
                        ownership, 52, 0L, 10_000L),
                "Ownership from a different card must not be reusable");
        check(
                !BingoCardFiveWorldListener.ownershipIsCurrent(
                        ownership, 51, 9_000L, 10_000L),
                "A player reset must invalidate earlier persistent block ownership");
        check(
                BingoCardFiveWorldListener.decodeOwnership("broken") == null,
                "Malformed Chunk PDC ownership must be rejected safely");
    }

    private static void preservesBlockKeyGeometry() {
        UUID world = UUID.randomUUID();
        BingoCardFiveWorldListener.BlockKey origin =
                new BingoCardFiveWorldListener.BlockKey(world, 10, 64, -3);
        check(
                origin.relative(BlockFace.NORTH)
                        .equals(new BingoCardFiveWorldListener.BlockKey(world, 10, 64, -4)),
                "Horizontal Snow connectivity must remain in the same world and Y level");
        check(
                origin.withY(72)
                        .equals(new BingoCardFiveWorldListener.BlockKey(world, 10, 72, -3)),
                "Dripleaf column scans must change only the block Y coordinate");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
