package crabcraft.net.crabUtilities.bingo

import crabcraft.net.crabUtilities.bingo.BingoTracking.isFresh
import crabcraft.net.crabUtilities.bingo.BingoTracking.putBounded
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
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Breeze
import org.bukkit.entity.BreezeWindCharge
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
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
    private val completion: BiConsumer<Player, BingoTask>,
) : AbstractBingoDetector() {
    private val spearSessions = HashMap<UUID, SpearSession>()
    private val breezeCharges = HashMap<UUID, BreezeChargeAttempt>()
    private val potProjectiles = HashMap<UUID, ProjectileAttempt>()
    private val fireworkShots = HashMap<UUID, FireworkAttempt>()
    private var lastPruneTick = Int.MIN_VALUE

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpearUseStarted(event: PlayerInteractEvent) {
        val hand = event.hand ?: return
        val item = event.item ?: return
        if (
            !event.action.isRightClick ||
                event.useItemInHand() == Event.Result.DENY ||
                !isSpear(item) ||
                !tracking.test(event.player, BingoTask.SPEAR_HIT_THREE)
        )
            return
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
            putBounded(
                breezeCharges,
                projectile.uniqueId,
                BreezeChargeAttempt(shooter.uniqueId, tick, detectorGeneration(), null, null),
            )
        }
        val player = shooter as? Player ?: return
        if (tracking.test(player, BingoTask.PROJECTILE_SMASH_FILLED_POT)) {
            putBounded(
                potProjectiles,
                projectile.uniqueId,
                ProjectileAttempt(
                    player.uniqueId,
                    projectile.world.uid,
                    projectile.x,
                    projectile.y,
                    projectile.z,
                    tick,
                    attemptToken(player.uniqueId),
                ),
            )
        }
        if (
            projectile is Firework &&
                projectile.isShotAtAngle &&
                tracking.test(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)
        ) {
            putBounded(
                fireworkShots,
                projectile.uniqueId,
                FireworkAttempt(projectile.uniqueId, player.uniqueId, tick, attemptToken(player.uniqueId)),
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageByEntityEvent) {
        val charge = event.entity as? BreezeWindCharge
        val damager = event.damager as? Player
        if (charge != null && damager != null) rememberBreezeDeflection(charge, damager)
        val mob = event.entity as? Mob ?: return
        val player = event.damageSource.causingEntity as? Player ?: return
        if (
            event.finalDamage <= 0.0 ||
                event.damageSource.damageType != DamageType.SPEAR ||
                !tracking.test(player, BingoTask.SPEAR_HIT_THREE) ||
                !player.hasActiveItem() ||
                !isSpear(player.activeItem)
        )
            return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        val playerId = player.uniqueId
        val activeHand = player.activeItemHand
        val spearType = player.activeItem.type
        var session = spearSessions[playerId]
        if (
            !CombatPolicy.sameSpearSession(
                session != null,
                session?.hand,
                activeHand,
                session?.spearType,
                spearType,
                session?.tick ?: tick,
                tick,
                SPEAR_SESSION_RETENTION_TICKS,
            ) || session == null || !isCurrent(playerId, session.token)
        ) {
            session = beginSpearSession(player, activeHand, spearType, tick)
        }
        session.hitMobs.add(mob.uniqueId)
        if (session.hitMobs.size >= 3) {
            spearSessions.remove(playerId, session)
            completion.accept(player, BingoTask.SPEAR_HIT_THREE)
        }
    }

    private fun rememberBreezeDeflection(charge: BreezeWindCharge, player: Player) {
        val chargeId = charge.uniqueId
        val attempt = breezeCharges[chargeId] ?: return
        if (
            attempt.detectorGeneration != detectorGeneration() ||
                !tracking.test(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)
        )
            return
        breezeCharges[chargeId] = attempt.copy(deflectorId = player.uniqueId, token = attemptToken(player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerProjectileHit(event: ProjectileHitEvent) {
        val charge = event.hitEntity as? BreezeWindCharge ?: return
        val player = event.entity.shooter as? Player ?: return
        rememberBreezeDeflection(charge, player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        pruneTransientStateIfDue(Bukkit.getCurrentTick())
        detectReflectedBreezeKill(event)
        detectFireworkKills(event)
    }

    private fun detectReflectedBreezeKill(event: EntityDeathEvent) {
        val breeze = event.entity as? Breeze ?: return
        val source = event.damageSource
        val charge = source.directEntity as? BreezeWindCharge ?: return
        val player = source.causingEntity as? Player ?: return
        val attempt = breezeCharges.remove(charge.uniqueId) ?: return
        val deflectorId = attempt.deflectorId ?: return
        val token = attempt.token ?: return
        if (
            !CombatPolicy.isExactReflectedBreezeKill(
                attempt.originalBreezeId,
                breeze.uniqueId,
                deflectorId,
                player.uniqueId,
            ) ||
                attempt.detectorGeneration != detectorGeneration() ||
                !isCurrent(deflectorId, token) ||
                !tracking.test(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)
        )
            return
        completion.accept(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)
    }

    private fun detectFireworkKills(event: EntityDeathEvent) {
        if (event.entity !is Enemy) return
        val source = event.damageSource
        val firework = source.directEntity as? Firework ?: return
        val player = source.causingEntity as? Player ?: return
        val attempt = fireworkShots[firework.uniqueId] ?: return
        if (
            !CombatPolicy.isExactFireworkKill(
                attempt.fireworkId,
                firework.uniqueId,
                attempt.playerId,
                player.uniqueId,
            ) ||
                !isCurrent(attempt.playerId, attempt.token) ||
                !tracking.test(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)
        )
            return
        attempt.killedMobs.add(event.entity.uniqueId)
        if (attempt.killedMobs.size >= 2) {
            fireworkShots.remove(firework.uniqueId, attempt)
            completion.accept(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileChangedBlock(event: EntityChangeBlockEvent) {
        val projectile = event.entity as? Projectile ?: return
        if (event.block.type != Material.DECORATED_POT || !CombatPolicy.isPotBreakReplacement(event.to)) return
        val attempt = potProjectiles.remove(projectile.uniqueId) ?: return
        val storedItem = (event.block.state as? DecoratedPot)?.inventory?.item
        val block = event.block
        if (
            !CombatPolicy.isQualifyingPotSmash(
                storedItem != null && !storedItem.isEmpty,
                attempt.worldId == block.world.uid,
                distanceSquared(attempt, block),
                POT_MINIMUM_DISTANCE_SQUARED,
            ) || !isCurrent(attempt.playerId, attempt.token)
        )
            return
        val player = Bukkit.getPlayer(attempt.playerId)
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
    fun onPlayerQuit(event: PlayerQuitEvent) = invalidatePlayerAttempts(event.player.uniqueId)

    override fun resetPlayer(playerId: UUID) = invalidatePlayerAttempts(playerId)

    override fun clear() {
        super.clear()
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
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                Runnable { spearSessions.remove(playerId, session) },
                SPEAR_SESSION_RETENTION_TICKS + 1L,
            )
        return session
    }

    private fun invalidatePlayerAttempts(playerId: UUID) {
        super.resetPlayer(playerId)
        spearSessions.remove(playerId)
        potProjectiles.values.removeIf { it.playerId == playerId }
        fireworkShots.values.removeIf { it.playerId == playerId }
        breezeCharges.replaceAll { _, attempt ->
            if (playerId == attempt.deflectorId) attempt.copy(deflectorId = null, token = null) else attempt
        }
    }

    private fun pruneTransientStateIfDue(tick: Int) {
        if (lastPruneTick != Int.MIN_VALUE) {
            val age = tick - lastPruneTick
            if (age >= 0 && age < 20) return
        }
        lastPruneTick = tick
        spearSessions.values.removeIf { !isFresh(it.tick, tick, SPEAR_SESSION_RETENTION_TICKS) }
        breezeCharges.values.removeIf {
            !isFresh(it.tick, tick, BREEZE_CHARGE_RETENTION_TICKS) || it.detectorGeneration != detectorGeneration()
        }
        potProjectiles.values.removeIf {
            !isFresh(it.tick, tick, PROJECTILE_RETENTION_TICKS) || !isCurrent(it.playerId, it.token)
        }
        fireworkShots.values.removeIf {
            !isFresh(it.tick, tick, PROJECTILE_RETENTION_TICKS) || !isCurrent(it.playerId, it.token)
        }
    }

    object CombatPolicy {
        @JvmStatic
        fun sameSpearSession(
            hasSession: Boolean,
            recordedHand: EquipmentSlot?,
            activeHand: EquipmentSlot?,
            recordedSpear: Material?,
            activeSpear: Material?,
            startedTick: Int,
            currentTick: Int,
            maximumAge: Int,
        ): Boolean =
            hasSession &&
                recordedHand == activeHand &&
                recordedSpear == activeSpear &&
                isFresh(startedTick, currentTick, maximumAge)

        @JvmStatic
        fun isExactReflectedBreezeKill(
            originalBreezeId: UUID,
            victimBreezeId: UUID,
            deflectorId: UUID,
            causingPlayerId: UUID,
        ): Boolean = originalBreezeId == victimBreezeId && deflectorId == causingPlayerId

        @JvmStatic
        fun isPotBreakReplacement(replacement: Material?): Boolean =
            replacement == Material.AIR || replacement == Material.WATER

        @JvmStatic
        fun isExactFireworkKill(
            launchedFireworkId: UUID,
            damagingFireworkId: UUID,
            shooterId: UUID,
            causingPlayerId: UUID,
        ): Boolean = launchedFireworkId == damagingFireworkId && shooterId == causingPlayerId

        @JvmStatic
        fun isQualifyingPotSmash(
            containsItem: Boolean,
            sameWorld: Boolean,
            distanceSquared: Double,
            minimumDistanceSquared: Double,
        ): Boolean =
            containsItem && sameWorld && distanceSquared.isFinite() && distanceSquared >= minimumDistanceSquared
    }

    private data class SpearSession(
        val playerId: UUID,
        val tick: Int,
        val hand: EquipmentSlot,
        val spearType: Material,
        val token: AttemptToken,
        val hitMobs: MutableSet<UUID>,
    )

    private data class BreezeChargeAttempt(
        val originalBreezeId: UUID,
        val tick: Int,
        val detectorGeneration: Long,
        val deflectorId: UUID?,
        val token: AttemptToken?,
    )

    private data class ProjectileAttempt(
        val playerId: UUID,
        val worldId: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val tick: Int,
        val token: AttemptToken,
    )

    private data class FireworkAttempt(
        val fireworkId: UUID,
        val playerId: UUID,
        val tick: Int,
        val token: AttemptToken,
        val killedMobs: MutableSet<UUID> = LinkedHashSet(),
    )

    companion object {
        private const val SPEAR_SESSION_RETENTION_TICKS = 20 * 16
        private const val BREEZE_CHARGE_RETENTION_TICKS = 20 * 60
        private const val PROJECTILE_RETENTION_TICKS = 20 * 60 * 5
        private const val POT_MINIMUM_DISTANCE_SQUARED = 10.0 * 10.0

        private fun isSpear(item: ItemStack?): Boolean = item != null && isSpear(item.type)

        private fun isSpear(material: Material?): Boolean = material != null && Tag.ITEMS_SPEARS.isTagged(material)

        private fun distanceSquared(attempt: ProjectileAttempt, block: Block): Double {
            if (attempt.worldId != block.world.uid) return Double.NaN
            val x = block.x + 0.5 - attempt.x
            val y = block.y + 0.5 - attempt.y
            val z = block.z + 0.5 - attempt.z
            return x * x + y * y + z * z
        }
    }
}
