package crabcraft.net.crabUtilities.bingo;

import org.bukkit.NamespacedKey;

/** Regression checks for Bingo #1 event handling. */
public final class HardBingoListenerRegressionTest {
    private HardBingoListenerRegressionTest() {}

    public static void main(String[] args) {
        unregisteredCustomHornInstrumentIsIgnored();
        registeredHornInstrumentRetainsItsKey();
    }

    private static void unregisteredCustomHornInstrumentIsIgnored() {
        check(
                HardBingoListener.serialiseInstrumentKey(null) == null,
                "An unregistered custom horn instrument must be ignored");
    }

    private static void registeredHornInstrumentRetainsItsKey() {
        NamespacedKey key = NamespacedKey.fromString("minecraft:ponder_goat_horn");
        check(key != null, "The vanilla horn key must be valid");
        check(
                "minecraft:ponder_goat_horn".equals(
                        HardBingoListener.serialiseInstrumentKey(key)),
                "A registered vanilla horn instrument must retain its key");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
