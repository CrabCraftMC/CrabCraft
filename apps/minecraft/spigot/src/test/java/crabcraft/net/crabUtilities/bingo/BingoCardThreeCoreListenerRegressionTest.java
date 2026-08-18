package crabcraft.net.crabUtilities.bingo;

import org.bukkit.Material;

/** Regression checks for Bingo #3 core event correlations. */
public final class BingoCardThreeCoreListenerRegressionTest {
    private BingoCardThreeCoreListenerRegressionTest() {}

    public static void main(String[] args) {
        check(
                BingoCardThreeCoreListener.hasAbsorbedTnt(Material.TNT),
                "An absorbed TNT body item must confirm a valid Sulfur Cube feed");
        check(
                !BingoCardThreeCoreListener.hasAbsorbedTnt(Material.AIR),
                "An empty Sulfur Cube must not confirm a feed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
