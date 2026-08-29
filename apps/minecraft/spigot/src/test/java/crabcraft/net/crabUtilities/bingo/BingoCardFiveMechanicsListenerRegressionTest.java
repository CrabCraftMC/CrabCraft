package crabcraft.net.crabUtilities.bingo;

import java.util.Arrays;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.EquipmentSlot;

/** Regression checks for Bingo #5 inventory, bookshelf, and armour policies. */
public final class BingoCardFiveMechanicsListenerRegressionTest {
    private BingoCardFiveMechanicsListenerRegressionTest() {}

    public static void main(String[] args) {
        acceptsOnlyActionsThatTakeTheGrindstoneResult();
        validatesEnchantedBookshelfLayouts();
        requiresFourDistinctCorrectlySlottedArmorFamilies();
        invalidatesDelayedAttemptsAcrossResets();
    }

    private static void acceptsOnlyActionsThatTakeTheGrindstoneResult() {
        check(
                BingoCardFiveMechanicsListener.isResultTakeAction(InventoryAction.PICKUP_ALL),
                "Picking up the Grindstone result must be considered a take action");
        check(
                BingoCardFiveMechanicsListener.isResultTakeAction(
                        InventoryAction.MOVE_TO_OTHER_INVENTORY),
                "Shift-clicking the Grindstone result must be considered a take action");
        check(
                !BingoCardFiveMechanicsListener.isResultTakeAction(InventoryAction.NOTHING),
                "A no-op click must not satisfy the Grindstone task");
        check(
                !BingoCardFiveMechanicsListener.isResultTakeAction(InventoryAction.PLACE_ALL),
                "Placing an item into a slot must not look like taking the result");
    }

    private static void validatesEnchantedBookshelfLayouts() {
        Material[] fiveBooks = enchantedBooks(5);
        check(
                BingoCardFiveMechanicsListener.hasEnchantedBookMaterialLayout(
                        fiveBooks, 5, 1),
                "Five Enchanted Books and one empty slot must arm final insertion");
        check(
                BingoCardFiveMechanicsListener.onlyEmptyMaterialSlot(fiveBooks) == 5,
                "The sole empty Chiseled Bookshelf slot must be identified exactly");

        Material[] sixBooks = enchantedBooks(6);
        check(
                BingoCardFiveMechanicsListener.hasEnchantedBookMaterialLayout(
                        sixBooks, 6, 0),
                "Six Enchanted Books must satisfy final shelf confirmation");
        check(
                BingoCardFiveMechanicsListener.onlyEmptyMaterialSlot(sixBooks) == -1,
                "A full shelf must not report an empty slot");

        Material[] mixedBooks = enchantedBooks(5);
        mixedBooks[1] = Material.BOOK;
        check(
                !BingoCardFiveMechanicsListener.hasEnchantedBookMaterialLayout(
                        mixedBooks, 5, 1),
                "An ordinary Book must invalidate an all-Enchanted-Book shelf");

        Material[] twoEmptySlots = enchantedBooks(4);
        check(
                BingoCardFiveMechanicsListener.onlyEmptyMaterialSlot(twoEmptySlots) == -1,
                "A shelf with more than one empty slot must not arm one insertion");
        check(
                !BingoCardFiveMechanicsListener.hasEnchantedBookMaterialLayout(
                        Arrays.copyOf(sixBooks, 5), 5, 0),
                "A non-six-slot inventory must not satisfy the bookshelf task");
    }

    private static void requiresFourDistinctCorrectlySlottedArmorFamilies() {
        check(
                BingoCardFiveMechanicsListener.armorFamily(
                                Material.COPPER_LEGGINGS, EquipmentSlot.LEGS)
                        == BingoCardFiveMechanicsListener.ArmorFamily.COPPER,
                "Copper Leggings must map to the Copper armour family");
        check(
                BingoCardFiveMechanicsListener.armorFamily(
                                Material.COPPER_LEGGINGS, EquipmentSlot.CHEST)
                        == null,
                "Armour in the wrong equipment slot must not count");
        check(
                BingoCardFiveMechanicsListener.armorFamily(
                                Material.ELYTRA, EquipmentSlot.CHEST)
                        == null,
                "Non-armour equipment must not create an armour family");

        check(
                BingoCardFiveMechanicsListener.hasFourDistinctArmorMaterialFamilies(
                        Material.LEATHER_HELMET,
                        Material.CHAINMAIL_CHESTPLATE,
                        Material.COPPER_LEGGINGS,
                        Material.IRON_BOOTS),
                "Four correctly slotted, distinct armour materials must satisfy the task");
        check(
                !BingoCardFiveMechanicsListener.hasFourDistinctArmorMaterialFamilies(
                        Material.IRON_HELMET,
                        Material.IRON_CHESTPLATE,
                        Material.DIAMOND_LEGGINGS,
                        Material.GOLDEN_BOOTS),
                "Repeating an armour family must not satisfy the task");
        check(
                !BingoCardFiveMechanicsListener.hasFourDistinctArmorMaterialFamilies(
                        Material.IRON_BOOTS,
                        Material.CHAINMAIL_CHESTPLATE,
                        Material.COPPER_LEGGINGS,
                        Material.LEATHER_BOOTS),
                "An armour item in the wrong slot must invalidate the set");
    }

    private static void invalidatesDelayedAttemptsAcrossResets() {
        check(
                BingoCardFiveMechanicsListener.attemptIsCurrent(2, 3, 2, 3),
                "An unchanged detector and player generation must remain current");
        check(
                !BingoCardFiveMechanicsListener.attemptIsCurrent(3, 3, 2, 3),
                "A detector clear must invalidate delayed inventory confirmations");
        check(
                !BingoCardFiveMechanicsListener.attemptIsCurrent(2, 4, 2, 3),
                "A player reset must invalidate delayed inventory confirmations");
    }

    private static Material[] enchantedBooks(int count) {
        Material[] contents = new Material[6];
        for (int slot = 0; slot < count; slot++) {
            contents[slot] = Material.ENCHANTED_BOOK;
        }
        return contents;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
