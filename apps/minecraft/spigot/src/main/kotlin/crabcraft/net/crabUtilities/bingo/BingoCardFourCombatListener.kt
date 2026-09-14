package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.DecoratedPot
import org.bukkit.damage.DamageType
import org.bukkit.entity.Breeze
import org.bukkit.entity.BreezeWindCharge
import org.bukkit.entity.Enemy
import org.bukkit.entity.Firework
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the combat and projectile tasks in Bingo #4. */
class BingoCardFourCombatListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>
) : BingoDetector {
    private val spearSessions = HashMap<UUID, SpearSession>()
    private val breezeCharges = HashMap<UUID, BreezeChargeAttempt>()
    private val potProjectiles = HashMap<UUID, ProjectileAttempt>()
    private val fireworkShots = HashMap<UUID, FireworkAttempt>()
    private val playerGenerations = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var lastPruneTick = Int.MIN_VALUE

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpearUseStarted(event: PlayerInteractEvent) {
        val hand = event.hand
        val item = event.item
        if (!event.action.isRightClick || hand == null || event.useItemInHand() == Event.Result.DENY ||
            item == null || !isSpear(item) || !tracking.test(event.player, BingoTask.SPEAR_HIT_THREE)) return
        beginSpearSession(event.player, hand, item.type, Bukkit.getCurrentTick())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onStoppedUsingItem(event: PlayerStopUsingItemEvent) {
        spearSessions.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunched(event: ProjectileLaunchEvent) {
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        val projectile = event.entity
        val shooter = projectile.shooter
        if (projectile is BreezeWindCharge && shooter is Breeze) {
            putBounded(breezeCharges, projectile.uniqueId, BreezeChargeAttempt(shooter.uniqueId, tick, detectorGeneration, null, null))
        }
        val player = projectile.shooter as? Player ?: return
        if (tracking.test(player, BingoTask.PROJECTILE_SMASH_FILLED_POT)) {
            putBounded(potProjectiles, projectile.uniqueId, ProjectileAttempt(
                player.uniqueId, projectile.world.uid, projectile.x, projectile.y, projectile.z, tick, attemptToken(player.uniqueId)))
        }
        if (projectile is Firework && projectile.isShotAtAngle && tracking.test(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)) {
            putBounded(fireworkShots, projectile.uniqueId, FireworkAttempt(projectile.uniqueId, player.uniqueId, tick, attemptToken(player.uniqueId)))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageByEntityEvent) {
        val charge = event.entity
        val damager = event.damager
        if (charge is BreezeWindCharge && damager is Player) rememberBreezeDeflection(charge, damager)
        val mob = event.entity
        val player = event.damageSource.causingEntity
        if (mob !is Mob || event.finalDamage <= 0.0 || DamageType.SPEAR != event.damageSource.damageType ||
            player !is Player || !tracking.test(player, BingoTask.SPEAR_HIT_THREE) || !player.hasActiveItem() ||
            !isSpear(player.activeItem)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        val playerId = player.uniqueId
        val activeHand = player.activeItemHand
        val spearType = player.activeItem.type
        var session = spearSessions[playerId]
        if (!CombatPolicy.sameSpearSession(session != null, session?.hand(), activeHand, session?.spearType(), spearType,
                session?.tick() ?: tick, tick, SPEAR_SESSION_RETENTION_TICKS) || !isCurrent(playerId, session!!.token())) {
            session = beginSpearSession(player, activeHand, spearType, tick)
        }
        val currentSession = session!!
        currentSession.hitMobs().add(mob.uniqueId)
        if (currentSession.hitMobs().size >= 3) {
            spearSessions.remove(playerId, currentSession)
            completion.accept(player, BingoTask.SPEAR_HIT_THREE)
        }
    }

    private fun rememberBreezeDeflection(charge: BreezeWindCharge, player: Player) {
        val chargeId = charge.uniqueId
        val attempt = breezeCharges[chargeId]
        if (attempt == null || attempt.detectorGeneration() != detectorGeneration ||
            !tracking.test(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)) return
        breezeCharges[chargeId] = attempt.withDeflector(player.uniqueId, attemptToken(player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerProjectileHit(event: ProjectileHitEvent) {
        val charge = event.hitEntity
        val player = event.entity.shooter
        if (charge is BreezeWindCharge && player is Player) rememberBreezeDeflection(charge, player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        detectReflectedBreezeKill(event)
        detectFireworkKills(event)
    }

    private fun detectReflectedBreezeKill(event: EntityDeathEvent) {
        val breeze = event.entity as? Breeze ?: return
        val source = event.damageSource
        val charge = source.directEntity
        val player = source.causingEntity
        if (charge !is BreezeWindCharge || player !is Player) return
        val attempt = breezeCharges.remove(charge.uniqueId) ?: return
        val deflectorId = attempt.deflectorId()
        val token = attempt.token()
        if (deflectorId == null || token == null ||
            !CombatPolicy.isExactReflectedBreezeKill(attempt.originalBreezeId(), breeze.uniqueId, deflectorId, player.uniqueId) ||
            attempt.detectorGeneration() != detectorGeneration || !isCurrent(deflectorId, token) ||
            !tracking.test(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)) return
        completion.accept(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)
    }

    private fun detectFireworkKills(event: EntityDeathEvent) {
        if (event.entity !is Enemy) return
        val source = event.damageSource
        val firework = source.directEntity
        val player = source.causingEntity
        if (firework !is Firework || player !is Player) return
        val attempt = fireworkShots[firework.uniqueId]
        if (attempt == null || !CombatPolicy.isExactFireworkKill(attempt.fireworkId(), firework.uniqueId, attempt.playerId(), player.uniqueId) ||
            !isCurrent(attempt.playerId(), attempt.token()) || !tracking.test(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)) return
        attempt.killedMobs().add(event.entity.uniqueId)
        if (attempt.killedMobs().size >= 2) {
            fireworkShots.remove(firework.uniqueId, attempt)
            completion.accept(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileChangedBlock(event: EntityChangeBlockEvent) {
        val projectile = event.entity
        if (projectile !is Projectile || event.block.type != Material.DECORATED_POT || !CombatPolicy.isPotBreakReplacement(event.to)) return
        val attempt = potProjectiles.remove(projectile.uniqueId) ?: return
        val storedItem = (event.block.state as? DecoratedPot)?.inventory?.item
        val block = event.block
        val distanceSquared = distanceSquared(attempt, block)
        if (!CombatPolicy.isQualifyingPotSmash(storedItem != null && !storedItem.isEmpty, attempt.worldId() == block.world.uid,
                distanceSquared, POT_MINIMUM_DISTANCE_SQUARED) || !isCurrent(attempt.playerId(), attempt.token())) return
        val player = Bukkit.getPlayer(attempt.playerId())
        if (player != null && tracking.test(player, BingoTask.PROJECTILE_SMASH_FILLED_POT)) {
            completion.accept(player, BingoTask.PROJECTILE_SMASH_FILLED_POT)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val entityId = event.entity.uniqueId
        breezeCharges.remove(entityId)
        potProjectiles.remove(entityId)
        fireworkShots.remove(entityId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        invalidatePlayerAttempts(event.player.uniqueId)
    }

    override fun resetPlayer(playerId: UUID) { invalidatePlayerAttempts(playerId) }

    override fun clear() {
        detectorGeneration++
        playerGenerations.clear()
        lastPruneTick = Int.MIN_VALUE
        spearSessions.clear()
        breezeCharges.clear()
        potProjectiles.clear()
        fireworkShots.clear()
    }

    private fun beginSpearSession(player: Player, hand: EquipmentSlot, spearType: Material, tick: Int): SpearSession {
        val playerId = player.uniqueId
        val session = SpearSession(playerId, tick, hand, spearType, attemptToken(playerId), LinkedHashSet())
        putBounded(spearSessions, playerId, session)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { spearSessions.remove(playerId, session) }, SPEAR_SESSION_RETENTION_TICKS + 1L)
        return session
    }

    private fun invalidatePlayerAttempts(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        spearSessions.remove(playerId)
        potProjectiles.values.removeIf { it.playerId() == playerId }
        fireworkShots.values.removeIf { it.playerId() == playerId }
        breezeCharges.replaceAll { _, attempt -> if (playerId == attempt.deflectorId()) attempt.withoutDeflector() else attempt }
    }

    private fun pruneTransientStateIfDue(tick: Int) {
        if (lastPruneTick != Int.MIN_VALUE) {
            val age = tick - lastPruneTick
            if (age >= 0 && age < 20) return
        }
        lastPruneTick = tick
        spearSessions.values.removeIf { !isFresh(it.tick(), tick, SPEAR_SESSION_RETENTION_TICKS) }
        breezeCharges.values.removeIf { !isFresh(it.tick(), tick, BREEZE_CHARGE_RETENTION_TICKS) || it.detectorGeneration() != detectorGeneration }
        potProjectiles.values.removeIf { !isFresh(it.tick(), tick, PROJECTILE_RETENTION_TICKS) || !isCurrent(it.playerId(), it.token()) }
        fireworkShots.values.removeIf { !isFresh(it.tick(), tick, PROJECTILE_RETENTION_TICKS) || !isCurrent(it.playerId(), it.token()) }
    }

    private fun attemptToken(playerId: UUID): AttemptToken = AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))
    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration() == detectorGeneration && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val SPEAR_SESSION_RETENTION_TICKS = 20 * 16
        private const val BREEZE_CHARGE_RETENTION_TICKS = 20 * 60
        private const val PROJECTILE_RETENTION_TICKS = 20 * 60 * 5
        private const val POT_MINIMUM_DISTANCE_SQUARED = 10.0 * 10.0

        @JvmStatic private fun isSpear(item: ItemStack?): Boolean = item != null && isSpear(item.type)
        @JvmStatic private fun isSpear(material: Material?): Boolean = material != null && Tag.ITEMS_SPEARS.isTagged(material)

        @JvmStatic private fun distanceSquared(attempt: ProjectileAttempt, block: Block): Double {
            if (attempt.worldId() != block.world.uid) return Double.NaN
            val x = block.x + 0.5 - attempt.x()
            val y = block.y + 0.5 - attempt.y()
            val z = block.z + 0.5 - attempt.z()
            return x * x + y * y + z * z
        }

        @JvmStatic private fun isFresh(earlier: Int, current: Int, maximumAge: Int): Boolean {
            val age = current - earlier
            return age >= 0 && age <= maximumAge
        }

        @JvmStatic private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) map.remove(map.keys.iterator().next())
            map[key] = value
        }
    }

    class CombatPolicy private constructor() {
        companion object {
            @JvmStatic
            fun sameSpearSession(
                hasSession: Boolean, recordedHand: EquipmentSlot?, activeHand: EquipmentSlot?, recordedSpear: Material?, activeSpear: Material?,
                startedTick: Int, currentTick: Int, maximumAge: Int
            ): Boolean = hasSession && recordedHand == activeHand && recordedSpear == activeSpear && isFresh(startedTick, currentTick, maximumAge)

            @JvmStatic
            fun isExactReflectedBreezeKill(originalBreezeId: UUID, victimBreezeId: UUID, deflectorId: UUID, causingPlayerId: UUID): Boolean =
                originalBreezeId == victimBreezeId && deflectorId == causingPlayerId

            @JvmStatic fun isPotBreakReplacement(replacement: Material): Boolean = replacement == Material.AIR || replacement == Material.WATER

            @JvmStatic
            fun isExactFireworkKill(launchedFireworkId: UUID, damagingFireworkId: UUID, shooterId: UUID, causingPlayerId: UUID): Boolean =
                launchedFireworkId == damagingFireworkId && shooterId == causingPlayerId

            @JvmStatic
            fun isQualifyingPotSmash(containsItem: Boolean, sameWorld: Boolean, distanceSquared: Double, minimumDistanceSquared: Double): Boolean =
                containsItem && sameWorld && distanceSquared.isFinite() && distanceSquared >= minimumDistanceSquared
        }
    }
    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }
    private data class SpearSession(private val playerId: UUID, private val tick: Int, private val hand: EquipmentSlot, private val spearType: Material, private val token: AttemptToken, private val hitMobs: MutableSet<UUID>) {
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
        fun hand(): EquipmentSlot = hand
        fun spearType(): Material = spearType
        fun token(): AttemptToken = token
        fun hitMobs(): MutableSet<UUID> = hitMobs
    }
    private data class BreezeChargeAttempt(private val originalBreezeId: UUID, private val tick: Int, private val detectorGeneration: Long, private val deflectorId: UUID?, private val token: AttemptToken?) {
        fun originalBreezeId(): UUID = originalBreezeId
        fun tick(): Int = tick
        fun detectorGeneration(): Long = detectorGeneration
        fun deflectorId(): UUID? = deflectorId
        fun token(): AttemptToken? = token
        fun withDeflector(playerId: UUID, token: AttemptToken): BreezeChargeAttempt =
            BreezeChargeAttempt(originalBreezeId, tick, detectorGeneration, playerId, token)
        fun withoutDeflector(): BreezeChargeAttempt = BreezeChargeAttempt(originalBreezeId, tick, detectorGeneration, null, null)
    }
    private data class ProjectileAttempt(private val playerId: UUID, private val worldId: UUID, private val x: Double, private val y: Double, private val z: Double, private val tick: Int, private val token: AttemptToken) {
        fun playerId(): UUID = playerId
        fun worldId(): UUID = worldId
        fun x(): Double = x
        fun y(): Double = y
        fun z(): Double = z
        fun tick(): Int = tick
        fun token(): AttemptToken = token
    }
    private data class FireworkAttempt(private val fireworkId: UUID, private val playerId: UUID, private val tick: Int, private val token: AttemptToken, private val killedMobs: MutableSet<UUID>) {
        fun fireworkId(): UUID = fireworkId
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
        fun token(): AttemptToken = token
        fun killedMobs(): MutableSet<UUID> = killedMobs
        constructor(fireworkId: UUID, playerId: UUID, tick: Int, token: AttemptToken) :
            this(fireworkId, playerId, tick, token, LinkedHashSet())
    }
}
