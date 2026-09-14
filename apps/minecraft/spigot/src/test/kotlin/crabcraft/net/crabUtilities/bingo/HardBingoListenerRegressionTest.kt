package crabcraft.net.crabUtilities.bingo

import org.bukkit.NamespacedKey

/** Regression checks for Bingo #1 event handling. */
object HardBingoListenerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        unregisteredCustomHornInstrumentIsIgnored()
        registeredHornInstrumentRetainsItsKey()
    }

    private fun unregisteredCustomHornInstrumentIsIgnored() {
        check(
                HardBingoListener.serialiseInstrumentKey(null) == null,
                "An unregistered custom horn instrument must be ignored")
    }

    private fun registeredHornInstrumentRetainsItsKey() {
        val key = NamespacedKey.fromString("minecraft:ponder_goat_horn")
        check(key != null, "The vanilla horn key must be valid")
        check(
                "minecraft:ponder_goat_horn".equals(
                        HardBingoListener.serialiseInstrumentKey(key)),
                "A registered vanilla horn instrument must retain its key")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
