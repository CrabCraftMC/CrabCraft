package crabcraft.net.crabUtilities.bingo;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.util.BoundingBox;

/** Regression checks for Bingo #4 mob-interaction attribution. */
public final class BingoCardFourMobListenerRegressionTest {
    private BingoCardFourMobListenerRegressionTest() {}

    public static void main(String[] args) {
        sulfurCubeBucketRequiresEveryCommittedPrecondition();
        silverfishInfestationRequiresNamingAndAnExactHostPair();
        powderSnowIntersectionExcludesTouchingFaces();
        attributionExpiresAcrossTimeAndLifecycleBoundaries();
    }

    private static void sulfurCubeBucketRequiresEveryCommittedPrecondition() {
        check(
                BingoCardFourMobListener.isDiamondSulfurCubeBucketTarget(
                        true, -1, Material.DIAMOND_BLOCK, Material.BUCKET),
                "An adult, unprimed Diamond Block Sulfur Cube must qualify");
        check(
                !BingoCardFourMobListener.isDiamondSulfurCubeBucketTarget(
                        false, -1, Material.DIAMOND_BLOCK, Material.BUCKET),
                "A baby Sulfur Cube must not qualify");
        check(
                !BingoCardFourMobListener.isDiamondSulfurCubeBucketTarget(
                        true, 20, Material.DIAMOND_BLOCK, Material.BUCKET),
                "A primed Sulfur Cube must not qualify");
        check(
                !BingoCardFourMobListener.isDiamondSulfurCubeBucketTarget(
                        true, -1, Material.GOLD_BLOCK, Material.BUCKET),
                "A Sulfur Cube carrying another block must not qualify");
        check(
                !BingoCardFourMobListener.isDiamondSulfurCubeBucketTarget(
                        true, -1, Material.DIAMOND_BLOCK, Material.WATER_BUCKET),
                "The capture must use an empty Bucket");
    }

    private static void silverfishInfestationRequiresNamingAndAnExactHostPair() {
        check(
                BingoCardFourMobListener.hasName(Component.text("Bingo")),
                "A non-null Name Tag component must arm Silverfish attribution");
        check(
                !BingoCardFourMobListener.hasName(null),
                "A null replacement name must not arm Silverfish attribution");
        check(
                BingoCardFourMobListener.isSilverfishInfestation(
                        Material.STONE, Material.INFESTED_STONE),
                "Stone becoming Infested Stone must satisfy the transition");
        check(
                BingoCardFourMobListener.isSilverfishInfestation(
                        Material.DEEPSLATE, Material.INFESTED_DEEPSLATE),
                "Deepslate becoming Infested Deepslate must satisfy the transition");
        check(
                !BingoCardFourMobListener.isSilverfishInfestation(
                        Material.STONE, Material.INFESTED_COBBLESTONE),
                "A mismatched infested result must not satisfy the transition");
        check(
                !BingoCardFourMobListener.isSilverfishInfestation(
                        Material.GRANITE, Material.INFESTED_STONE),
                "An incompatible source block must not satisfy the transition");
    }

    private static void powderSnowIntersectionExcludesTouchingFaces() {
        BoundingBox skeleton = new BoundingBox(0.2, 0.0, 0.2, 0.8, 1.99, 0.8);
        check(
                BingoCardFourMobListener.intersectsBlock(skeleton, 0, 0, 0),
                "The Skeleton's feet block must intersect");
        check(
                BingoCardFourMobListener.intersectsBlock(skeleton, 0, 1, 0),
                "The Skeleton's upper body block must intersect");
        check(
                !BingoCardFourMobListener.intersectsBlock(skeleton, 1, 0, 0),
                "A neighbouring block that only lies beyond the bounding box must not intersect");

        BoundingBox exactBlock = new BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        check(
                !BingoCardFourMobListener.intersectsBlock(exactBlock, 1, 0, 0),
                "Blocks that only touch at a face must not intersect");
    }

    private static void attributionExpiresAcrossTimeAndLifecycleBoundaries() {
        long now = 10_000L;
        check(
                BingoCardFourMobListener.isFreshAttribution(9_000L, now, 8_000L, 8_500L, 2_000L),
                "A recent marker created after reset and clear must remain valid");
        check(
                !BingoCardFourMobListener.isFreshAttribution(8_000L, now, 8_000L, 0L, 5_000L),
                "A marker from before or at the card clear must be invalid");
        check(
                !BingoCardFourMobListener.isFreshAttribution(8_500L, now, 0L, 8_500L, 5_000L),
                "A marker from before or at the player reset must be invalid");
        check(
                !BingoCardFourMobListener.isFreshAttribution(7_999L, now, 0L, 0L, 2_000L),
                "An expired marker must be invalid");
        check(
                !BingoCardFourMobListener.isFreshAttribution(10_001L, now, 0L, 0L, 2_000L),
                "A future marker must be invalid");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
