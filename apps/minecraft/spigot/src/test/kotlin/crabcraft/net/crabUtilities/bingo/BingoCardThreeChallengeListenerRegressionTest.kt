package crabcraft.net.crabUtilities.bingo

import org.bukkit.event.entity.CreeperPowerEvent

/** Regression checks for Bingo #3 challenge event correlations. */
object BingoCardThreeChallengeListenerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(
                !BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        false, CreeperPowerEvent.PowerCause.LIGHTNING),
                "A fresh lightning charge must be eligible for player attribution")
        check(
                BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        true, CreeperPowerEvent.PowerCause.LIGHTNING),
                "Lightning must not overwrite an already-charged Creeper's attribution")
        check(
                !BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        true, CreeperPowerEvent.PowerCause.SET_OFF),
                "De-powering a Creeper must clear its existing attribution")
        check(
                !BingoCardThreeChallengeListener.ChargedCreeperAttributionPolicy.shouldPreserve(
                        true, CreeperPowerEvent.PowerCause.SET_ON),
                "Plugin-powered state changes must clear existing attribution")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
