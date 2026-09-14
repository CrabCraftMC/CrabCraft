package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.inventory.ItemCraftedEvent
import java.util.EnumSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.SmithItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.SmithingInventory
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Inventory, crafting and per-player progress detectors for Bingo #6. */
class BingoCardSixMechanicsListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier
) : BingoDetector {
    private val progressCardKey = NamespacedKey(plugin, "bingo6_progress_card")
    private val fishFlagsKey = NamespacedKey(plugin, "bingo6_fish_flags")
    private val enchantedItemsKey = NamespacedKey(plugin, "bingo6_enchanted_items")
    private val playerGenerations = HashMap<UUID, Long>()
    private val pendingPotCrafts = HashMap<UUID, PendingResult>()
    private val pendingSmithingDrops = HashMap<UUID, PendingResult>()
    private var detectorGeneration = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFishCaught(event: PlayerFishEvent) {
        val player = event.player
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val item = event.caught as? Item ?: return
        if (!tracking.test(player, BingoTask.FISH_TREASURE_AND_JUNK)) return

        val category = fishingCategory(item.itemStack)
        if (category == 0) return
        val data = cardProgress(player)
        val flags = data.getOrDefault(fishFlagsKey, PersistentDataType.INTEGER, 0) or category
        data.set(fishFlagsKey, PersistentDataType.INTEGER, flags)
        if (flags == ALL_FISH_CATEGORIES) {
            completion.accept(player, BingoTask.FISH_TREASURE_AND_JUNK)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemEnchanted(event: EnchantItemEvent) {
        val player = event.enchanter
        if (event.enchantsToAdd.isEmpty() || !tracking.test(player, BingoTask.ENCHANT_FIVE_ITEMS)) return
        val data = cardProgress(player)
        val materials = decodeMaterials(data.getOrDefault(enchantedItemsKey, PersistentDataType.STRING, ""))
        materials.add(event.item.type)
        data.set(enchantedItemsKey, PersistentDataType.STRING, encodeMaterials(materials))
        if (materials.size >= 5) completion.accept(player, BingoTask.ENCHANT_FIVE_ITEMS)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnderChestClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.topInventory.type != InventoryType.ENDER_CHEST
            || !canChangeInventory(event.action)
            || !tracking.test(player, BingoTask.FILL_ENDER_CHEST)
            || occupiedSlots(event.view.topInventory) >= 27) return
        scheduleEnderChestCheck(player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnderChestDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.view.topInventory.type != InventoryType.ENDER_CHEST
            || event.rawSlots.none { it < 27 }
            || !tracking.test(player, BingoTask.FILL_ENDER_CHEST)
            || occupiedSlots(event.view.topInventory) >= 27) return
        scheduleEnderChestCheck(player)
    }

    private fun scheduleEnderChestCheck(player: Player) {
        val playerId = player.uniqueId
        val token = tokenFor(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val current = Bukkit.getPlayer(playerId)
            if (current != null
                && isCurrent(playerId, token)
                && tracking.test(current, BingoTask.FILL_ENDER_CHEST)
                && occupiedSlots(current.enderChest) == 27) {
                completion.accept(current, BingoTask.FILL_ENDER_CHEST)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSmithingResultTaken(event: SmithItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== event.inventory
            || event.slotType != InventoryType.SlotType.RESULT
            || !isResultTakeAction(event.action)
            || !tracking.test(player, BingoTask.APPLY_ARMOUR_TRIM)) return

        val inventory = event.inventory
        val before = inventory.inputEquipment
        val result = event.currentItem
        if (!appliesNewTrim(before, result)) return
        val expectedResult = result!!.clone()
        val inputs = smithingInputs(inventory)
        val playerId = player.uniqueId
        val token = tokenFor(playerId)
        if (event.action != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            val pending = PendingResult(expectedResult, token)
            pendingSmithingDrops[playerId] = pending
            Bukkit.getScheduler().runTask(plugin, Runnable { pendingSmithingDrops.remove(playerId, pending) })
        }
        if (isDropResultAction(event.action)) return

        val resultCountBefore = matchingItemCount(player, expectedResult)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val current = Bukkit.getPlayer(playerId)
            if (current != null
                && isCurrent(playerId, token)
                && tracking.test(current, BingoTask.APPLY_ARMOUR_TRIM)
                && resultCountIncreased(resultCountBefore, matchingItemCount(current, expectedResult))
                && everyInputWasConsumed(inputs, smithingInputs(inventory))) {
                completion.accept(current, BingoTask.APPLY_ARMOUR_TRIM)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSmithingResultDropped(event: PlayerDropItemEvent) {
        val player = event.player
        val pending = pendingSmithingDrops.remove(player.uniqueId)
        if (pending != null
            && isCurrent(player.uniqueId, pending.token())
            && event.itemDrop.itemStack.isSimilar(pending.result())
            && tracking.test(player, BingoTask.APPLY_ARMOUR_TRIM)) {
            completion.accept(player, BingoTask.APPLY_ARMOUR_TRIM)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDecoratedPotCraftAttempt(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!isResultTakeAction(event.action)) return
        val result = event.currentItem ?: return
        if (result.type != Material.DECORATED_POT) return
        val crafting = event.inventory as? CraftingInventory ?: return
        if (!tracking.test(player, BingoTask.FOUR_SHERD_DECORATED_POT)) return
        val matrix = crafting.matrix
        if (!hasFourDistinctSherds(matrix)) return

        val playerId = player.uniqueId
        val token = tokenFor(playerId)
        val pending = PendingResult(result.clone(), token)
        pendingPotCrafts[playerId] = pending
        Bukkit.getScheduler().runTask(plugin, Runnable { pendingPotCrafts.remove(playerId, pending) })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onItemCrafted(event: ItemCraftedEvent) {
        val player = event.player
        val pending = pendingPotCrafts.remove(player.uniqueId)
        val crafted = event.craftedItem
        if (pending != null
            && crafted.type == Material.DECORATED_POT
            && crafted.isSimilar(pending.result())
            && isCurrent(player.uniqueId, pending.token())
            && tracking.test(player, BingoTask.FOUR_SHERD_DECORATED_POT)) {
            completion.accept(player, BingoTask.FOUR_SHERD_DECORATED_POT)
        }
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        pendingPotCrafts.remove(playerId)
        pendingSmithingDrops.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        if (player != null) clearPlayerProgress(player)
    }

    override fun clear() {
        detectorGeneration++
        playerGenerations.clear()
        pendingPotCrafts.clear()
        pendingSmithingDrops.clear()
    }

    private fun cardProgress(player: Player): PersistentDataContainer {
        val data = player.persistentDataContainer
        val cardId = activeCardId.asInt
        val storedCardId = data.get(progressCardKey, PersistentDataType.INTEGER)
        if (storedCardId == null || storedCardId != cardId) {
            data.set(progressCardKey, PersistentDataType.INTEGER, cardId)
            data.remove(fishFlagsKey)
            data.remove(enchantedItemsKey)
        }
        return data
    }

    private fun clearPlayerProgress(player: Player) {
        val data = player.persistentDataContainer
        data.remove(progressCardKey)
        data.remove(fishFlagsKey)
        data.remove(enchantedItemsKey)
    }

    private fun tokenFor(playerId: UUID): AttemptToken =
        AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        detectorGeneration == token.detectorGeneration()
            && playerGenerations.getOrDefault(playerId, 0L) == token.playerGeneration()

    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }

    private data class PendingResult(private val result: ItemStack, private val token: AttemptToken) {
        fun result(): ItemStack = result
        fun token(): AttemptToken = token
    }

    data class StackSnapshot(private val material: Material, private val amount: Int) {
        fun material(): Material = material
        fun amount(): Int = amount
    }

    companion object {
        private const val FISH_TREASURE = 1
        private const val FISH_JUNK = 2
        private const val ALL_FISH_CATEGORIES = FISH_TREASURE or FISH_JUNK
        private val TREASURE_ITEMS: Set<Material> = EnumSet.of(
            Material.BOW, Material.ENCHANTED_BOOK, Material.NAME_TAG, Material.NAUTILUS_SHELL, Material.SADDLE)
        private val JUNK_ITEMS: Set<Material> = EnumSet.of(
            Material.LILY_PAD, Material.BOWL, Material.LEATHER, Material.LEATHER_BOOTS,
            Material.ROTTEN_FLESH, Material.STICK, Material.STRING, Material.POTION,
            Material.BONE, Material.INK_SAC, Material.TRIPWIRE_HOOK, Material.BAMBOO)

        @JvmStatic
        fun fishingCategory(item: ItemStack): Int = fishingCategory(item.type, item.enchantments.isNotEmpty())

        @JvmStatic
        fun fishingCategory(material: Material, enchanted: Boolean): Int {
            if (material == Material.FISHING_ROD) {
                // Vanilla has a damaged, unenchanted rod in junk and an enchanted rod
                // in treasure, so Material alone is deliberately not enough here.
                return if (enchanted) FISH_TREASURE else FISH_JUNK
            }
            if (TREASURE_ITEMS.contains(material)) return FISH_TREASURE
            if (JUNK_ITEMS.contains(material)) return FISH_JUNK
            return 0
        }

        @JvmStatic
        fun decodeMaterials(encoded: String?): MutableSet<Material> {
            if (encoded.isNullOrBlank()) return EnumSet.noneOf(Material::class.java)
            return encoded.split(",").mapNotNull(Material::matchMaterial).toCollection(EnumSet.noneOf(Material::class.java))
        }

        @JvmStatic
        fun encodeMaterials(materials: Set<Material>): String = materials.map { it.name }.sorted().joinToString(",")

        @JvmStatic
        fun occupiedSlots(inventory: Inventory): Int {
            var occupied = 0
            for (item in inventory.storageContents) {
                if (item != null && !item.type.isAir) occupied++
            }
            return occupied
        }

        @JvmStatic
        fun appliesNewTrim(before: ItemStack?, result: ItemStack?): Boolean {
            val resultMeta = result?.itemMeta as? ArmorMeta ?: return false
            if (!resultMeta.hasTrim()) return false
            val beforeMeta = before?.itemMeta as? ArmorMeta ?: return true
            if (!beforeMeta.hasTrim()) return true
            return beforeMeta.trim != resultMeta.trim
        }

        @JvmStatic
        fun hasFourDistinctSherds(matrix: Array<out ItemStack?>): Boolean {
            val materials = arrayOfNulls<Material>(matrix.size)
            for (index in matrix.indices) {
                val item = matrix[index]
                materials[index] = if (item == null || item.type.isAir) null else item.type
            }
            return hasFourDistinctSherdMaterials(materials)
        }

        @JvmStatic
        fun hasFourDistinctSherdMaterials(matrix: Array<out Material?>): Boolean {
            val sherds = HashSet<Material>()
            var sherdSlots = 0
            for (material in matrix) {
                if (material == null) continue
                if (!material.name.endsWith("_POTTERY_SHERD")) return false
                sherdSlots++
                sherds.add(material)
            }
            return sherdSlots == 4 && sherds.size == 4
        }

        @JvmStatic
        fun smithingInputs(inventory: SmithingInventory): Array<StackSnapshot?> = snapshots(arrayOf(
            inventory.inputTemplate, inventory.inputEquipment, inventory.inputMineral))

        @JvmStatic
        fun snapshots(items: Array<out ItemStack?>): Array<StackSnapshot?> {
            val snapshots = arrayOfNulls<StackSnapshot>(items.size)
            for (index in items.indices) {
                val item = items[index]
                if (item != null && !item.type.isAir) snapshots[index] = StackSnapshot(item.type, item.amount)
            }
            return snapshots
        }

        @JvmStatic
        fun everyInputWasConsumed(before: Array<out StackSnapshot?>, after: Array<out StackSnapshot?>): Boolean {
            if (before.size != after.size) return false
            var hadInput = false
            for (index in before.indices) {
                val previous = before[index] ?: continue
                hadInput = true
                val current = after[index]
                if (current != null
                    && (current.material() != previous.material() || current.amount() >= previous.amount())) return false
            }
            return hadInput
        }

        @JvmStatic
        fun matchingItemCount(player: Player, expected: ItemStack): Int {
            var count = matchingItemCount(player.inventory.storageContents, expected)
            count += matchingItemCount(player.inventory.armorContents, expected)
            val offhand = player.inventory.itemInOffHand
            if (offhand.isSimilar(expected)) count += offhand.amount
            val cursor: ItemStack? = player.openInventory.cursor
            if (cursor != null && cursor.isSimilar(expected)) count += cursor.amount
            return count
        }

        @JvmStatic
        fun matchingItemCount(items: Array<out ItemStack?>, expected: ItemStack): Int {
            var count = 0
            for (item in items) {
                if (item != null && item.isSimilar(expected)) count += item.amount
            }
            return count
        }

        @JvmStatic
        fun resultCountIncreased(before: Int, after: Int): Boolean = before >= 0 && after > before

        @JvmStatic
        fun canChangeInventory(action: InventoryAction): Boolean =
            action != InventoryAction.NOTHING && action != InventoryAction.CLONE_STACK && action != InventoryAction.UNKNOWN

        @JvmStatic
        fun isResultTakeAction(action: InventoryAction): Boolean = when (action) {
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.HOTBAR_MOVE_AND_READD,
            InventoryAction.HOTBAR_SWAP -> true
            else -> false
        }

        @JvmStatic
        fun isDropResultAction(action: InventoryAction): Boolean =
            action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT
    }
}
