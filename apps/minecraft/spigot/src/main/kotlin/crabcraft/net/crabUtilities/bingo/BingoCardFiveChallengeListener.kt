package crabcraft.net.crabUtilities.bingo
import com.destroystokyo.paper.event.entity.ThrownEggHatchEvent
import io.papermc.paper.event.entity.EntityDamageItemEvent
import io.papermc.paper.event.player.PlayerNameEntityEvent
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Egg
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.Enderman
import org.bukkit.entity.Endermite
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Pillager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the attribution-heavy challenge tasks in Bingo #5. */
class BingoCardFiveChallengeListener @JvmOverloads constructor(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier,
    // Exclusively for the loopback test harness; production attributes shots to actual targets.
    private val allowCreativeObserverForTesting: Boolean = false
) : BingoDetector {
    private val endermanOwnerKey = NamespacedKey(plugin, "bingo_card5_enderman_owner")
    private val endermanNamedAtKey = NamespacedKey(plugin, "bingo_card5_enderman_named_at")
    private val endermanCardIdKey = NamespacedKey(plugin, "bingo_card5_enderman_card")
    private val endCrystalOwnerKey = NamespacedKey(plugin, "bingo_card5_crystal_owner")
    private val endCrystalPlacedAtKey = NamespacedKey(plugin, "bingo_card5_crystal_placed_at")
    private val endCrystalCardIdKey = NamespacedKey(plugin, "bingo_card5_crystal_card")
    private val eggOwnerKey = NamespacedKey(plugin, "bingo_card5_egg_owner")
    private val eggThrownAtKey = NamespacedKey(plugin, "bingo_card5_egg_thrown_at")
    private val eggCardIdKey = NamespacedKey(plugin, "bingo_card5_egg_card")
    private val pillagerShots = HashMap<UUID, PillagerShotAttempt>()
    private val thrownEggs = HashMap<UUID, PersistentMarker>()
    private val playerGenerations = HashMap<UUID, Long>()
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var detectorGeneration = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEndermanNamed(event: PlayerNameEntityEvent) {
        val enderman = event.entity as? Enderman ?: return
        clearMarker(enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey)
        val player = event.player
        if (event.name != null
                && tracking.test(player, BingoTask.ENDERMAN_KILLED_BY_ENDERMITES_ONLY)) {
            val cardId = activeCardId.asInt
            if (!isUsableCardId(cardId)) return
            setMarker(enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey,
                    PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEndermanDamaged(event: EntityDamageEvent) {
        val enderman = event.entity as? Enderman ?: return
        val marker = markerFrom(
                enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey) ?: return
        if (!markerIsCurrent(marker)
                || !ChallengePolicy.retainsEndermanMarker(event.finalDamage,
                        ChallengePolicy.isEndermiteMeleeDamage(event.damageSource))) {
            clearMarker(enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        detectEndermanKilledByEndermites(event)
        detectEndCrystalHostileKill(event)
    }

    private fun detectEndermanKilledByEndermites(event: EntityDeathEvent) {
        val enderman = event.entity as? Enderman ?: return
        if (!ChallengePolicy.isEndermiteMeleeDamage(event.damageSource)) return
        val marker = markerFrom(
                enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey) ?: return
        if (!markerIsCurrent(marker)) return
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && tracking.test(player, BingoTask.ENDERMAN_KILLED_BY_ENDERMITES_ONLY)) {
            completion.accept(player, BingoTask.ENDERMAN_KILLED_BY_ENDERMITES_ONLY)
        }
    }

    private fun detectEndCrystalHostileKill(event: EntityDeathEvent) {
        if (event.entity !is Enemy || !ChallengePolicy.isExplosionDamage(event.damageSource)) return
        val crystal = exactEndCrystalSource(event.damageSource) ?: return
        val marker = markerFrom(
                crystal, endCrystalOwnerKey, endCrystalPlacedAtKey, endCrystalCardIdKey) ?: return
        if (!markerIsCurrent(marker)) return
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && tracking.test(player, BingoTask.PLAYER_END_CRYSTAL_HOSTILE_KILL)) {
            completion.accept(player, BingoTask.PLAYER_END_CRYSTAL_HOSTILE_KILL)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPillagerShot(event: EntityShootBowEvent) {
        val bow = event.bow
        val pillager = event.entity as? Pillager ?: return
        if (bow == null || bow.type != Material.CROSSBOW) return
        val player = pillager.target as? Player ?: creativeObserverForTesting(pillager)
        if (player == null || !tracking.test(player, BingoTask.DISARM_PILLAGER)) return
        val pillagerId = pillager.uniqueId
        val attempt = PillagerShotAttempt(
                player.uniqueId, bow.clone(), Bukkit.getCurrentTick(), attemptToken(player.uniqueId))
        putBounded(pillagerShots, pillagerId, attempt)
        // A successful vanilla shot damages the Crossbow synchronously after this event.
        // Discard the correlation on the following tick if no matching damage arrived.
        Bukkit.getScheduler().runTask(plugin, Runnable { pillagerShots.remove(pillagerId, attempt) })
    }

    private fun creativeObserverForTesting(pillager: Pillager): Player? {
        if (!allowCreativeObserverForTesting) return null
        var closest: Player? = null
        var closestDistanceSquared = 32.0 * 32.0
        for (candidate in Bukkit.getOnlinePlayers()) {
            if (candidate.gameMode != GameMode.CREATIVE
                    || candidate.world != pillager.world
                    || !tracking.test(candidate, BingoTask.DISARM_PILLAGER)) continue
            val distanceSquared = candidate.location.distanceSquared(pillager.location)
            if (distanceSquared <= closestDistanceSquared) {
                closest = candidate
                closestDistanceSquared = distanceSquared
            }
        }
        return closest
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPillagerCrossbowDamaged(event: EntityDamageItemEvent) {
        val damagedItem = event.item
        val pillager = event.entity as? Pillager ?: return
        if (damagedItem.type != Material.CROSSBOW) return
        val attempt = pillagerShots.remove(pillager.uniqueId) ?: return
        if (attempt.shotTick() != Bukkit.getCurrentTick()
                || !attempt.crossbow().isSimilar(damagedItem)
                || !isCurrent(attempt.playerId(), attempt.token())
                || !ChallengePolicy.damageBreaksItem(damagedItem, event.damage)) return
        val pillagerId = pillager.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            confirmPillagerDisarmed(pillagerId, attempt.playerId(), attempt.token())
        })
    }

    private fun confirmPillagerDisarmed(pillagerId: UUID, playerId: UUID, token: AttemptToken) {
        val entity = Bukkit.getEntity(pillagerId)
        val player = Bukkit.getPlayer(playerId)
        if (entity is Pillager && entity.isValid && !entity.isDead && player != null
                && isCurrent(playerId, token)
                && ChallengePolicy.isUnarmed(
                        entity.equipment.itemInMainHand, entity.equipment.itemInOffHand)
                && tracking.test(player, BingoTask.DISARM_PILLAGER)) {
            completion.accept(player, BingoTask.DISARM_PILLAGER)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEggLaunched(event: ProjectileLaunchEvent) {
        val egg = event.entity as? Egg ?: return
        val player = egg.shooter as? Player ?: return
        if (!tracking.test(player, BingoTask.HATCH_THROWN_CHICKEN)) return
        val playerId = player.uniqueId
        val cardId = activeCardId.asInt
        if (!isUsableCardId(cardId)) return
        val marker = PersistentMarker(playerId, System.currentTimeMillis(), cardId)
        setMarker(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey, marker)
        putBounded(thrownEggs, egg.uniqueId, marker)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEggHatched(event: ThrownEggHatchEvent) {
        val egg = event.egg
        val marker = thrownEggs.remove(egg.uniqueId)
                ?: markerFrom(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey)
        clearMarker(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey)
        if (marker == null || !markerIsCurrent(marker)
                || !ChallengePolicy.isChickenHatch(
                        event.isHatching, event.numHatches.toInt(), event.hatchingType)) return
        val player = Bukkit.getPlayer(marker.playerId())
        if (player == null || !tracking.test(player, BingoTask.HATCH_THROWN_CHICKEN)) return
        completion.accept(player, BingoTask.HATCH_THROWN_CHICKEN)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEndCrystalPlaced(event: EntityPlaceEvent) {
        val crystal = event.entity as? EnderCrystal ?: return
        clearMarker(crystal, endCrystalOwnerKey, endCrystalPlacedAtKey, endCrystalCardIdKey)
        val player = event.player
        if (player != null && tracking.test(player, BingoTask.PLAYER_END_CRYSTAL_HOSTILE_KILL)) {
            val cardId = activeCardId.asInt
            if (!isUsableCardId(cardId)) return
            setMarker(crystal, endCrystalOwnerKey, endCrystalPlacedAtKey, endCrystalCardIdKey,
                    PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val entityId = event.entity.uniqueId
        pillagerShots.remove(entityId)
        thrownEggs.remove(entityId)
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        playerResetAtMillis[playerId] = System.currentTimeMillis()
        pillagerShots.values.removeIf { it.playerId() == playerId }
        thrownEggs.entries.removeIf { entry ->
            if (entry.value.playerId() != playerId) false
            else {
                val entity = Bukkit.getEntity(entry.key)
                if (entity is Egg) clearMarker(entity, eggOwnerKey, eggThrownAtKey, eggCardIdKey)
                true
            }
        }
    }

    override fun clear() {
        detectorGeneration++
        pillagerShots.clear()
        thrownEggs.clear()
        playerGenerations.clear()
        playerResetAtMillis.clear()
    }

    private fun attemptToken(playerId: UUID) = AttemptToken(
            detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
            token.detectorGeneration() == detectorGeneration
                    && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

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

    private fun markerIsCurrent(marker: PersistentMarker): Boolean {
        val now = System.currentTimeMillis()
        val resetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L)
        return markerMatchesCard(marker.cardId(), activeCardId.asInt)
                && isFreshAttribution(marker.timestamp(), now, resetAt,
                        MAX_PERSISTENT_ATTRIBUTION_MILLIS)
    }

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000

        @JvmStatic
        fun isFreshAttribution(timestamp: Long, now: Long, playerResetAt: Long,
                maximumAge: Long): Boolean = timestamp > playerResetAt
                && timestamp <= now && now - timestamp <= maximumAge

        @JvmStatic
        fun markerMatchesCard(markerCardId: Int, currentCardId: Int): Boolean =
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
        private fun exactEndCrystalSource(source: DamageSource): EnderCrystal? =
                source.directEntity as? EnderCrystal ?: source.causingEntity as? EnderCrystal

        @JvmStatic
        private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) {
                map.remove(map.keys.iterator().next())
            }
            map[key] = value
        }
    }

    private data class AttemptToken(val detectorGeneration: Long, val playerGeneration: Long) {
        fun detectorGeneration() = detectorGeneration
        fun playerGeneration() = playerGeneration
    }

    private data class PersistentMarker(val playerId: UUID, val timestamp: Long, val cardId: Int) {
        fun playerId() = playerId
        fun timestamp() = timestamp
        fun cardId() = cardId
    }

    private data class PillagerShotAttempt(val playerId: UUID, val crossbow: ItemStack,
            val shotTick: Int, val token: AttemptToken) {
        fun playerId() = playerId
        fun crossbow() = crossbow
        fun shotTick() = shotTick
        fun token() = token
    }

    class ChallengePolicy private constructor() {
        companion object {
            @JvmStatic
            fun isEndermiteMeleeDamage(source: DamageSource): Boolean {
                val type = source.damageType
                if (DamageType.MOB_ATTACK != type && DamageType.MOB_ATTACK_NO_AGGRO != type) {
                    return false
                }
                return source.causingEntity is Endermite || source.directEntity is Endermite
            }

            @JvmStatic
            fun isExplosionDamage(source: DamageSource): Boolean {
                val type = source.damageType
                return DamageType.EXPLOSION == type || DamageType.PLAYER_EXPLOSION == type
            }

            @JvmStatic
            fun retainsEndermanMarker(finalDamage: Double, endermiteMeleeDamage: Boolean) =
                    finalDamage <= 0.0 || endermiteMeleeDamage

            @JvmStatic
            fun damageBreaksItem(item: ItemStack, appliedDamage: Int): Boolean {
                val damageable = item.itemMeta as? Damageable ?: return false
                if (damageable.isUnbreakable) return false
                val maximumDamage = if (damageable.hasMaxDamage()) damageable.maxDamage
                        else item.type.maxDurability.toInt()
                return damageBreaksItem(damageable.damage, maximumDamage, appliedDamage)
            }

            @JvmStatic
            fun damageBreaksItem(currentDamage: Int, maximumDamage: Int, appliedDamage: Int) =
                    currentDamage >= 0 && maximumDamage > 0 && appliedDamage > 0
                            && currentDamage.toLong() + appliedDamage >= maximumDamage

            @JvmStatic
            fun isChickenHatch(hatching: Boolean, numberOfHatches: Int, hatchType: EntityType?) =
                    hatching && numberOfHatches > 0 && hatchType == EntityType.CHICKEN

            @JvmStatic
            fun isUnarmed(mainHand: ItemStack?, offHand: ItemStack?) =
                    isEmpty(mainHand) && isEmpty(offHand)

            @JvmStatic
            private fun isEmpty(item: ItemStack?) = item == null || item.isEmpty || item.type.isAir
        }
    }
}
