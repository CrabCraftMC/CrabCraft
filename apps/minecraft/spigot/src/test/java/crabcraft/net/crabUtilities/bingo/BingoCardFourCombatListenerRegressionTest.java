package crabcraft.net.crabUtilities.bingo;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

/** Regression checks for Bingo #4 combat and projectile correlations. */
public final class BingoCardFourCombatListenerRegressionTest {
    private BingoCardFourCombatListenerRegressionTest() {}

    public static void main(String[] args) {
        EquipmentSlot hand = EquipmentSlot.HAND;
        check(
                BingoCardFourCombatListener.CombatPolicy.sameSpearSession(
                        true,
                        hand,
                        hand,
                        Material.IRON_SPEAR,
                        Material.IRON_SPEAR,
                        100,
                        420,
                        320),
                "Three hits from one active Spear use must share a session");
        check(
                !BingoCardFourCombatListener.CombatPolicy.sameSpearSession(
                        true,
                        hand,
                        EquipmentSlot.OFF_HAND,
                        Material.IRON_SPEAR,
                        Material.IRON_SPEAR,
                        100,
                        101,
                        320),
                "Switching the active Spear hand must start a new session");
        check(
                !BingoCardFourCombatListener.CombatPolicy.sameSpearSession(
                        true,
                        hand,
                        hand,
                        Material.IRON_SPEAR,
                        Material.DIAMOND_SPEAR,
                        100,
                        101,
                        320),
                "Switching Spear items must start a new session");
        check(
                !BingoCardFourCombatListener.CombatPolicy.sameSpearSession(
                        true,
                        hand,
                        hand,
                        Material.IRON_SPEAR,
                        Material.IRON_SPEAR,
                        100,
                        421,
                        320),
                "An expired Spear session must not retain earlier hits");

        UUID originalBreeze = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        check(
                BingoCardFourCombatListener.CombatPolicy.isExactReflectedBreezeKill(
                        originalBreeze, originalBreeze, player, player),
                "The exact reflected charge may kill the Breeze that fired it");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isExactReflectedBreezeKill(
                        originalBreeze, UUID.randomUUID(), player, player),
                "A reflected charge must not award for killing another Breeze");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isExactReflectedBreezeKill(
                        originalBreeze, originalBreeze, player, UUID.randomUUID()),
                "Another player's deflection must not award the first player");

        UUID firework = UUID.randomUUID();
        check(
                BingoCardFourCombatListener.CombatPolicy.isExactFireworkKill(
                        firework, firework, player, player),
                "The exact crossbow firework and shooter must retain kill attribution");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isExactFireworkKill(
                        firework, UUID.randomUUID(), player, player),
                "Another firework must not share a tracked explosion's kills");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isExactFireworkKill(
                        firework, firework, player, UUID.randomUUID()),
                "Another player must not inherit a tracked firework's kills");

        check(
                BingoCardFourCombatListener.CombatPolicy.isPotBreakReplacement(Material.AIR),
                "A dry Decorated Pot is replaced by air when smashed");
        check(
                BingoCardFourCombatListener.CombatPolicy.isPotBreakReplacement(Material.WATER),
                "A waterlogged Decorated Pot is replaced by water when smashed");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isPotBreakReplacement(Material.STONE),
                "An unrelated projectile block change must not count as a smashed pot");
        check(
                BingoCardFourCombatListener.CombatPolicy.isQualifyingPotSmash(
                        true, true, 100.0, 100.0),
                "Exactly ten blocks must meet the projectile distance threshold");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isQualifyingPotSmash(
                        false, true, 400.0, 100.0),
                "An empty Decorated Pot must not count");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isQualifyingPotSmash(
                        true, false, 400.0, 100.0),
                "Projectile distance cannot be correlated across worlds");
        check(
                !BingoCardFourCombatListener.CombatPolicy.isQualifyingPotSmash(
                        true, true, 99.999, 100.0),
                "A projectile fired from under ten blocks must not count");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
