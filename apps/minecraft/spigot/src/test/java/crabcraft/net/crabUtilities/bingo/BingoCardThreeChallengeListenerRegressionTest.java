package crabcraft.net.crabUtilities.bingo;

import org.bukkit.event.entity.CreeperPowerEvent;

/** Regression checks for Bingo #3 challenge event correlations. */
public final class BingoCardThreeChallengeListenerRegressionTest {
    private BingoCardThreeChallengeListenerRegressionTest() {}

    public static void main(String[] args) {
        check(
                !BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        false, CreeperPowerEvent.PowerCause.LIGHTNING),
                "A fresh lightning charge must be eligible for player attribution");
        check(
                BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        true, CreeperPowerEvent.PowerCause.LIGHTNING),
                "Lightning must not overwrite an already-charged Creeper's attribution");
        check(
                !BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        true, CreeperPowerEvent.PowerCause.SET_OFF),
                "De-powering a Creeper must clear its existing attribution");
        check(
                !BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        true, CreeperPowerEvent.PowerCause.SET_ON),
                "Plugin-powered state changes must clear existing attribution");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
