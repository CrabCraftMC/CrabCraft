package crabcraft.net.crabUtilities.bingo;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Pure regression checks for Bingo #4 world-event correlations. */
public final class BingoCardFourWorldListenerRegressionTest {
    private BingoCardFourWorldListenerRegressionTest() {}

    public static void main(String[] args) {
        acceptsBothPortalOrientations();
        rejectsIncompleteAndNonRectangularPortals();
        checksEnderPearlDistanceAndWorld();
    }

    private static void acceptsBothPortalOrientations() {
        check(
                BingoCardFourWorldListener.formsFourByFourPortal(portalInXPlane()),
                "A complete 4-by-4 X-plane portal interior must be accepted");
        check(
                BingoCardFourWorldListener.formsFourByFourPortal(portalInZPlane()),
                "A complete 4-by-4 Z-plane portal interior must be accepted");
    }

    private static void rejectsIncompleteAndNonRectangularPortals() {
        Set<BingoCardFourWorldListener.BlockPoint> missing = portalInXPlane();
        missing.remove(new BingoCardFourWorldListener.BlockPoint(8, 12, 23));
        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(missing),
                "A portal with one missing interior block must be rejected");

        Set<BingoCardFourWorldListener.BlockPoint> nonPlanar = portalInXPlane();
        nonPlanar.remove(new BingoCardFourWorldListener.BlockPoint(8, 12, 23));
        nonPlanar.add(new BingoCardFourWorldListener.BlockPoint(9, 12, 23));
        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(nonPlanar),
                "Sixteen portal blocks that are not one rectangle must be rejected");

        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(rectangle(8, 3)),
                "A 3-by-4 portal interior must be rejected");
        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(rectangle(8, 5)),
                "A 4-by-5 portal interior must be rejected");
    }

    private static void checksEnderPearlDistanceAndWorld() {
        UUID world = UUID.randomUUID();
        check(
                BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0, 0, world, 60, 80),
                "An exact 100-block horizontal Ender Pearl teleport must count");
        check(
                BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0, 0, world, 100.01, 0),
                "An Ender Pearl teleport beyond 100 horizontal blocks must count");
        check(
                !BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0, 0, world, 99.99, 0),
                "An Ender Pearl teleport below 100 horizontal blocks must not count");
        check(
                !BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0, 0, UUID.randomUUID(), 200, 0),
                "Cross-world movement must not count as one Ender Pearl teleport");
        check(
                !BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0, 0, world, 0, 0),
                "Vertical distance must not count towards the horizontal requirement");
    }

    private static Set<BingoCardFourWorldListener.BlockPoint> portalInXPlane() {
        Set<BingoCardFourWorldListener.BlockPoint> points = new LinkedHashSet<>();
        for (int y = 10; y < 14; y++) {
            for (int z = 20; z < 24; z++) {
                points.add(new BingoCardFourWorldListener.BlockPoint(8, y, z));
            }
        }
        return points;
    }

    private static Set<BingoCardFourWorldListener.BlockPoint> portalInZPlane() {
        Set<BingoCardFourWorldListener.BlockPoint> points = new LinkedHashSet<>();
        for (int y = -4; y < 0; y++) {
            for (int x = 31; x < 35; x++) {
                points.add(new BingoCardFourWorldListener.BlockPoint(x, y, 5));
            }
        }
        return points;
    }

    private static Set<BingoCardFourWorldListener.BlockPoint> rectangle(int x, int height) {
        Set<BingoCardFourWorldListener.BlockPoint> points = new LinkedHashSet<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < 4; z++) {
                points.add(new BingoCardFourWorldListener.BlockPoint(x, y, z));
            }
        }
        return points;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
