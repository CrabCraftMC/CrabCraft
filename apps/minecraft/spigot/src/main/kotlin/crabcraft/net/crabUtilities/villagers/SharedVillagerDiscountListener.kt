package crabcraft.net.crabUtilities.villagers

import com.destroystokyo.paper.entity.villager.ReputationType
import crabcraft.net.crabUtilities.CrabUtilities
import java.util.UUID
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.entity.ZombieVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.raid.RaidFinishEvent
import org.bukkit.potion.PotionEffectType

/** Shares villager discounts earned by cures and raid victories with nearby players. */
class SharedVillagerDiscountListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onZombieVillagerCure(event: EntityTransformEvent) {
        if (!isEnabled() || event.transformReason != EntityTransformEvent.TransformReason.CURED) return
        val zombieVillager = event.entity as? ZombieVillager ?: return
        if (zombieVillager.conversionPlayer == null) return
        val villager = event.transformedEntity as? Villager ?: return
        val nearbyPlayerIds = nearbyPlayers(villager.world, villager.location, radius()).map { it.uniqueId }
        if (nearbyPlayerIds.isEmpty()) return
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (villager.isValid) nearbyPlayerIds.forEach { applyCureDiscount(villager, it) }
            },
            CURE_APPLICATION_DELAY_TICKS,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRaidFinish(event: RaidFinishEvent) {
        if (!isEnabled()) return
        val heroDiscount =
            event.winners.firstNotNullOfOrNull { it.getPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE) } ?: return
        for (player in nearbyPlayers(event.world, event.raid.location, radius())) player.addPotionEffect(heroDiscount)
    }

    private fun isEnabled() = plugin.config.getBoolean("$CONFIG_PATH.enabled", false)

    private fun radius() = maxOf(0.0, plugin.config.getDouble("$CONFIG_PATH.radius", DEFAULT_RADIUS))

    companion object {
        private const val CONFIG_PATH = "tweaks.shared-villager-discounts"
        private const val DEFAULT_RADIUS = 100.0
        private const val CURE_APPLICATION_DELAY_TICKS = 5L
        private const val CURE_MAJOR_POSITIVE = 20
        private const val CURE_MINOR_POSITIVE = 25

        @JvmStatic
        fun nearbyPlayers(world: World, origin: Location, radius: Double): List<Player> {
            if (radius <= 0.0) return emptyList()
            val radiusSquared = radius * radius
            return world.getNearbyPlayers(origin, radius).filter {
                it.gameMode != GameMode.SPECTATOR && it.location.distanceSquared(origin) <= radiusSquared
            }
        }

        @JvmStatic
        fun applyCureDiscount(villager: Villager, playerId: UUID) {
            val reputation = villager.getReputation(playerId)
            reputation.setReputation(
                ReputationType.MAJOR_POSITIVE,
                maxOf(reputation.getReputation(ReputationType.MAJOR_POSITIVE), CURE_MAJOR_POSITIVE),
            )
            reputation.setReputation(
                ReputationType.MINOR_POSITIVE,
                maxOf(reputation.getReputation(ReputationType.MINOR_POSITIVE), CURE_MINOR_POSITIVE),
            )
            villager.setReputation(playerId, reputation)
        }
    }
}
