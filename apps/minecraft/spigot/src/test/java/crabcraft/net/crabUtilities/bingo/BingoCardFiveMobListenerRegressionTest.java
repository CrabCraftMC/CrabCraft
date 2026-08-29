package crabcraft.net.crabUtilities.bingo;

import org.bukkit.Material;

/** Pure regression checks for Bingo #5 mob interaction and attribution policies. */
public final class BingoCardFiveMobListenerRegressionTest {
    private BingoCardFiveMobListenerRegressionTest() {}

    public static void main(String[] args) {
        requiresAnExactDroppedPandaCake();
        checksIronGolemRepairPreconditions();
        requiresConfirmedHealthGrowth();
        keepsAttributionOnlyForTheSameCard();
        expiresAttributionAcrossTimeAndPlayerResetBoundaries();
    }

    private static void requiresAnExactDroppedPandaCake() {
        check(
                BingoCardFiveMobListener.isEligibleDroppedPandaCake(Material.CAKE, true),
                "A dropped Cake in the Panda ground-food tag must be eligible");
        check(
                !BingoCardFiveMobListener.isEligibleDroppedPandaCake(Material.CAKE, false),
                "Cake must not count if vanilla does not let a Panda pick it up");
        check(
                !BingoCardFiveMobListener.isEligibleDroppedPandaCake(Material.BAMBOO, true),
                "Other Panda food must not satisfy the Cake-specific task");
    }

    private static void checksIronGolemRepairPreconditions() {
        check(
                BingoCardFiveMobListener.canStartGolemRepair(
                        Material.IRON_INGOT, 80.0, 100.0),
                "An Iron Ingot used on a damaged Iron Golem must start confirmation");
        check(
                !BingoCardFiveMobListener.canStartGolemRepair(
                        Material.IRON_INGOT, 100.0, 100.0),
                "An undamaged Iron Golem must not satisfy the repair task");
        check(
                !BingoCardFiveMobListener.canStartGolemRepair(
                        Material.IRON_BLOCK, 80.0, 100.0),
                "A non-Ingot item must not satisfy the repair task");
        check(
                !BingoCardFiveMobListener.canStartGolemRepair(
                        Material.IRON_INGOT, 99.99999995, 100.0),
                "Floating-point noise around full health must not create a repair attempt");
    }

    private static void requiresConfirmedHealthGrowth() {
        check(
                BingoCardFiveMobListener.healthIncreased(80.0, 81.0),
                "A confirmed Iron Golem health increase must count");
        check(
                !BingoCardFiveMobListener.healthIncreased(80.0, 80.0),
                "An interaction with no health increase must not count");
        check(
                !BingoCardFiveMobListener.healthIncreased(80.0, 80.00000005),
                "Floating-point noise must not look like a successful repair");
    }

    private static void keepsAttributionOnlyForTheSameCard() {
        check(
                BingoCardFiveMobListener.markerMatchesCard(5, 5),
                "Attribution for the same active card must survive a listener reload");
        check(
                !BingoCardFiveMobListener.markerMatchesCard(5, 6),
                "Attribution from a different card must be rejected");
        check(
                !BingoCardFiveMobListener.markerMatchesCard(5, Integer.MIN_VALUE),
                "Attribution must be rejected while no card is active");
    }

    private static void expiresAttributionAcrossTimeAndPlayerResetBoundaries() {
        long now = 10_000L;
        check(
                BingoCardFiveMobListener.isFreshAttribution(
                        9_000L, now, 8_500L, 2_000L),
                "Recent attribution created after a player reset must remain valid");
        check(
                !BingoCardFiveMobListener.isFreshAttribution(
                        8_500L, now, 8_500L, 5_000L),
                "Attribution from at or before a player reset must be invalid");
        check(
                BingoCardFiveMobListener.isFreshAttribution(
                        8_000L, now, 0L, 2_000L),
                "Attribution exactly at its maximum age must remain valid");
        check(
                !BingoCardFiveMobListener.isFreshAttribution(
                        7_999L, now, 0L, 2_000L),
                "Expired dropped-item attribution must be invalid");
        check(
                !BingoCardFiveMobListener.isFreshAttribution(
                        10_001L, now, 0L, 2_000L),
                "Future attribution timestamps must be invalid");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
