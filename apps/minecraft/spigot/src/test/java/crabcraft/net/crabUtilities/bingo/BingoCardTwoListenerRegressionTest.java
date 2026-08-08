package crabcraft.net.crabUtilities.bingo;

import java.util.EnumMap;
import java.util.UUID;
import org.bukkit.inventory.EquipmentSlot;

/** Regression checks for Bingo #2 event correlations. */
public final class BingoCardTwoListenerRegressionTest {
    private BingoCardTwoListenerRegressionTest() {}

    public static void main(String[] args) {
        directOffhandTransitionsAreCorrelated();
        onlyTheMatchingSameTickEquipIsAttributed();
        everyArmourPieceMustHaveTheSamePlayerOwner();
        capturedFirePlacementRetainsIgnitionOwner();
    }

    private static void directOffhandTransitionsAreCorrelated() {
        check(
                BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "helmet", "empty", String::equals),
                "Clearing the tracked Piglin off-hand item must count as a transition");
        check(
                BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "helmet", "chestplate", String::equals),
                "Replacing equipped armour with the next pickup in the same tick must count as a transition");
        check(
                !BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "helmet", "helmet", String::equals),
                "Keeping the tracked item in the Piglin off hand must not count as a transition");
        check(
                !BingoCardTwoListener.transitionedAwayFromTrackedItem(
                        "helmet", "chestplate", "empty", String::equals),
                "An unrelated previous off-hand item must not count as the tracked transition");
    }

    private static void onlyTheMatchingSameTickEquipIsAttributed() {
        check(
                BingoCardTwoListener.canAttributePiglinArmour(true, true, 40, 40, true, true),
                "A fresh matching equip following the tracked off-hand transition must be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, false, 40, 40, true, true),
                "Pre-equipped armour without an observed pickup must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, true, 39, 40, true, true),
                "An equip outside the tracked transition tick must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(false, true, 40, 40, true, true),
                "An expired player pickup must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, true, 40, 40, false, true),
                "An item equipped into an unrelated slot must not be attributed");
        check(
                !BingoCardTwoListener.canAttributePiglinArmour(true, true, 40, 40, true, false),
                "An unrelated equipped item must not be attributed");
    }

    private static void everyArmourPieceMustHaveTheSamePlayerOwner() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        EnumMap<EquipmentSlot, UUID> owners = new EnumMap<>(EquipmentSlot.class);
        owners.put(EquipmentSlot.HEAD, first);
        owners.put(EquipmentSlot.CHEST, first);
        owners.put(EquipmentSlot.LEGS, first);
        owners.put(EquipmentSlot.FEET, first);
        check(
                BingoCardTwoListener.allArmourSlotsOwnedBy(owners, first),
                "One player dropping all four pieces must satisfy ownership");

        owners.put(EquipmentSlot.FEET, second);
        check(
                !BingoCardTwoListener.allArmourSlotsOwnedBy(owners, first),
                "Armour dropped by another player must prevent cross-player attribution");

        owners.remove(EquipmentSlot.FEET);
        check(
                !BingoCardTwoListener.allArmourSlotsOwnedBy(owners, first),
                "A pre-equipped unowned armour piece must not satisfy ownership");
    }

    private static void capturedFirePlacementRetainsIgnitionOwner() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        check(
                BingoCardTwoListener.shouldRetainFireOwnerAfterPlaceEvent(
                        true, player, player),
                "Paper's captured fire BlockPlaceEvent must not erase the preceding ignition owner");
        check(
                !BingoCardTwoListener.shouldRetainFireOwnerAfterPlaceEvent(
                        true, player, otherPlayer),
                "A fire placement by another player must clear stale ownership");
        check(
                !BingoCardTwoListener.shouldRetainFireOwnerAfterPlaceEvent(
                        false, player, player),
                "A non-fire block placement must clear stale fire ownership");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
