package crabcraft.net.crabUtilities.bingo

import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.LlamaInventory
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** Mob interaction and exact-entity attribution detectors for Bingo #6. */
class BingoCardSixMobListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier,
) : AbstractBingoDetector() {
    private val mendingOwnerKey = NamespacedKey(plugin, "bingo6_mending_owner")
    private val mendingDroppedAtKey = NamespacedKey(plugin, "bingo6_mending_dropped")
    private val mendingCardIdKey = NamespacedKey(plugin, "bingo6_mending_card")
    private val poisonOwnerKey = NamespacedKey(plugin, "bingo6_poison_owner")
    private val poisonThrownAtKey = NamespacedKey(plugin, "bingo6_poison_thrown")
    private val poisonCardIdKey = NamespacedKey(plugin, "bingo6_poison_card")
    private val markerPlayerRunKey = NamespacedKey(plugin, "bingo6_marker_run")
    private val playerRunKey = NamespacedKey(plugin, "bingo6_mob_run")
    private val pendingLavaDestructions = HashMap<UUID, PendingLavaDestruction>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLlamaInteracted(event: PlayerInteractEntityEvent) {
        val llama = event.rightClicked as? Llama ?: return
        val player = event.player
        if (
            !isCarpet(player.inventory.getItem(event.hand)) ||
                isCarpet(llama.inventory.decor) ||
                !tracking.test(player, BingoTask.CARPET_LLAMA)
        )
            return
        scheduleLlamaCarpetCheck(player, llama)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLlamaInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val inventory = event.view.topInventory as? LlamaInventory ?: return
        val llama = inventory.holder as? Llama ?: return
        if (
            isCarpet(inventory.decor) ||
                !clickCanDecorateLlama(event, player, inventory.size) ||
                !tracking.test(player, BingoTask.CARPET_LLAMA)
        )
            return
        scheduleLlamaCarpetCheck(player, llama)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLlamaInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val inventory = event.view.topInventory as? LlamaInventory ?: return
        val llama = inventory.holder as? Llama ?: return
        if (
            isCarpet(inventory.decor) ||
                !isCarpet(event.oldCursor) ||
                1 !in event.rawSlots ||
                !tracking.test(player, BingoTask.CARPET_LLAMA)
        )
            return
        scheduleLlamaCarpetCheck(player, llama)
    }

    private fun scheduleLlamaCarpetCheck(player: Player, llama: Llama) {
        val playerId = player.uniqueId
        val llamaId = llama.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler()
            .runTask(
                plugin,
                Runnable {
                    val currentPlayer = Bukkit.getPlayer(playerId)
                    val currentLlama = Bukkit.getEntity(llamaId) as? Llama
                    if (
                        currentPlayer != null &&
                            currentLlama != null &&
                            currentLlama.isValid &&
                            !currentLlama.isDead &&
                            isCurrent(playerId, token) &&
                            tracking.test(currentPlayer, BingoTask.CARPET_LLAMA) &&
                            isCarpet(currentLlama.inventory.decor)
                    )
                        completion.accept(currentPlayer, BingoTask.CARPET_LLAMA)
                },
            )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMendingBookDropped(event: PlayerDropItemEvent) {
        val player = event.player
        val item = event.itemDrop
        val cardId = activeCardId.asInt
        clearMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey)
        if (
            cardId == Int.MIN_VALUE ||
                !isMendingBook(item.itemStack) ||
                !tracking.test(player, BingoTask.THROW_MENDING_BOOK_IN_LAVA)
        )
            return
        setMarker(
            item,
            mendingOwnerKey,
            mendingDroppedAtKey,
            mendingCardIdKey,
            PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId, playerRun(player)),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDroppedItemDamaged(event: EntityDamageEvent) {
        val item = event.entity as? Item ?: return
        if (
            event.cause != EntityDamageEvent.DamageCause.LAVA ||
                !isMendingBook(item.itemStack) ||
                !itemDamageIsLethal(
                    item.health,
                    event.getOriginalDamage(EntityDamageEvent.DamageModifier.BASE),
                    event.damage,
                )
        )
            return
        val marker = currentMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey) ?: return
        val itemId = item.uniqueId
        val pending = PendingLavaDestruction(marker, attemptToken(marker.playerId))
        pendingLavaDestructions[itemId] = pending
        Bukkit.getScheduler().runTask(plugin, Runnable { pendingLavaDestructions.remove(itemId, pending) })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMarkedItemMergeConflict(event: ItemMergeEvent) {
        val sourceMarker = currentMarker(event.entity, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey) ?: return
        val targetMarker = currentMarker(event.target, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey) ?: return
        if (
            sourceMarker.playerId != targetMarker.playerId ||
                sourceMarker.cardId != targetMarker.cardId ||
                sourceMarker.playerRun != targetMarker.playerRun
        ) {
            // One merged entity cannot preserve exact ownership for both books.
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMarkedItemMerged(event: ItemMergeEvent) {
        val sourceMarker = currentMarker(event.entity, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey) ?: return
        val targetMarker = currentMarker(event.target, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey)
        if (targetMarker == null || sourceMarker.timestamp > targetMarker.timestamp) {
            setMarker(event.target, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey, sourceMarker)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityPickedUpItem(event: EntityPickupItemEvent) = forgetMendingItem(event.item)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryPickedUpItem(event: InventoryPickupItemEvent) = forgetMendingItem(event.item)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val pending = pendingLavaDestructions.remove(event.entity.uniqueId) ?: return
        val item = event.entity as? Item ?: return
        if (
            event.cause != EntityRemoveEvent.Cause.DEATH ||
                pending.marker != markerFrom(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey) ||
                !isCurrent(pending.marker.playerId, pending.token)
        )
            return
        clearMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey)
        completePersistentOrDefer(pending.marker, BingoTask.THROW_MENDING_BOOK_IN_LAVA, 0)
    }

    private fun forgetMendingItem(item: Item) {
        pendingLavaDestructions.remove(item.uniqueId)
        clearMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRavagerAttack(event: EntityDamageByEntityEvent) {
        val ravager = event.damager as? Ravager ?: return
        val player = event.entity as? Player ?: return
        if (
            !canStartRavagerStun(player.isBlocking, activelyBlocksWithShield(player), ravager.stunnedTicks) ||
                !tracking.test(player, BingoTask.STUN_RAVAGER)
        )
            return
        val playerId = player.uniqueId
        val ravagerId = ravager.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler()
            .runTask(
                plugin,
                Runnable {
                    val currentPlayer = Bukkit.getPlayer(playerId)
                    val currentRavager = Bukkit.getEntity(ravagerId) as? Ravager
                    if (
                        currentPlayer != null &&
                            currentRavager != null &&
                            currentRavager.isValid &&
                            !currentRavager.isDead &&
                            isCurrent(playerId, token) &&
                            tracking.test(currentPlayer, BingoTask.STUN_RAVAGER) &&
                            becameStunned(0, currentRavager.stunnedTicks)
                    )
                        completion.accept(currentPlayer, BingoTask.STUN_RAVAGER)
                },
            )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLingeringPotionSplash(event: LingeringPotionSplashEvent) {
        val potion = event.entity
        val player = potion.shooter as? Player ?: return
        if (!hasPoison(potion.effects) || !tracking.test(player, BingoTask.POISON_BEE)) return
        val cardId = activeCardId.asInt
        if (cardId == Int.MIN_VALUE) return
        setMarker(
            event.areaEffectCloud,
            poisonOwnerKey,
            poisonThrownAtKey,
            poisonCardIdKey,
            PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId, playerRun(player)),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBeePotionEffect(event: EntityPotionEffectEvent) {
        if (
            event.entity !is Bee ||
                event.modifiedType != PotionEffectType.POISON ||
                event.newEffect == null ||
                !poisonApplicationAccepted(event.action, event.isOverride)
        )
            return
        if (event.cause == EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD) {
            lingeringPotionMarker(event.source)?.let { completePersistentOrDefer(it, BingoTask.POISON_BEE, 0) }
            return
        }
        val player =
            if (event.cause == EntityPotionEffectEvent.Cause.POTION_SPLASH) splashPotionOwner(event.source) else null
        if (player != null && tracking.test(player, BingoTask.POISON_BEE))
            completion.accept(player, BingoTask.POISON_BEE)
    }

    private fun splashPotionOwner(source: Entity?): Player? {
        val directPlayer = source as? Player
        val projectilePlayer = (source as? ThrownPotion)?.shooter as? Player
        if (!splashSourceCanResolvePlayer(directPlayer != null, projectilePlayer != null)) return null
        return directPlayer ?: projectilePlayer
    }

    private fun lingeringPotionMarker(source: Entity?): PersistentMarker? {
        val cloud = source as? AreaEffectCloud ?: return null
        return currentMarker(cloud, poisonOwnerKey, poisonThrownAtKey, poisonCardIdKey)
    }

    override fun resetPlayer(playerId: UUID) {
        super.resetPlayer(playerId)
        Bukkit.getPlayer(playerId)?.let { player ->
            val data = player.persistentDataContainer
            data.set(
                playerRunKey,
                PersistentDataType.LONG,
                data.getOrDefault(playerRunKey, PersistentDataType.LONG, 0L) + 1L,
            )
        }
        pendingLavaDestructions.entries.removeIf { it.value.marker.playerId == playerId }
    }

    override fun clear() {
        super.clear()
        pendingLavaDestructions.clear()
    }

    private fun currentMarker(
        entity: Entity,
        ownerKey: NamespacedKey,
        timeKey: NamespacedKey,
        cardKey: NamespacedKey,
    ): PersistentMarker? {
        val marker = markerFrom(entity, ownerKey, timeKey, cardKey)
        if (
            marker == null ||
                !timestampIsCurrent(marker.timestamp, System.currentTimeMillis(), MAX_PERSISTENT_ATTRIBUTION_MILLIS)
        ) {
            clearMarker(entity, ownerKey, timeKey, cardKey)
            return null
        }
        val player = Bukkit.getPlayer(marker.playerId)
        if (player != null && marker.playerRun != playerRun(player)) {
            clearMarker(entity, ownerKey, timeKey, cardKey)
            return null
        }
        val cardId = activeCardId.asInt
        if (cardId != Int.MIN_VALUE && marker.cardId != cardId) {
            clearMarker(entity, ownerKey, timeKey, cardKey)
            return null
        }
        return marker
    }

    private fun markerIsCurrent(marker: PersistentMarker): Boolean {
        val player = Bukkit.getPlayer(marker.playerId) ?: return false
        return markerIsCurrent(
            marker.cardId,
            activeCardId.asInt,
            marker.timestamp,
            System.currentTimeMillis(),
            marker.playerRun,
            playerRun(player),
            MAX_PERSISTENT_ATTRIBUTION_MILLIS,
        )
    }

    private fun markerOwnerStateIsCurrent(marker: PersistentMarker): Boolean {
        val player = Bukkit.getPlayer(marker.playerId) ?: return false
        return persistentOwnerStateIsCurrent(
            marker.timestamp,
            System.currentTimeMillis(),
            marker.playerRun,
            playerRun(player),
            MAX_PERSISTENT_ATTRIBUTION_MILLIS,
        )
    }

    private fun playerRun(player: Player): Long =
        player.persistentDataContainer.getOrDefault(playerRunKey, PersistentDataType.LONG, 0L)

    private fun completePersistentOrDefer(marker: PersistentMarker, task: BingoTask, attempts: Int) {
        val cardId = activeCardId.asInt
        val player = Bukkit.getPlayer(marker.playerId)
        val timestampCurrent =
            timestampIsCurrent(marker.timestamp, System.currentTimeMillis(), MAX_PERSISTENT_ATTRIBUTION_MILLIS)
        if (
            shouldDeferPersistentCompletion(
                marker.cardId,
                cardId,
                player != null,
                timestampCurrent,
                attempts,
                MAX_STARTUP_DEFERRAL_ATTEMPTS,
            )
        ) {
            Bukkit.getScheduler()
                .runTaskLater(plugin, Runnable { completePersistentOrDefer(marker, task, attempts + 1) }, 20L)
            return
        }
        if (cardId == Int.MIN_VALUE || player == null) return
        if (markerIsCurrent(marker) && tracking.test(player, task)) completion.accept(player, task)
    }

    private fun markerFrom(
        entity: Entity,
        ownerKey: NamespacedKey,
        timeKey: NamespacedKey,
        cardKey: NamespacedKey,
    ): PersistentMarker? {
        val data = entity.persistentDataContainer
        val owner = data.get(ownerKey, PersistentDataType.STRING) ?: return null
        val timestamp = data.get(timeKey, PersistentDataType.LONG) ?: return null
        val cardId = data.get(cardKey, PersistentDataType.INTEGER) ?: return null
        val run = data.get(markerPlayerRunKey, PersistentDataType.LONG) ?: return null
        return try {
            PersistentMarker(UUID.fromString(owner), timestamp, cardId, run)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun setMarker(
        entity: Entity,
        ownerKey: NamespacedKey,
        timeKey: NamespacedKey,
        cardKey: NamespacedKey,
        marker: PersistentMarker,
    ) {
        val data = entity.persistentDataContainer
        data.set(ownerKey, PersistentDataType.STRING, marker.playerId.toString())
        data.set(timeKey, PersistentDataType.LONG, marker.timestamp)
        data.set(cardKey, PersistentDataType.INTEGER, marker.cardId)
        data.set(markerPlayerRunKey, PersistentDataType.LONG, marker.playerRun)
    }

    private fun clearMarker(entity: Entity, ownerKey: NamespacedKey, timeKey: NamespacedKey, cardKey: NamespacedKey) {
        val data = entity.persistentDataContainer
        data.remove(ownerKey)
        data.remove(timeKey)
        data.remove(cardKey)
        data.remove(markerPlayerRunKey)
    }

    private data class PersistentMarker(val playerId: UUID, val timestamp: Long, val cardId: Int, val playerRun: Long)

    private data class PendingLavaDestruction(val marker: PersistentMarker, val token: AttemptToken)

    companion object {
        private const val MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_STARTUP_DEFERRAL_ATTEMPTS = 300

        @JvmStatic
        fun isCarpet(item: ItemStack?): Boolean =
            item != null && !item.isEmpty && Tag.ITEMS_WOOL_CARPETS.isTagged(item.type)

        @JvmStatic
        fun clickCanDecorateLlama(event: InventoryClickEvent, player: Player, topInventorySize: Int): Boolean {
            val hotbarButton = event.hotbarButton
            return clickCanDecorateLlama(
                event.rawSlot,
                topInventorySize,
                event.action,
                isCarpet(event.cursor),
                isCarpet(event.currentItem),
                event.click == ClickType.SWAP_OFFHAND && isCarpet(player.inventory.itemInOffHand),
                hotbarButton >= 0 && isCarpet(player.inventory.getItem(hotbarButton)),
            )
        }

        @JvmStatic
        fun clickCanDecorateLlama(
            rawSlot: Int,
            topInventorySize: Int,
            action: InventoryAction,
            cursorCarpet: Boolean,
            currentCarpet: Boolean,
            offhandSwapWithCarpet: Boolean,
            hotbarSwapWithCarpet: Boolean,
        ): Boolean =
            if (rawSlot == 1) cursorCarpet || offhandSwapWithCarpet || hotbarSwapWithCarpet
            else rawSlot >= topInventorySize && action == InventoryAction.MOVE_TO_OTHER_INVENTORY && currentCarpet

        @JvmStatic
        fun isMendingBook(item: ItemStack?): Boolean {
            val meta = item?.itemMeta as? EnchantmentStorageMeta ?: return false
            return isMendingBook(item.type, meta.hasStoredEnchant(Enchantment.MENDING))
        }

        @JvmStatic
        fun isMendingBook(material: Material, hasStoredMending: Boolean): Boolean =
            material == Material.ENCHANTED_BOOK && hasStoredMending

        @JvmStatic
        fun itemDamageIsLethal(health: Int, originalDamage: Double, eventDamage: Double): Boolean =
            health > 0 &&
                originalDamage.isFinite() &&
                originalDamage > 0.0 &&
                eventDamage.isFinite() &&
                eventDamage != 0.0 &&
                (health.toFloat() - originalDamage.toFloat()).toInt() <= 0

        @JvmStatic
        fun canStartRavagerStun(playerBlocking: Boolean, playerHoldsShield: Boolean, stunnedTicks: Int): Boolean =
            playerBlocking && playerHoldsShield && stunnedTicks <= 0

        @JvmStatic
        fun becameStunned(previousTicks: Int, currentTicks: Int): Boolean = previousTicks <= 0 && currentTicks > 0

        @JvmStatic
        fun hasPoison(effects: Collection<PotionEffect>): Boolean = effects.any { it.type == PotionEffectType.POISON }

        @JvmStatic
        fun splashSourceCanResolvePlayer(sourceIsPlayer: Boolean, thrownPotionHasPlayerShooter: Boolean): Boolean =
            sourceIsPlayer || thrownPotionHasPlayerShooter

        @JvmStatic
        fun poisonApplicationAccepted(
            action: EntityPotionEffectEvent.Action,
            overridesExistingEffect: Boolean,
        ): Boolean =
            action == EntityPotionEffectEvent.Action.ADDED ||
                action == EntityPotionEffectEvent.Action.CHANGED && overridesExistingEffect

        @JvmStatic
        fun markerIsCurrent(
            markerCardId: Int,
            activeCardId: Int,
            timestamp: Long,
            now: Long,
            markerPlayerRun: Long,
            currentPlayerRun: Long,
            maximumAge: Long,
        ): Boolean =
            activeCardId != Int.MIN_VALUE &&
                markerCardId == activeCardId &&
                persistentOwnerStateIsCurrent(timestamp, now, markerPlayerRun, currentPlayerRun, maximumAge)

        @JvmStatic
        fun persistentOwnerStateIsCurrent(
            timestamp: Long,
            now: Long,
            markerPlayerRun: Long,
            currentPlayerRun: Long,
            maximumAge: Long,
        ): Boolean = markerPlayerRun == currentPlayerRun && timestampIsCurrent(timestamp, now, maximumAge)

        @JvmStatic
        fun timestampIsCurrent(timestamp: Long, now: Long, maximumAge: Long): Boolean =
            timestamp <= now && now - timestamp <= maximumAge

        @JvmStatic
        fun shouldDeferPersistentCompletion(
            markerCardId: Int,
            activeCardId: Int,
            ownerOnline: Boolean,
            timestampCurrent: Boolean,
            attempts: Int,
            maximumAttempts: Int,
        ): Boolean =
            (activeCardId == Int.MIN_VALUE || !ownerOnline && markerCardId == activeCardId) &&
                timestampCurrent &&
                attempts < maximumAttempts

        private fun activelyBlocksWithShield(player: Player): Boolean = player.activeItem.type == Material.SHIELD
    }
}
