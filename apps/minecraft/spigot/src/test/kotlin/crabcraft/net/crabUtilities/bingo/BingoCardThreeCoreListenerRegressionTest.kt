package crabcraft.net.crabUtilities.bingo

import org.bukkit.Material

/** Regression checks for Bingo #3 core event correlations. */
object BingoCardThreeCoreListenerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(
                BingoCardThreeCoreListener.hasAbsorbedTnt(Material.TNT),
                "An absorbed TNT body item must confirm a valid Sulfur Cube feed")
        check(
                !BingoCardThreeCoreListener.hasAbsorbedTnt(Material.AIR),
                "An empty Sulfur Cube must not confirm a feed")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
