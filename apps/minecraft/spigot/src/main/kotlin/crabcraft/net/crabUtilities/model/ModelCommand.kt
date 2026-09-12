package crabcraft.net.crabUtilities.model

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.logging.Level

/** Implements reversible Nexo model merging for a helmet held beside its cosmetic token. */
class ModelCommand private constructor(private val plugin: JavaPlugin, private val nexoItems: NexoItemLookup?) :
    CommandExecutor, TabCompleter, Listener {
    private val codec = MergedModelCodec(plugin)

    private enum class DestinationKind { MAIN_HAND, OFF_HAND, STORAGE }

    private data class Destination(val kind: DestinationKind, val slot: Int) {
        fun kind(): DestinationKind = kind
        fun slot(): Int = slot
        companion object {
            @JvmStatic fun mainHand(): Destination = Destination(DestinationKind.MAIN_HAND, -1)
            @JvmStatic fun offHand(): Destination = Destination(DestinationKind.OFF_HAND, -1)
            @JvmStatic fun storage(slot: Int): Destination = Destination(DestinationKind.STORAGE, slot)
        }
    }

    private data class HeldPair(val target: ItemStack, val targetInMainHand: Boolean,
        val cosmetic: ItemStack, val cosmeticId: String) {
        fun target(): ItemStack = target
        fun targetInMainHand(): Boolean = targetInMainHand
        fun cosmetic(): ItemStack = cosmetic
        fun cosmeticId(): String = cosmeticId
    }

    private class MergeInputException(message: String) : Exception(message)

    /** Returns the listener that protects embedded cosmetics across item transformations. */
    fun protectionListener(): Listener = MergedModelProtectionListener(codec)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("Only players can use /model."))
            return true
        }
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }
        when (args[0].lowercase(Locale.ROOT)) {
            "merge" -> if (args.size != 1) sendUsage(sender) else if (hasPermission(sender, MERGE_PERMISSION)) merge(sender)
            "split" -> if (args.size != 1) sendUsage(sender) else if (hasPermission(sender, SPLIT_PERMISSION)) split(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun merge(player: Player) {
        val pair = try {
            inspectHeldPair(player)
        } catch (exception: MergeInputException) {
            player.sendMessage(CrabMessages.error(exception.message!!))
            return
        }
        val merged = try {
            codec.merge(pair.target(), pair.cosmetic(), pair.cosmeticId())
        } catch (exception: RuntimeException) {
            plugin.logger.warning("Could not merge items for ${player.uniqueId}: ${exception.message}")
            player.sendMessage(CrabMessages.error("Merge failed; nothing changed."))
            return
        }
        val cosmeticName = itemName(pair.cosmetic())
        val targetName = itemName(pair.target())
        val cosmeticRemainder = decrement(pair.cosmetic())
        val inventory = player.inventory
        val newMainHand = if (pair.targetInMainHand()) merged else cosmeticRemainder
        val newOffHand = if (pair.targetInMainHand()) cosmeticRemainder else merged
        if (!commitHands(player, inventory, newMainHand, newOffHand, "merge")) {
            player.sendMessage(CrabMessages.error("Merge failed; nothing changed."))
            return
        }
        player.sendMessage(CrabMessages.success("Merged ").append(cosmeticName)
            .append(CrabMessages.muted(" → ")).append(targetName).append(CrabMessages.success(".")))
    }

    private fun split(player: Player) {
        val inventory = player.inventory
        val mainHand = inventory.itemInMainHand
        val offHand = inventory.itemInOffHand
        val mainMerged = codec.isMerged(mainHand)
        val offMerged = codec.isMerged(offHand)
        if (!mainMerged && !offMerged) {
            player.sendMessage(CrabMessages.error("Hold one merged helmet."))
            return
        }
        if (mainMerged && offMerged) {
            player.sendMessage(CrabMessages.error("Hold only one merged helmet."))
            return
        }
        val mergedInMainHand = mainMerged
        val merged = if (mergedInMainHand) mainHand else offHand
        if (merged.amount != 1) {
            player.sendMessage(CrabMessages.error("Split one merged helmet at a time."))
            return
        }
        val stored: MergedModelCodec.StoredItems
        val restored: ItemStack
        try {
            stored = codec.read(merged)
            restored = codec.restoreTarget(merged, stored)
        } catch (exception: MergedModelCodec.CorruptMergedItemException) {
            plugin.logger.warning("Could not read merged item held by ${player.uniqueId}: ${exception.message}")
            player.sendMessage(CrabMessages.error("Stored model data is damaged; nothing changed."))
            return
        } catch (exception: RuntimeException) {
            plugin.logger.warning("Could not restore merged item held by ${player.uniqueId}: ${exception.message}")
            player.sendMessage(CrabMessages.error("Split failed; nothing changed."))
            return
        }
        val cosmetic = stored.cosmetic().asOne()
        val destination = findDestination(inventory, mergedInMainHand, cosmetic)
        if (destination == null) {
            player.sendMessage(CrabMessages.error("Make room for the returned cosmetic."))
            return
        }
        val cosmeticName = itemName(cosmetic)
        val targetName = itemName(restored)
        if (!commitSplit(player, inventory, mergedInMainHand, destination, cosmetic, restored)) {
            player.sendMessage(CrabMessages.error("Split failed; nothing changed."))
            return
        }
        player.sendMessage(CrabMessages.success("Restored ").append(targetName)
            .append(CrabMessages.muted(" + ")).append(cosmeticName).append(CrabMessages.success(".")))
    }

    private fun inspectHeldPair(player: Player): HeldPair {
        if (!isNexoAvailable()) throw MergeInputException("Custom-item support is unavailable.")
        val inventory = player.inventory
        val mainHand = inventory.itemInMainHand
        val offHand = inventory.itemInOffHand
        if (codec.isMerged(mainHand) || codec.isMerged(offHand)) {
            throw MergeInputException("One item is already merged. Split it first.")
        }
        val mainId: String?
        val offId: String?
        try {
            mainId = if (mainHand.isEmpty) null else nexoItems!!.idFromItem(mainHand)
            offId = if (offHand.isEmpty) null else nexoItems!!.idFromItem(offHand)
        } catch (exception: LinkageError) {
            plugin.logger.warning("Nexo could not inspect held items: ${exception.message}")
            throw MergeInputException("Couldn't inspect those custom items. Try again shortly.")
        } catch (exception: RuntimeException) {
            plugin.logger.warning("Nexo could not inspect held items: ${exception.message}")
            throw MergeInputException("Couldn't inspect those custom items. Try again shortly.")
        }
        val mainIsNexo = !mainId.isNullOrBlank()
        val offIsNexo = !offId.isNullOrBlank()
        if (mainIsNexo == offIsNexo) {
            throw MergeInputException("Hold one custom cosmetic and one helmet, one in each hand.")
        }
        val cosmetic = if (mainIsNexo) mainHand else offHand
        val target = if (mainIsNexo) offHand else mainHand
        val cosmeticId = if (mainIsNexo) mainId else offId!!
        if (target.isEmpty) throw MergeInputException("Hold a helmet in your other hand.")
        if (target.amount != 1) throw MergeInputException("Hold one helmet, not a stack.")
        if (!codec.isHeadTarget(target)) throw MergeInputException("The other item must be head-slot equipment.")
        if (!codec.hasApplicableModel(cosmetic)) throw MergeInputException("That custom item has no usable resource-pack model.")
        if (!codec.hasCompatibleEquipmentModel(cosmetic)) {
            throw MergeInputException("That custom item uses a different equipment slot.")
        }
        return HeldPair(target, !mainIsNexo, cosmetic, cosmeticId)
    }

    private fun commitHands(player: Player, inventory: PlayerInventory, newMainHand: ItemStack,
        newOffHand: ItemStack, operation: String): Boolean {
        val oldMainHand = inventory.itemInMainHand.clone()
        val oldOffHand = inventory.itemInOffHand.clone()
        try {
            inventory.setItemInMainHand(newMainHand)
            inventory.setItemInOffHand(newOffHand)
            return true
        } catch (exception: RuntimeException) {
            rollbackHands(player, inventory, oldMainHand, oldOffHand, operation)
            plugin.logger.log(Level.SEVERE, "Could not commit model $operation for ${player.uniqueId}", exception)
            return false
        }
    }

    private fun commitSplit(player: Player, inventory: PlayerInventory, mergedInMainHand: Boolean,
        destination: Destination, cosmetic: ItemStack, restored: ItemStack): Boolean {
        val oldMainHand = inventory.itemInMainHand.clone()
        val oldOffHand = inventory.itemInOffHand.clone()
        val oldStorage = if (destination.kind() == DestinationKind.STORAGE) cloneOrNull(inventory.getItem(destination.slot())) else null
        try {
            placeReturnedItem(inventory, destination, cosmetic)
            if (mergedInMainHand) inventory.setItemInMainHand(restored) else inventory.setItemInOffHand(restored)
            return true
        } catch (exception: RuntimeException) {
            if (destination.kind() == DestinationKind.STORAGE) {
                try {
                    inventory.setItem(destination.slot(), oldStorage)
                } catch (rollbackException: RuntimeException) {
                    plugin.logger.log(Level.SEVERE, "Could not roll back model split storage for ${player.uniqueId}", rollbackException)
                }
            }
            rollbackHands(player, inventory, oldMainHand, oldOffHand, "split")
            plugin.logger.log(Level.SEVERE, "Could not commit model split for ${player.uniqueId}", exception)
            return false
        }
    }

    private fun rollbackHands(player: Player, inventory: PlayerInventory, oldMainHand: ItemStack,
        oldOffHand: ItemStack, operation: String) {
        try {
            inventory.setItemInMainHand(oldMainHand)
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE, "Could not roll back main hand after model $operation for ${player.uniqueId}", exception)
        }
        try {
            inventory.setItemInOffHand(oldOffHand)
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE, "Could not roll back off-hand after model $operation for ${player.uniqueId}", exception)
        }
    }

    private fun sendUsage(player: Player) {
        var sent = false
        if (isNexoAvailable() && player.hasPermission(MERGE_PERMISSION)) {
            player.sendMessage(CrabMessages.highlight("/model merge").append(CrabMessages.muted(" — apply held custom model")))
            sent = true
        }
        if (player.hasPermission(SPLIT_PERMISSION)) {
            player.sendMessage(CrabMessages.highlight("/model split").append(CrabMessages.muted(" — restore helmet and cosmetic")))
            sent = true
        }
        if (!sent) player.sendMessage(CrabMessages.error("You do not have permission to use this command."))
    }

    private fun isNexoAvailable(): Boolean {
        val nexo = plugin.server.pluginManager.getPlugin("Nexo")
        return nexoItems != null && nexo != null && nexo.isEnabled
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase(Locale.ROOT)
        val options = ArrayList<String>(2)
        if (isNexoAvailable() && sender.hasPermission(MERGE_PERMISSION) && "merge".startsWith(prefix)) options.add("merge")
        if (sender.hasPermission(SPLIT_PERMISSION) && "split".startsWith(prefix)) options.add("split")
        return options
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerItemBreak(event: PlayerItemBreakEvent) {
        val broken = event.brokenItem
        if (!codec.isMerged(broken)) return
        val cosmetic = try {
            codec.read(broken).cosmetic().asOne()
        } catch (exception: MergedModelCodec.CorruptMergedItemException) {
            plugin.logger.log(Level.SEVERE,
                "Could not recover the cosmetic from a broken merged item belonging to ${event.player.uniqueId}", exception)
            event.player.sendMessage(CrabMessages.error("Helmet broke; cosmetic recovery failed."))
            return
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE,
                "Could not recover the cosmetic from a broken merged item belonging to ${event.player.uniqueId}", exception)
            event.player.sendMessage(CrabMessages.error("Helmet broke; cosmetic recovery failed."))
            return
        }
        val player = event.player
        val destination = findStorageDestination(player.inventory, cosmetic)
        if (destination != null) {
            try {
                placeReturnedItem(player.inventory, destination, cosmetic)
                player.sendMessage(CrabMessages.warning("Recovered ").append(itemName(cosmetic))
                    .append(CrabMessages.warning(" from your broken helmet.")))
                return
            } catch (exception: RuntimeException) {
                plugin.logger.log(Level.SEVERE, "Could not return the cosmetic from a broken item to ${player.uniqueId}", exception)
            }
        }
        player.world.dropItemNaturally(player.location, cosmetic)
        player.sendMessage(CrabMessages.warning("Helmet broke; dropped ").append(itemName(cosmetic))
            .append(CrabMessages.warning(" at your feet.")))
    }

    companion object {
        private const val MERGE_PERMISSION = "crabutilities.model.merge"
        private const val SPLIT_PERMISSION = "crabutilities.model.split"

        /** Creates the command while keeping the optional Nexo API behind a guarded class boundary. */
        @JvmStatic
        fun create(plugin: JavaPlugin): ModelCommand {
            val nexo = plugin.server.pluginManager.getPlugin("Nexo")
            if (nexo == null || !nexo.isEnabled) {
                plugin.logger.info("Nexo not detected — model merging is unavailable; splitting remains enabled.")
                return ModelCommand(plugin, null)
            }
            try {
                val lookup = NexoItemBridge.create()
                plugin.logger.info("Nexo detected — reversible model merging enabled.")
                return ModelCommand(plugin, lookup)
            } catch (error: LinkageError) {
                plugin.logger.warning("Nexo is present but its API could not be loaded; model merging is unavailable: ${error.message}")
                return ModelCommand(plugin, null)
            }
        }

        @JvmStatic
        private fun decrement(item: ItemStack): ItemStack {
            if (item.amount == 1) return ItemStack.empty()
            val remainder = item.clone()
            remainder.amount = item.amount - 1
            return remainder
        }

        @JvmStatic
        private fun cloneOrNull(item: ItemStack?): ItemStack? = item?.clone()

        @JvmStatic
        private fun findDestination(inventory: PlayerInventory, mergedInMainHand: Boolean, returned: ItemStack): Destination? {
            val otherHand = if (mergedInMainHand) inventory.itemInOffHand else inventory.itemInMainHand
            if (canAccept(inventory, otherHand, returned)) {
                return if (mergedInMainHand) Destination.offHand() else Destination.mainHand()
            }
            val storage = inventory.storageContents
            for (slot in storage.indices) {
                if (canAccept(inventory, storage[slot], returned)) return Destination.storage(slot)
            }
            return null
        }

        @JvmStatic
        private fun canAccept(inventory: PlayerInventory, existing: ItemStack?, incoming: ItemStack): Boolean {
            if (existing == null || existing.isEmpty) return incoming.amount <= Math.min(inventory.maxStackSize, incoming.maxStackSize)
            if (!existing.isSimilar(incoming)) return false
            val maximum = Math.min(inventory.maxStackSize, Math.min(existing.maxStackSize, incoming.maxStackSize))
            return existing.amount.toLong() + incoming.amount <= maximum
        }

        @JvmStatic
        private fun placeReturnedItem(inventory: PlayerInventory, destination: Destination, returned: ItemStack) {
            when (destination.kind()) {
                DestinationKind.MAIN_HAND -> inventory.setItemInMainHand(combine(inventory.itemInMainHand, returned))
                DestinationKind.OFF_HAND -> inventory.setItemInOffHand(combine(inventory.itemInOffHand, returned))
                DestinationKind.STORAGE -> inventory.setItem(destination.slot(), combine(inventory.getItem(destination.slot()), returned))
            }
        }

        @JvmStatic
        private fun combine(existing: ItemStack?, incoming: ItemStack): ItemStack {
            if (existing == null || existing.isEmpty) return incoming.asOne()
            val combined = existing.clone()
            combined.amount = existing.amount + incoming.amount
            return combined
        }

        @JvmStatic
        private fun itemName(item: ItemStack): Component = Component.text().color(CrabMessages.HIGHLIGHT)
            .decoration(TextDecoration.ITALIC, false).append(item.effectiveName()).hoverEvent(item.asHoverEvent { it }).build()

        @JvmStatic
        private fun hasPermission(player: Player, permission: String): Boolean {
            if (player.hasPermission(permission)) return true
            player.sendMessage(CrabMessages.error("You do not have permission to use that command."))
            return false
        }

        @JvmStatic
        private fun findStorageDestination(inventory: PlayerInventory, returned: ItemStack): Destination? {
            val storage = inventory.storageContents
            for (slot in storage.indices) {
                if (canAccept(inventory, storage[slot], returned)) return Destination.storage(slot)
            }
            return null
        }
    }
}
