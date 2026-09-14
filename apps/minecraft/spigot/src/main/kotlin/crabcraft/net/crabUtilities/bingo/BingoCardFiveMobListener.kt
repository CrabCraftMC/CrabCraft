package crabcraft.net.crabUtilities.bingo
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.player.PlayerNameEntityEvent
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.Hoglin
import org.bukkit.entity.IronGolem
import org.bukkit.entity.Panda
import org.bukkit.entity.Player
import org.bukkit.entity.Zoglin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the mob-interaction tasks on Bingo #5. */
class BingoCardFiveMobListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier
) : BingoDetector {
    private val hoglinOwnerKey = NamespacedKey(plugin, "bingo_card5_hoglin_owner")
    private val hoglinNamedAtKey = NamespacedKey(plugin, "bingo_card5_hoglin_named_at")
    private val hoglinCardIdKey = NamespacedKey(plugin, "bingo_card5_hoglin_card")
    private val helmetOwnerKey = NamespacedKey(plugin, "bingo_card5_helmet_owner")
    private val helmetDroppedAtKey = NamespacedKey(plugin, "bingo_card5_helmet_dropped_at")
    private val helmetCardIdKey = NamespacedKey(plugin, "bingo_card5_helmet_card")
    private val cakeOwnerKey = NamespacedKey(plugin, "bingo_card5_cake_owner")
    private val cakeDroppedAtKey = NamespacedKey(plugin, "bingo_card5_cake_dropped_at")
    private val cakeCardIdKey = NamespacedKey(plugin, "bingo_card5_cake_card")
    private val droppedHelmets = LinkedHashMap<UUID, DroppedHelmet>()
    private val droppedCakes = LinkedHashMap<UUID, DroppedCake>()
    private val playerGenerations = HashMap<UUID, Long>()
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var detectorGeneration = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHelmetDropped(event: PlayerDropItemEvent) {
        val item = event.itemDrop.itemStack
        val player = event.player
        if (!isHeadgear(item) || !tracking.test(player, BingoTask.MOB_EQUIPS_DROPPED_HELMET)) return
        val now = System.currentTimeMillis()
        val cardId = activeCardId.asInt
        if (!isUsableCardId(cardId)) return
        val marker = PersistentMarker(player.uniqueId, now, cardId)
        setMarker(event.itemDrop, helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey, marker)
        pruneDroppedHelmets(now)
        putBounded(droppedHelmets, event.itemDrop.uniqueId, DroppedHelmet(marker, item.asOne()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCakeDropped(event: PlayerDropItemEvent) {
        val item = event.itemDrop.itemStack
        val player = event.player
        if (!isEligibleDroppedPandaCake(item.type, Tag.ITEMS_PANDA_EATS_FROM_GROUND.isTagged(item.type))
                || !tracking.test(player, BingoTask.FEED_PANDA_CAKE)) return
        val now = System.currentTimeMillis()
        val cardId = activeCardId.asInt
        if (!isUsableCardId(cardId)) return
        val marker = PersistentMarker(player.uniqueId, now, cardId)
        setMarker(event.itemDrop, cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey, marker)
        pruneDroppedCakes(now)
        putBounded(droppedCakes, event.itemDrop.uniqueId, DroppedCake(marker))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemPickedUp(event: EntityPickupItemEvent) {
        val itemId = event.item.uniqueId
        val cake = droppedCakes.remove(itemId)
                ?: markerFrom(event.item, cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey)?.let(::DroppedCake)
        clearMarker(event.item, cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey)
        val picker = event.entity
        if (cake != null && picker is Panda) confirmPandaAcceptedCake(picker, cake)

        val dropped = droppedHelmets.remove(itemId)
                ?: markerFrom(event.item, helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey)?.let {
                    DroppedHelmet(it, event.item.itemStack.asOne())
                }
        clearMarker(event.item, helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey)
        if (dropped == null || picker !is Enemy) return
        val equipment = picker.equipment!!
        val previousHelmet = equipment.getItem(EquipmentSlot.HEAD).clone()
        val enemyId = picker.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            confirmHelmetEquipped(enemyId, previousHelmet, dropped)
        })
    }

    private fun confirmHelmetEquipped(enemyId: UUID, previousHelmet: ItemStack, dropped: DroppedHelmet) {
        val entity = Bukkit.getEntity(enemyId)
        val player = Bukkit.getPlayer(dropped.marker().playerId())
        if (entity !is Enemy || !entity.isValid || entity.isDead || player == null
                || !markerIsCurrent(dropped.marker(), System.currentTimeMillis(), MAX_DROPPED_ITEM_AGE_MILLIS)
                || !tracking.test(player, BingoTask.MOB_EQUIPS_DROPPED_HELMET)) return
        val equipped = entity.equipment!!.getItem(EquipmentSlot.HEAD)
        if (equipped.isSimilar(dropped.item()) && !previousHelmet.isSimilar(equipped)) {
            completion.accept(player, BingoTask.MOB_EQUIPS_DROPPED_HELMET)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMobInteracted(event: PlayerInteractEntityEvent) {
        val golem = event.rightClicked as? IronGolem ?: return
        beginGolemRepairAttempt(event, golem)
    }

    private fun confirmPandaAcceptedCake(panda: Panda, cake: DroppedCake) {
        val player = Bukkit.getPlayer(cake.marker().playerId())
        if (player != null && panda.isValid && !panda.isDead
                && markerIsCurrent(cake.marker(), System.currentTimeMillis(), MAX_DROPPED_ITEM_AGE_MILLIS)
                && tracking.test(player, BingoTask.FEED_PANDA_CAKE)) {
            completion.accept(player, BingoTask.FEED_PANDA_CAKE)
        }
    }

    private fun beginGolemRepairAttempt(event: PlayerInteractEntityEvent, golem: IronGolem) {
        val player = event.player
        val item = player.inventory.getItem(event.hand)
        val maxHealth = golem.getAttribute(Attribute.MAX_HEALTH)
        if (maxHealth == null || !canStartGolemRepair(item?.type, golem.health, maxHealth.value)
                || !tracking.test(player, BingoTask.REPAIR_IRON_GOLEM)) return
        val playerId = player.uniqueId
        val golemId = golem.uniqueId
        val previousHealth = golem.health
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            confirmGolemRepaired(playerId, golemId, previousHealth, token)
        })
    }

    private fun confirmGolemRepaired(playerId: UUID, golemId: UUID, previousHealth: Double,
            token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val entity = Bukkit.getEntity(golemId)
        if (player != null && entity is IronGolem && entity.isValid && !entity.isDead
                && healthIncreased(previousHealth, entity.health) && isCurrent(playerId, token)
                && tracking.test(player, BingoTask.REPAIR_IRON_GOLEM)) {
            completion.accept(player, BingoTask.REPAIR_IRON_GOLEM)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHoglinNamed(event: PlayerNameEntityEvent) {
        val hoglin = event.entity as? Hoglin ?: return
        if (event.name == null) return
        val player = event.player
        if (!tracking.test(player, BingoTask.NAME_HOGLIN_ZOGLIN)) return
        val cardId = activeCardId.asInt
        if (!isUsableCardId(cardId)) return
        setMarker(hoglin, hoglinOwnerKey, hoglinNamedAtKey, hoglinCardIdKey,
                PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHoglinTransformed(event: EntityTransformEvent) {
        if (event.transformReason != EntityTransformEvent.TransformReason.PIGLIN_ZOMBIFIED
                || event.transformedEntity !is Zoglin) return
        val hoglin = event.entity as? Hoglin ?: return
        val marker = markerFrom(hoglin, hoglinOwnerKey, hoglinNamedAtKey, hoglinCardIdKey) ?: return
        if (!markerIsCurrent(marker, System.currentTimeMillis(), MAX_PERSISTENT_ATTRIBUTION_MILLIS)) return
        clearHoglinMarker(hoglin)
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && tracking.test(player, BingoTask.NAME_HOGLIN_ZOGLIN)) {
            completion.accept(player, BingoTask.NAME_HOGLIN_ZOGLIN)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemMerged(event: ItemMergeEvent) {
        // A merge makes it impossible to prove which physical item was equipped.
        droppedHelmets.remove(event.entity.uniqueId)
        droppedHelmets.remove(event.target.uniqueId)
        droppedCakes.remove(event.entity.uniqueId)
        droppedCakes.remove(event.target.uniqueId)
        clearDroppedItemMarkers(event.entity)
        clearDroppedItemMarkers(event.target)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryPickedUp(event: InventoryPickupItemEvent) {
        droppedHelmets.remove(event.item.uniqueId)
        droppedCakes.remove(event.item.uniqueId)
        clearDroppedItemMarkers(event.item)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        droppedHelmets.remove(event.entity.uniqueId)
        droppedCakes.remove(event.entity.uniqueId)
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        playerResetAtMillis[playerId] = System.currentTimeMillis()
        droppedHelmets.entries.removeIf { entry ->
            if (entry.value.marker().playerId() != playerId) false
            else {
                clearDroppedItemMarkers(Bukkit.getEntity(entry.key))
                true
            }
        }
        droppedCakes.entries.removeIf { entry ->
            if (entry.value.marker().playerId() != playerId) false
            else {
                clearDroppedItemMarkers(Bukkit.getEntity(entry.key))
                true
            }
        }
    }

    override fun clear() {
        detectorGeneration++
        droppedHelmets.clear()
        droppedCakes.clear()
        playerGenerations.clear()
        playerResetAtMillis.clear()
    }

    private fun clearHoglinMarker(hoglin: Hoglin) {
        clearMarker(hoglin, hoglinOwnerKey, hoglinNamedAtKey, hoglinCardIdKey)
    }

    private fun clearDroppedItemMarkers(entity: Entity?) {
        if (entity == null) return
        clearMarker(entity, helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey)
        clearMarker(entity, cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey)
    }

    private fun markerIsCurrent(marker: PersistentMarker, now: Long, maximumAge: Long): Boolean {
        val resetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L)
        return markerMatchesCard(marker.cardId(), activeCardId.asInt)
                && isFreshAttribution(marker.timestamp(), now, resetAt, maximumAge)
    }

    private fun pruneDroppedHelmets(now: Long) {
        droppedHelmets.values.removeIf { !markerIsCurrent(it.marker(), now, MAX_DROPPED_ITEM_AGE_MILLIS) }
    }

    private fun pruneDroppedCakes(now: Long) {
        droppedCakes.values.removeIf { !markerIsCurrent(it.marker(), now, MAX_DROPPED_ITEM_AGE_MILLIS) }
    }

    private fun markerFrom(entity: Entity, ownerKey: NamespacedKey,
            timestampKey: NamespacedKey, cardIdKey: NamespacedKey): PersistentMarker? {
        val data = entity.persistentDataContainer
        val owner = data.get(ownerKey, PersistentDataType.STRING) ?: return null
        val timestamp = data.get(timestampKey, PersistentDataType.LONG) ?: return null
        val cardId = data.get(cardIdKey, PersistentDataType.INTEGER) ?: return null
        return try {
            PersistentMarker(UUID.fromString(owner), timestamp, cardId)
        } catch (ignored: IllegalArgumentException) {
            clearMarker(entity, ownerKey, timestampKey, cardIdKey)
            null
        }
    }

    private fun attemptToken(playerId: UUID) = AttemptToken(
            detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken) =
            token.detectorGeneration() == detectorGeneration
                    && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val MAX_DROPPED_ITEM_AGE_MILLIS = 10L * 60 * 1_000
        private const val MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val HEALTH_EPSILON = 1.0e-7

        @JvmStatic
        fun isHeadgear(item: ItemStack?): Boolean {
            if (item == null || item.isEmpty) return false
            val equippable = item.getData(DataComponentTypes.EQUIPPABLE)
            return equippable != null && equippable.slot() == EquipmentSlot.HEAD
        }

        @JvmStatic
        fun isEligibleDroppedPandaCake(itemType: Material?, pandaGroundFood: Boolean) =
                itemType == Material.CAKE && pandaGroundFood

        @JvmStatic
        fun canStartGolemRepair(itemType: Material?, currentHealth: Double, maximumHealth: Double) =
                itemType == Material.IRON_INGOT && currentHealth + HEALTH_EPSILON < maximumHealth

        @JvmStatic
        fun healthIncreased(previousHealth: Double, currentHealth: Double) =
                currentHealth > previousHealth + HEALTH_EPSILON

        @JvmStatic
        fun isFreshAttribution(timestamp: Long, now: Long, playerResetAt: Long, maximumAge: Long) =
                timestamp > playerResetAt && timestamp <= now && now - timestamp <= maximumAge

        @JvmStatic
        fun markerMatchesCard(markerCardId: Int, currentCardId: Int) =
                isUsableCardId(currentCardId) && markerCardId == currentCardId

        @JvmStatic
        private fun isUsableCardId(cardId: Int) = cardId != Int.MIN_VALUE

        @JvmStatic
        private fun setMarker(entity: Entity, ownerKey: NamespacedKey,
                timestampKey: NamespacedKey, cardIdKey: NamespacedKey, marker: PersistentMarker) {
            val data = entity.persistentDataContainer
            data.set(ownerKey, PersistentDataType.STRING, marker.playerId().toString())
            data.set(timestampKey, PersistentDataType.LONG, marker.timestamp())
            data.set(cardIdKey, PersistentDataType.INTEGER, marker.cardId())
        }

        @JvmStatic
        private fun clearMarker(entity: Entity, ownerKey: NamespacedKey,
                timestampKey: NamespacedKey, cardIdKey: NamespacedKey) {
            val data = entity.persistentDataContainer
            data.remove(ownerKey)
            data.remove(timestampKey)
            data.remove(cardIdKey)
        }

        @JvmStatic
        private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) {
                val oldest = map.keys.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            map[key] = value
        }
    }

    private data class AttemptToken(val detectorGeneration: Long, val playerGeneration: Long) {
        fun detectorGeneration() = detectorGeneration
        fun playerGeneration() = playerGeneration
    }

    private data class DroppedHelmet(val marker: PersistentMarker, val item: ItemStack) {
        fun marker() = marker
        fun item() = item
    }

    private data class DroppedCake(val marker: PersistentMarker) {
        fun marker() = marker
    }

    private data class PersistentMarker(val playerId: UUID, val timestamp: Long, val cardId: Int) {
        fun playerId() = playerId
        fun timestamp() = timestamp
        fun cardId() = cardId
    }
}
