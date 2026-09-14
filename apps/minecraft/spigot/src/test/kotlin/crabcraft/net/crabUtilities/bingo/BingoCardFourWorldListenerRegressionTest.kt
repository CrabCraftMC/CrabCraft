package crabcraft.net.crabUtilities.bingo

import java.util.LinkedHashSet
import java.util.UUID

/** Pure regression checks for Bingo #4 world-event correlations. */
object BingoCardFourWorldListenerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        acceptsBothPortalOrientations()
        rejectsIncompleteAndNonRectangularPortals()
        checksEnderPearlDistanceAndWorld()
    }

    private fun acceptsBothPortalOrientations() {
        check(
                BingoCardFourWorldListener.formsFourByFourPortal(portalInXPlane()),
                "A complete 4-by-4 X-plane portal interior must be accepted")
        check(
                BingoCardFourWorldListener.formsFourByFourPortal(portalInZPlane()),
                "A complete 4-by-4 Z-plane portal interior must be accepted")
    }

    private fun rejectsIncompleteAndNonRectangularPortals() {
        val missing = portalInXPlane()
        missing.remove(BingoCardFourWorldListener.BlockPoint(8, 12, 23))
        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(missing),
                "A portal with one missing interior block must be rejected")

        val nonPlanar = portalInXPlane()
        nonPlanar.remove(BingoCardFourWorldListener.BlockPoint(8, 12, 23))
        nonPlanar.add(BingoCardFourWorldListener.BlockPoint(9, 12, 23))
        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(nonPlanar),
                "Sixteen portal blocks that are not one rectangle must be rejected")

        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(rectangle(8, 3)),
                "A 3-by-4 portal interior must be rejected")
        check(
                !BingoCardFourWorldListener.formsFourByFourPortal(rectangle(8, 5)),
                "A 4-by-5 portal interior must be rejected")
    }

    private fun checksEnderPearlDistanceAndWorld() {
        val world = UUID.randomUUID()
        check(
                BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0.0, 0.0, world, 60.0, 80.0),
                "An exact 100-block horizontal Ender Pearl teleport must count")
        check(
                BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0.0, 0.0, world, 100.01, 0.0),
                "An Ender Pearl teleport beyond 100 horizontal blocks must count")
        check(
                !BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0.0, 0.0, world, 99.99, 0.0),
                "An Ender Pearl teleport below 100 horizontal blocks must not count")
        check(
                !BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0.0, 0.0, UUID.randomUUID(), 200.0, 0.0),
                "Cross-world movement must not count as one Ender Pearl teleport")
        check(
                !BingoCardFourWorldListener.isHundredBlockHorizontalTeleport(
                        world, 0.0, 0.0, world, 0.0, 0.0),
                "Vertical distance must not count towards the horizontal requirement")
    }

    private fun portalInXPlane(): MutableSet<BingoCardFourWorldListener.BlockPoint> {
        val points = LinkedHashSet<BingoCardFourWorldListener.BlockPoint>()
        for (y in 10 until 14) {
            for (z in 20 until 24) {
                points.add(BingoCardFourWorldListener.BlockPoint(8, y, z))
            }
        }
        return points
    }

    private fun portalInZPlane(): MutableSet<BingoCardFourWorldListener.BlockPoint> {
        val points = LinkedHashSet<BingoCardFourWorldListener.BlockPoint>()
        for (y in -4 until 0) {
            for (x in 31 until 35) {
                points.add(BingoCardFourWorldListener.BlockPoint(x, y, 5))
            }
        }
        return points
    }

    private fun rectangle(x: Int, height: Int): MutableSet<BingoCardFourWorldListener.BlockPoint> {
        val points = LinkedHashSet<BingoCardFourWorldListener.BlockPoint>()
        for (y in 0 until height) {
            for (z in 0 until 4) {
                points.add(BingoCardFourWorldListener.BlockPoint(x, y, z))
            }
        }
        return points
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
