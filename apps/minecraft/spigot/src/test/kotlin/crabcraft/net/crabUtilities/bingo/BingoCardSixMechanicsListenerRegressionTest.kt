package crabcraft.net.crabUtilities.bingo

import java.util.EnumSet
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryAction

/** Regression checks for Bingo #6 inventory and progress policies. */
object BingoCardSixMechanicsListenerRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        classifiesVanillaFishingLoot()
        roundTripsEnchantedMaterialProgress()
        requiresFourDistinctPotterySherds()
        acceptsOnlyResultTakeActions()
        confirmsEveryRecipeInputWasActuallyConsumed()
        requiresTheCraftedResultToReachThePlayer()
    }

    private fun classifiesVanillaFishingLoot() {
        check(BingoCardSixMechanicsListener.fishingCategory(
                        Material.ENCHANTED_BOOK, false) == 1,
                "An Enchanted Book must count as fishing treasure")
        check(BingoCardSixMechanicsListener.fishingCategory(
                        Material.TRIPWIRE_HOOK, false) == 2,
                "A Tripwire Hook must count as fishing junk")
        check(BingoCardSixMechanicsListener.fishingCategory(
                        Material.FISHING_ROD, false) == 2,
                "An unenchanted Fishing Rod must count as fishing junk")
        check(BingoCardSixMechanicsListener.fishingCategory(
                        Material.FISHING_ROD, true) == 1,
                "An enchanted Fishing Rod must count as fishing treasure")
        check(BingoCardSixMechanicsListener.fishingCategory(Material.COD, false) == 0,
                "Ordinary fish must not satisfy either special loot category")
    }

    private fun roundTripsEnchantedMaterialProgress() {
        val materials = EnumSet.of(
                Material.DIAMOND_SWORD,
                Material.BOW,
                Material.BOOK,
                Material.IRON_PICKAXE,
                Material.TRIDENT)
        val encoded = BingoCardSixMechanicsListener.encodeMaterials(materials)
        check(BingoCardSixMechanicsListener.decodeMaterials(encoded).equals(materials),
                "Five distinct enchanted item materials must survive persistent encoding")
        check(BingoCardSixMechanicsListener.decodeMaterials("not_a_material,,BOW")
                        .equals(EnumSet.of(Material.BOW)),
                "Unknown persisted materials must be ignored safely")
    }

    private fun requiresFourDistinctPotterySherds() {
        val distinct = arrayOf<Material?>(
            Material.ANGLER_POTTERY_SHERD,
            Material.ARCHER_POTTERY_SHERD,
            Material.ARMS_UP_POTTERY_SHERD,
            Material.BLADE_POTTERY_SHERD
        )
        check(BingoCardSixMechanicsListener.hasFourDistinctSherdMaterials(distinct),
                "Four different Pottery Sherds must satisfy the recipe policy")

        val repeated = distinct.clone()
        repeated[3] = Material.ANGLER_POTTERY_SHERD
        check(!BingoCardSixMechanicsListener.hasFourDistinctSherdMaterials(repeated),
                "A repeated Pottery Sherd must not satisfy the task")

        val withBrick = distinct.clone()
        withBrick[3] = Material.BRICK
        check(!BingoCardSixMechanicsListener.hasFourDistinctSherdMaterials(withBrick),
                "A Brick must not count as a Pottery Sherd")
    }

    private fun acceptsOnlyResultTakeActions() {
        check(BingoCardSixMechanicsListener.isResultTakeAction(InventoryAction.PICKUP_ALL),
                "Picking up a result must count")
        check(BingoCardSixMechanicsListener.isResultTakeAction(
                        InventoryAction.MOVE_TO_OTHER_INVENTORY),
                "Shift-clicking a result must arm a post-transfer confirmation")
        check(!BingoCardSixMechanicsListener.isResultTakeAction(InventoryAction.PLACE_ALL),
                "Placing into a slot must not look like taking a result")
        check(!BingoCardSixMechanicsListener.canChangeInventory(InventoryAction.NOTHING),
                "A no-op cannot trigger an Ender Chest confirmation")
        check(BingoCardSixMechanicsListener.isDropResultAction(
                        InventoryAction.DROP_ALL_SLOT),
                "Dropping a Smithing result must use exact dropped-item confirmation")
    }

    private fun requiresTheCraftedResultToReachThePlayer() {
        check(BingoCardSixMechanicsListener.resultCountIncreased(1, 2),
                "A newly received Smithing result must confirm success")
        check(!BingoCardSixMechanicsListener.resultCountIncreased(1, 1),
                "Closing a failed full-inventory craft must not look successful")
        check(!BingoCardSixMechanicsListener.resultCountIncreased(2, 1),
                "Losing an existing matching item must not look like a new result")
    }

    private fun confirmsEveryRecipeInputWasActuallyConsumed() {
        val before = arrayOf<BingoCardSixMechanicsListener.StackSnapshot?>(
            BingoCardSixMechanicsListener.StackSnapshot(
                    Material.ANGLER_POTTERY_SHERD, 2),
            BingoCardSixMechanicsListener.StackSnapshot(
                    Material.ARCHER_POTTERY_SHERD, 1),
            BingoCardSixMechanicsListener.StackSnapshot(
                    Material.ARMS_UP_POTTERY_SHERD, 3),
            BingoCardSixMechanicsListener.StackSnapshot(
                    Material.BLADE_POTTERY_SHERD, 1)
        )
        val consumed = arrayOf<BingoCardSixMechanicsListener.StackSnapshot?>(
            BingoCardSixMechanicsListener.StackSnapshot(
                    Material.ANGLER_POTTERY_SHERD, 1),
            null,
            BingoCardSixMechanicsListener.StackSnapshot(
                    Material.ARMS_UP_POTTERY_SHERD, 2),
            null
        )
        check(BingoCardSixMechanicsListener.everyInputWasConsumed(before, consumed),
                "A successful craft must consume at least one of every input")
        check(!BingoCardSixMechanicsListener.everyInputWasConsumed(before, before),
                "A full-inventory shift-click that consumes nothing must not complete")

        val partlyConsumed = consumed.clone()
        partlyConsumed[2] = before[2]
        check(!BingoCardSixMechanicsListener.everyInputWasConsumed(before, partlyConsumed),
                "Leaving any required input unchanged must reject completion")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
