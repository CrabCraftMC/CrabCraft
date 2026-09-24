package crabcraft.net.crabUtilities.bingo

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent
import crabcraft.net.crabUtilities.bingo.BingoTracking.BlockKey
import java.util.EnumSet
import java.util.HashSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.ChiseledBookshelf
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.CauldronLevelChangeEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ChiseledBookshelfInventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven inventory and equipment detectors for Bingo #5. */
class BingoCardFiveMechanicsListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
) : AbstractBingoDetector() {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCauldronLevelChanged(event: CauldronLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (
            event.reason != CauldronLevelChangeEvent.ChangeReason.BANNER_WASH ||
                !tracking.test(player, BingoTask.CLEAN_BANNER_PATTERN)
        )
            return
        // Vanilla only raises BANNER_WASH when a patterned banner is successfully
        // cleaned, so the uncancelled reason is stronger than inspecting either hand
        // after the interaction has already replaced the held ItemStack.
        completion.accept(player, BingoTask.CLEAN_BANNER_PATTERN)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGrindstoneResultTaken(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val grindstone = event.view.topInventory as? GrindstoneInventory ?: return
        if (
            event.clickedInventory != grindstone ||
                event.slotType != InventoryType.SlotType.RESULT ||
                !isResultTakeAction(event.action) ||
                !tracking.test(player, BingoTask.REMOVE_ENCHANTMENT_GRINDSTONE)
        )
            return
        if (!removesNonCurseEnchantment(grindstone.upperItem, grindstone.lowerItem, event.currentItem)) return
        completion.accept(player, BingoTask.REMOVE_ENCHANTMENT_GRINDSTONE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChiseledBookshelfInteracted(event: PlayerInteractEvent) {
        if (
            !event.action.isRightClick ||
                event.hand == null ||
                event.useInteractedBlock() == Event.Result.DENY ||
                event.item?.type != Material.ENCHANTED_BOOK
        )
            return
        val block = event.clickedBlock ?: return
        val player = event.player
        val shelf = block.state as? ChiseledBookshelf ?: return
        if (
            !tracking.test(player, BingoTask.FILL_CHISELED_BOOKSHELF_ENCHANTED) ||
                !hasExactlyFiveEnchantedBooks(shelf.inventory)
        )
            return
        val emptySlot = onlyEmptySlot(shelf.inventory.storageContents)
        val interactionPoint = event.interactionPoint ?: return
        if (
            emptySlot < 0 || shelf.getSlot(interactionPoint.toVector().subtract(block.location.toVector())) != emptySlot
        )
            return
        val playerId = player.uniqueId
        val token = attemptToken(playerId)
        val shelfKey = BlockKey.from(block)
        Bukkit.getScheduler()
            .runTask(plugin, Runnable { confirmEnchantedBookshelf(playerId, shelfKey, emptySlot, token) })
    }

    private fun confirmEnchantedBookshelf(playerId: UUID, shelfKey: BlockKey, insertedSlot: Int, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val world = Bukkit.getWorld(shelfKey.worldId()) ?: return
        if (!isCurrent(playerId, token) || !tracking.test(player, BingoTask.FILL_CHISELED_BOOKSHELF_ENCHANTED)) return
        val shelf = world.getBlockAt(shelfKey.x(), shelfKey.y(), shelfKey.z()).state as? ChiseledBookshelf ?: return
        if (shelf.lastInteractedSlot == insertedSlot && hasSixEnchantedBooks(shelf.inventory)) {
            completion.accept(player, BingoTask.FILL_CHISELED_BOOKSHELF_ENCHANTED)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerArmorChanged(event: PlayerArmorChangeEvent) {
        val player = event.player
        if (!tracking.test(player, BingoTask.WEAR_FOUR_ARMOUR_MATERIALS)) return
        val playerId = player.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmFourArmorFamilies(playerId, token) })
    }

    private fun confirmFourArmorFamilies(playerId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId) ?: return
        if (!isCurrent(playerId, token) || !tracking.test(player, BingoTask.WEAR_FOUR_ARMOUR_MATERIALS)) return
        val inventory = player.inventory
        if (hasFourDistinctArmorFamilies(inventory.helmet, inventory.chestplate, inventory.leggings, inventory.boots)) {
            completion.accept(player, BingoTask.WEAR_FOUR_ARMOUR_MATERIALS)
        }
    }

    enum class ArmorFamily {
        LEATHER,
        CHAINMAIL,
        COPPER,
        IRON,
        GOLD,
        DIAMOND,
        NETHERITE,
        TURTLE,
    }

    companion object {
        @JvmStatic
        fun isResultTakeAction(action: InventoryAction): Boolean =
            when (action) {
                InventoryAction.PICKUP_ALL,
                InventoryAction.PICKUP_SOME,
                InventoryAction.PICKUP_HALF,
                InventoryAction.PICKUP_ONE,
                InventoryAction.DROP_ALL_SLOT,
                InventoryAction.DROP_ONE_SLOT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY,
                InventoryAction.HOTBAR_MOVE_AND_READD,
                InventoryAction.HOTBAR_SWAP -> true
                else -> false
            }

        @JvmStatic
        fun removesNonCurseEnchantment(upperInput: ItemStack?, lowerInput: ItemStack?, result: ItemStack?): Boolean {
            val inputEnchantments = HashSet<Enchantment>()
            inputEnchantments.addAll(enchantments(upperInput))
            inputEnchantments.addAll(enchantments(lowerInput))
            val resultEnchantments = enchantments(result)
            return inputEnchantments.any { !it.isCursed && !resultEnchantments.contains(it) }
        }

        private fun enchantments(item: ItemStack?): Set<Enchantment> {
            if (isEmpty(item)) return emptySet()
            val enchantments = HashSet(item!!.enchantments.keys)
            val meta = item.itemMeta
            if (meta is EnchantmentStorageMeta) enchantments.addAll(meta.storedEnchants.keys)
            return enchantments
        }

        @JvmStatic
        fun hasExactlyFiveEnchantedBooks(inventory: ChiseledBookshelfInventory): Boolean =
            hasEnchantedBookLayout(inventory.storageContents, 5, 1)

        @JvmStatic
        fun hasSixEnchantedBooks(inventory: ChiseledBookshelfInventory): Boolean =
            hasEnchantedBookLayout(inventory.storageContents, 6, 0)

        @JvmStatic
        fun hasEnchantedBookLayout(
            contents: Array<out ItemStack?>?,
            expectedBooks: Int,
            expectedEmptySlots: Int,
        ): Boolean = hasEnchantedBookMaterialLayout(materialContents(contents), expectedBooks, expectedEmptySlots)

        @JvmStatic
        fun hasEnchantedBookMaterialLayout(
            contents: Array<out Material?>?,
            expectedBooks: Int,
            expectedEmptySlots: Int,
        ): Boolean {
            if (contents == null || contents.size != 6) return false
            var books = 0
            var emptySlots = 0
            for (material in contents) {
                if (isAirMaterial(material)) emptySlots++
                else if (material == Material.ENCHANTED_BOOK) books++ else return false
            }
            return books == expectedBooks && emptySlots == expectedEmptySlots
        }

        @JvmStatic
        fun onlyEmptySlot(contents: Array<out ItemStack?>?): Int = onlyEmptyMaterialSlot(materialContents(contents))

        @JvmStatic
        fun onlyEmptyMaterialSlot(contents: Array<out Material?>?): Int {
            if (contents == null || contents.size != 6) return -1
            var emptySlot = -1
            for (slot in contents.indices) {
                if (!isAirMaterial(contents[slot])) continue
                if (emptySlot >= 0) return -1
                emptySlot = slot
            }
            return emptySlot
        }

        @JvmStatic
        fun hasFourDistinctArmorFamilies(
            helmet: ItemStack?,
            chestplate: ItemStack?,
            leggings: ItemStack?,
            boots: ItemStack?,
        ): Boolean =
            hasFourDistinctArmorMaterialFamilies(
                materialType(helmet),
                materialType(chestplate),
                materialType(leggings),
                materialType(boots),
            )

        @JvmStatic
        fun hasFourDistinctArmorMaterialFamilies(
            helmet: Material?,
            chestplate: Material?,
            leggings: Material?,
            boots: Material?,
        ): Boolean {
            val helmetFamily = armorFamily(helmet, EquipmentSlot.HEAD) ?: return false
            val chestFamily = armorFamily(chestplate, EquipmentSlot.CHEST) ?: return false
            val legFamily = armorFamily(leggings, EquipmentSlot.LEGS) ?: return false
            val bootFamily = armorFamily(boots, EquipmentSlot.FEET) ?: return false
            return EnumSet.of(helmetFamily, chestFamily, legFamily, bootFamily).size == 4
        }

        @JvmStatic
        fun armorFamily(material: Material?, expectedSlot: EquipmentSlot): ArmorFamily? =
            when (material) {
                Material.LEATHER_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.LEATHER else null
                Material.LEATHER_CHESTPLATE -> if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.LEATHER else null
                Material.LEATHER_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.LEATHER else null
                Material.LEATHER_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.LEATHER else null
                Material.CHAINMAIL_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.CHAINMAIL else null
                Material.CHAINMAIL_CHESTPLATE ->
                    if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.CHAINMAIL else null
                Material.CHAINMAIL_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.CHAINMAIL else null
                Material.CHAINMAIL_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.CHAINMAIL else null
                Material.COPPER_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.COPPER else null
                Material.COPPER_CHESTPLATE -> if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.COPPER else null
                Material.COPPER_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.COPPER else null
                Material.COPPER_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.COPPER else null
                Material.IRON_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.IRON else null
                Material.IRON_CHESTPLATE -> if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.IRON else null
                Material.IRON_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.IRON else null
                Material.IRON_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.IRON else null
                Material.GOLDEN_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.GOLD else null
                Material.GOLDEN_CHESTPLATE -> if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.GOLD else null
                Material.GOLDEN_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.GOLD else null
                Material.GOLDEN_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.GOLD else null
                Material.DIAMOND_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.DIAMOND else null
                Material.DIAMOND_CHESTPLATE -> if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.DIAMOND else null
                Material.DIAMOND_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.DIAMOND else null
                Material.DIAMOND_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.DIAMOND else null
                Material.NETHERITE_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.NETHERITE else null
                Material.NETHERITE_CHESTPLATE ->
                    if (expectedSlot == EquipmentSlot.CHEST) ArmorFamily.NETHERITE else null
                Material.NETHERITE_LEGGINGS -> if (expectedSlot == EquipmentSlot.LEGS) ArmorFamily.NETHERITE else null
                Material.NETHERITE_BOOTS -> if (expectedSlot == EquipmentSlot.FEET) ArmorFamily.NETHERITE else null
                Material.TURTLE_HELMET -> if (expectedSlot == EquipmentSlot.HEAD) ArmorFamily.TURTLE else null
                else -> null
            }

        private fun materialContents(contents: Array<out ItemStack?>?): Array<Material?>? = contents?.let {
            Array(it.size) { slot -> materialType(it[slot]) }
        }

        private fun materialType(item: ItemStack?): Material? = if (isEmpty(item)) null else item!!.type

        private fun isAirMaterial(material: Material?): Boolean =
            material == null ||
                material == Material.AIR ||
                material == Material.CAVE_AIR ||
                material == Material.VOID_AIR

        private fun isEmpty(item: ItemStack?): Boolean = item == null || item.isEmpty || item.type.isAir
    }
}
