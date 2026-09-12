package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.entity.WaterBottleSplashEvent
import io.papermc.paper.event.player.PlayerNameEntityEvent
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Enemy
import org.bukkit.entity.FishHook
import org.bukkit.entity.Ghast
import org.bukkit.entity.Mob
import org.bukkit.entity.Nautilus
import org.bukkit.entity.Player
import org.bukkit.entity.Vindicator
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the adventure and mob-interaction tasks in Bingo #3. */
class BingoCardThreeAdventureListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
) : BingoDetector {
    private val johnnyOwners = HashMap<UUID, UUID>()
    private val playerGenerations = HashMap<UUID, Long>()
    private var detectorGeneration = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNautilusTamed(event: EntityTameEvent) {
        val owner = event.owner
        if (event.entity is Nautilus && owner is Player && tracking.test(owner, BingoTask.TAME_NAUTILUS)) {
            completion.accept(owner, BingoTask.TAME_NAUTILUS)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityNamed(event: PlayerNameEntityEvent) {
        val vindicator = event.entity as? Vindicator ?: return
        val name = event.name ?: return
        if (JOHNNY_NAME != PlainTextComponentSerializer.plainText().serialize(name)
            || !tracking.test(event.player, BingoTask.JOHNNY_VINDICATOR_KILL)) return
        val playerId = event.player.uniqueId
        val vindicatorId = vindicator.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmJohnnyName(playerId, vindicatorId, token) })
    }

    private fun confirmJohnnyName(playerId: UUID, vindicatorId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val vindicator = Bukkit.getEntity(vindicatorId)
        if (player == null || vindicator !is Vindicator || !vindicator.isJohnny
            || !isCurrent(playerId, token) || !tracking.test(player, BingoTask.JOHNNY_VINDICATOR_KILL)) return
        putBounded(johnnyOwners, vindicatorId, playerId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val deadEntityId = event.entity.uniqueId
        val vindicator = event.damageSource.causingEntity
        if (event.entity is Enemy && vindicator is Vindicator) {
            val playerId = johnnyOwners[vindicator.uniqueId]
            val player = playerId?.let(Bukkit::getPlayer)
            if (player != null && vindicator.isJohnny
                && tracking.test(player, BingoTask.JOHNNY_VINDICATOR_KILL)) {
                completion.accept(player, BingoTask.JOHNNY_VINDICATOR_KILL)
                johnnyOwners.remove(vindicator.uniqueId, playerId)
            }
        }
        johnnyOwners.remove(deadEntityId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGhastHooked(event: ProjectileHitEvent) {
        val hook = event.entity
        if (hook is FishHook && event.hitEntity is Ghast) {
            val player = hook.shooter
            if (player is Player && tracking.test(player, BingoTask.HOOK_GHAST)) {
                completion.accept(player, BingoTask.HOOK_GHAST)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWaterBottleSplashed(event: WaterBottleSplashEvent) {
        val player = event.potion.shooter as? Player ?: return
        if (!tracking.test(player, BingoTask.WATER_BOTTLE_EXTINGUISH_THREE)) return
        val extinguishedMobs = HashSet<UUID>()
        for (entity in event.toExtinguish) {
            if (entity is Mob) extinguishedMobs.add(entity.uniqueId)
        }
        if (extinguishedMobs.size >= 3) {
            completion.accept(player, BingoTask.WATER_BOTTLE_EXTINGUISH_THREE)
        }
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        johnnyOwners.values.removeIf(playerId::equals)
    }

    override fun clear() {
        detectorGeneration++
        playerGenerations.clear()
        johnnyOwners.clear()
    }

    private fun attemptToken(playerId: UUID): AttemptToken =
        AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration == detectorGeneration
            && token.playerGeneration == playerGenerations.getOrDefault(playerId, 0L)

    private data class AttemptToken(val detectorGeneration: Long, val playerGeneration: Long)

    companion object {
        private const val MAX_TRACKED_JOHNNIES = 4_096
        private const val JOHNNY_NAME = "Johnny"

        private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRACKED_JOHNNIES) {
                map.remove(map.keys.iterator().next())
            }
            map[key] = value
        }
    }
}
