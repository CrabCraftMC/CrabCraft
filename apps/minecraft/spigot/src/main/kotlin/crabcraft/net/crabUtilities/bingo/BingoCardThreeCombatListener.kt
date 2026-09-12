package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.entity.ShulkerDuplicateEvent
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Raid
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Raider
import org.bukkit.entity.Shulker
import org.bukkit.entity.ShulkerBullet
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BellResonateEvent
import org.bukkit.event.block.BellRingEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.StriderTemperatureChangeEvent
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the projectile, raid and ridden-mob tasks in Bingo #3. */
class BingoCardThreeCombatListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
) : BingoDetector {
    private val shulkerHits = HashMap<UUID, ShulkerHit>()
    private val bellRings = HashMap<BlockKey, TimedAttempt>()
    private val piercingShots = HashMap<UUID, PiercingShot>()
    private val playerGenerations = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var lastPruneTick = Int.MIN_VALUE

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        val bullet = event.entity
        val hitShulker = event.hitEntity
        if (bullet is ShulkerBullet && hitShulker is Shulker) {
            val firingShulker = bullet.shooter
            val target = bullet.target
            if (firingShulker is Shulker && firingShulker.uniqueId != hitShulker.uniqueId
                && target is Player && tracking.test(target, BingoTask.SHULKER_BULLET_DUPLICATE)) {
                val token = attemptToken(target.uniqueId)
                val hit = ShulkerHit(target.uniqueId, tick, token)
                val shulkerId = hitShulker.uniqueId
                putBounded(shulkerHits, shulkerId, hit)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { shulkerHits.remove(shulkerId, hit) }, 2L)
            }
        }
        if (event.entity is AbstractArrow && event.hitBlock != null) {
            piercingShots.remove(event.entity.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShulkerDuplicated(event: ShulkerDuplicateEvent) {
        val tick = Bukkit.getCurrentTick()
        val hit = shulkerHits.remove(event.parent.uniqueId)
        if (hit == null || !isFresh(hit.tick, tick, SHULKER_DUPLICATION_CORRELATION_TICKS)
            || !isCurrent(hit.playerId, hit.token)) return
        val childId = event.entity.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmShulkerDuplicate(hit, childId) })
    }

    private fun confirmShulkerDuplicate(hit: ShulkerHit, childId: UUID) {
        val player = Bukkit.getPlayer(hit.playerId)
        val child = Bukkit.getEntity(childId)
        if (player != null && child is Shulker && child.isValid && !child.isDead
            && isCurrent(hit.playerId, hit.token) && tracking.test(player, BingoTask.SHULKER_BULLET_DUPLICATE)) {
            completion.accept(player, BingoTask.SHULKER_BULLET_DUPLICATE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStriderTemperatureChanged(event: StriderTemperatureChangeEvent) {
        val strider = event.entity
        if (event.isShivering || !strider.isInLava || strider.passengers.isEmpty()) return
        val player = strider.passengers.first()
        if (player is Player && tracking.test(player, BingoTask.WARM_RIDDEN_STRIDER)) {
            completion.accept(player, BingoTask.WARM_RIDDEN_STRIDER)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBellRung(event: BellRingEvent) {
        val bell = BlockKey.from(event.block)
        bellRings.remove(bell)
        val player = event.entity as? Player ?: return
        if (!tracking.test(player, BingoTask.RAID_BELL_REVEAL_THREE)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        val attempt = TimedAttempt(player.uniqueId, tick, attemptToken(player.uniqueId))
        putBounded(bellRings, bell, attempt)
        Bukkit.getScheduler().runTaskLater(
            plugin, Runnable { bellRings.remove(bell, attempt) }, BELL_RESONANCE_CORRELATION_TICKS + 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBellResonated(event: BellResonateEvent) {
        val bell = BlockKey.from(event.block)
        val attempt = bellRings.remove(bell)
        val tick = Bukkit.getCurrentTick()
        if (attempt == null || !isFresh(attempt.tick, tick, BELL_RESONANCE_CORRELATION_TICKS)
            || !isCurrent(attempt.playerId, attempt.token)) return
        val raiders = event.resonatedEntities.filterIsInstance<Raider>()
            .filter { it.isValid && !it.isDead }
            .filter { raider ->
                val raid = raider.raid
                raid != null && raid.isStarted && raid.status == Raid.RaidStatus.ONGOING
            }
            .map { it.uniqueId }.distinct().size
        if (raiders < 3) return
        val player = Bukkit.getPlayer(attempt.playerId)
        if (player != null && tracking.test(player, BingoTask.RAID_BELL_REVEAL_THREE)) {
            completion.accept(player, BingoTask.RAID_BELL_REVEAL_THREE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArrowShot(event: EntityShootBowEvent) {
        val player = event.entity as? Player ?: return
        val bow = event.bow ?: return
        val arrow = event.projectile as? AbstractArrow ?: return
        if (bow.type != Material.CROSSBOW || bow.getEnchantmentLevel(Enchantment.PIERCING) <= 0
            || arrow.pierceLevel <= 0 || !tracking.test(player, BingoTask.PIERCING_ARROW_HIT_THREE)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        putBounded(piercingShots, arrow.uniqueId,
            PiercingShot(player.uniqueId, tick, attemptToken(player.uniqueId)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageByEntityEvent) {
        val arrow = event.damager as? AbstractArrow ?: return
        val mob = event.entity as? Mob ?: return
        if (event.finalDamage <= 0.0) return
        val shot = piercingShots[arrow.uniqueId]
        if (shot == null || !isCurrent(shot.playerId, shot.token)) return
        val player = Bukkit.getPlayer(shot.playerId)
        if (player == null || !tracking.test(player, BingoTask.PIERCING_ARROW_HIT_THREE)) {
            piercingShots.remove(arrow.uniqueId, shot)
            return
        }
        shot.hitMobs.add(mob.uniqueId)
        if (shot.hitMobs.size >= 3) {
            piercingShots.remove(arrow.uniqueId, shot)
            completion.accept(player, BingoTask.PIERCING_ARROW_HIT_THREE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val entityId = event.entity.uniqueId
        piercingShots.remove(entityId)
        if (event.entity is Shulker) shulkerHits.remove(entityId)
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        shulkerHits.values.removeIf { it.playerId == playerId }
        bellRings.values.removeIf { it.playerId == playerId }
        piercingShots.values.removeIf { it.playerId == playerId }
    }

    override fun clear() {
        detectorGeneration++
        playerGenerations.clear()
        lastPruneTick = Int.MIN_VALUE
        shulkerHits.clear()
        bellRings.clear()
        piercingShots.clear()
    }

    private fun pruneTransientStateIfDue(tick: Int) {
        if (lastPruneTick != Int.MIN_VALUE) {
            val age = tick - lastPruneTick
            if (age >= 0 && age < 20) return
        }
        lastPruneTick = tick
        shulkerHits.values.removeIf { !isFresh(it.tick, tick, SHULKER_DUPLICATION_CORRELATION_TICKS) }
        bellRings.values.removeIf { !isFresh(it.tick, tick, BELL_RESONANCE_CORRELATION_TICKS) }
        piercingShots.entries.removeIf {
            tick - it.value.tick > PROJECTILE_RETENTION_TICKS || Bukkit.getEntity(it.key) == null
        }
    }

    private fun attemptToken(playerId: UUID): AttemptToken =
        AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration == detectorGeneration
            && token.playerGeneration == playerGenerations.getOrDefault(playerId, 0L)

    private data class BlockKey(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
        }
    }

    private data class AttemptToken(val detectorGeneration: Long, val playerGeneration: Long)
    private data class TimedAttempt(val playerId: UUID, val tick: Int, val token: AttemptToken)
    private data class ShulkerHit(val playerId: UUID, val tick: Int, val token: AttemptToken)
    private data class PiercingShot(
        val playerId: UUID,
        val tick: Int,
        val token: AttemptToken,
        val hitMobs: MutableSet<UUID> = LinkedHashSet(),
    )

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val SHULKER_DUPLICATION_CORRELATION_TICKS = 1
        // A vanilla bell begins resonating shortly after it is rung and applies the
        // reveal roughly 45 ticks later. Leave a little margin for tick timing.
        private const val BELL_RESONANCE_CORRELATION_TICKS = 60
        private const val PROJECTILE_RETENTION_TICKS = 20 * 60 * 5

        private fun isFresh(earlier: Int, current: Int, maximumAge: Int): Boolean {
            val age = current - earlier
            return age >= 0 && age <= maximumAge
        }

        private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) {
                map.remove(map.keys.iterator().next())
            }
            map[key] = value
        }
    }
}
