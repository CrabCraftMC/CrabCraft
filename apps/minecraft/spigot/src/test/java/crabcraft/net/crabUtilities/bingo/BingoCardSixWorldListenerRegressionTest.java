package crabcraft.net.crabUtilities.bingo;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/** Pure regression checks for Bingo #6 world attribution and delayed confirmations. */
public final class BingoCardSixWorldListenerRegressionTest {
    private BingoCardSixWorldListenerRegressionTest() {}

    public static void main(String[] args) {
        requiresExactlyFourByFourPaintingDimensions();
        recognizesArrowProjectilesOnly();
        enumeratesExactlyTheVanillaConduitFrame();
        requiresThePlayersFinalFrameForMaximumConduitPower();
        recognizesOnlyFishBuckets();
        invalidatesDelayedAttemptsAcrossResets();
        keepsNamedGhastAttributionOnlyForTheCurrentCardAndPlayerRun();
        defersGhastConfirmationAcrossTheStartupCardGap();
    }

    private static void enumeratesExactlyTheVanillaConduitFrame() {
        int offsets = 0;
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (BingoCardSixWorldListener.isConduitFrameOffset(x, y, z)) offsets++;
                }
            }
        }
        check(offsets == 42, "The vanilla Conduit frame must expose exactly 42 valid slots");
        check(BingoCardSixWorldListener.isConduitFrameOffset(0, 2, 2),
                "A corner in the vertical ring must be a frame slot");
        check(BingoCardSixWorldListener.isConduitFrameOffset(2, 0, -1),
                "An edge in the horizontal ring must be a frame slot");
        check(!BingoCardSixWorldListener.isConduitFrameOffset(2, 2, 2),
                "A cube corner is not part of the Conduit frame");
        check(!BingoCardSixWorldListener.isConduitFrameOffset(1, 0, 1),
                "The central three-by-three volume is not part of the frame");
    }

    private static void requiresExactlyFourByFourPaintingDimensions() {
        check(
                BingoCardSixWorldListener.isFourByFour(4, 4),
                "A four-wide, four-high Painting must satisfy the task");
        check(
                !BingoCardSixWorldListener.isFourByFour(4, 3),
                "A four-wide but three-high Painting must not count");
        check(
                !BingoCardSixWorldListener.isFourByFour(3, 4),
                "A three-wide but four-high Painting must not count");
    }

    private static void recognizesArrowProjectilesOnly() {
        check(
                BingoCardSixWorldListener.isArrow(EntityType.ARROW),
                "A normal or tipped Arrow must be eligible to power the Button");
        check(
                BingoCardSixWorldListener.isArrow(EntityType.SPECTRAL_ARROW),
                "A Spectral Arrow must be eligible to power the Button");
        check(
                !BingoCardSixWorldListener.isArrow(EntityType.TRIDENT),
                "A Trident is an AbstractArrow in Bukkit but must not satisfy an Arrow task");
        check(
                !BingoCardSixWorldListener.isArrow(EntityType.SNOWBALL),
                "An unrelated projectile must not satisfy the Arrow task");
    }

    private static void requiresThePlayersFinalFrameForMaximumConduitPower() {
        check(
                BingoCardSixWorldListener.isFullyPoweredConduit(true, 42, true),
                "An active 42-frame Conduit containing the player's placed block must count");
        check(
                !BingoCardSixWorldListener.isFullyPoweredConduit(false, 42, true),
                "A structurally complete but inactive Conduit must not count");
        check(
                !BingoCardSixWorldListener.isFullyPoweredConduit(true, 41, true),
                "A 41-frame Conduit has not reached maximum power");
        check(
                !BingoCardSixWorldListener.isFullyPoweredConduit(true, 42, false),
                "Placing an unrelated nearby prismarine block must not receive credit");
    }

    private static void recognizesOnlyFishBuckets() {
        check(
                BingoCardSixWorldListener.isFishBucket(Material.COD_BUCKET),
                "A Cod Bucket must be accepted");
        check(
                BingoCardSixWorldListener.isFishBucket(Material.SALMON_BUCKET),
                "A Salmon Bucket must be accepted");
        check(
                BingoCardSixWorldListener.isFishBucket(Material.PUFFERFISH_BUCKET),
                "A Pufferfish Bucket must be accepted");
        check(
                BingoCardSixWorldListener.isFishBucket(Material.TROPICAL_FISH_BUCKET),
                "A Tropical Fish Bucket must be accepted");
        check(
                !BingoCardSixWorldListener.isFishBucket(Material.AXOLOTL_BUCKET),
                "An Axolotl Bucket is aquatic but is not a Fish Bucket");
        check(
                !BingoCardSixWorldListener.isFishBucket(Material.WATER_BUCKET),
                "A plain Water Bucket must not satisfy the fish task");
    }

    private static void invalidatesDelayedAttemptsAcrossResets() {
        check(
                BingoCardSixWorldListener.attemptIsCurrent(6, 4, 6, 4),
                "An unchanged detector and player generation must remain current");
        check(
                !BingoCardSixWorldListener.attemptIsCurrent(7, 4, 6, 4),
                "A detector clear must invalidate pending block confirmations");
        check(
                !BingoCardSixWorldListener.attemptIsCurrent(6, 5, 6, 4),
                "A player reset must invalidate that player's pending confirmations");
    }

    private static void keepsNamedGhastAttributionOnlyForTheCurrentCardAndPlayerRun() {
        long now = 10_000L;
        check(
                BingoCardSixWorldListener.markerIsCurrent(
                        61, 61, 9_000L, now, 4L, 4L, 2_000L),
                "A recent Ghast marker for the active card and current player run must survive reloads");
        check(
                !BingoCardSixWorldListener.markerIsCurrent(
                        60, 61, 9_000L, now, 4L, 4L, 2_000L),
                "A marker from another card must not be reusable");
        check(
                !BingoCardSixWorldListener.markerIsCurrent(
                        61, Integer.MIN_VALUE, 9_000L, now, 4L, 4L, 2_000L),
                "A Ghast marker must be rejected while no card is active");
        check(
                !BingoCardSixWorldListener.markerIsCurrent(
                        61, 61, 9_000L, now, 3L, 4L, 5_000L),
                "A marker from before the player's persisted reset generation must be rejected");
        check(
                !BingoCardSixWorldListener.markerIsCurrent(
                        61, 61, 7_999L, now, 4L, 4L, 2_000L),
                "An expired Ghast marker must be rejected");
        check(
                !BingoCardSixWorldListener.markerIsCurrent(
                        61, 61, 10_001L, now, 4L, 4L, 2_000L),
                "A future Ghast marker timestamp must be rejected");
    }

    private static void defersGhastConfirmationAcrossTheStartupCardGap() {
        check(BingoCardSixWorldListener.shouldDeferPersistentConfirmation(
                        6, Integer.MIN_VALUE, true, true, 0, 300),
                "A Ghast portal event must wait while Redis restores the active card");
        check(BingoCardSixWorldListener.shouldDeferPersistentConfirmation(
                        6, 6, false, true, 0, 300),
                "A transported Ghast may wait briefly for its owner to reconnect");
        check(!BingoCardSixWorldListener.shouldDeferPersistentConfirmation(
                        6, Integer.MIN_VALUE, true, false, 0, 300),
                "An expired Ghast marker must not be deferred");
        check(!BingoCardSixWorldListener.shouldDeferPersistentConfirmation(
                        6, Integer.MIN_VALUE, true, true, 300, 300),
                "Ghast startup deferral must be bounded");
        check(BingoCardSixWorldListener.persistentConfirmationNeedsContext(
                        6, Integer.MIN_VALUE, true),
                "An exhausted startup wait must still be recognized so its in-memory claim can be released");
        check(BingoCardSixWorldListener.persistentConfirmationNeedsContext(
                        6, 6, false),
                "An exhausted offline-owner wait must release its claim for a later join scan");
        check(!BingoCardSixWorldListener.shouldDeferPersistentConfirmation(
                        5, 6, false, true, 0, 300),
                "A known mismatching card must not retry for an offline owner");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
