package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import java.util.UUID

/** Enforces phantom preferences without modifying time-since-rest or other statistics. */
open class PhantomManager(private val plugin: CrabUtilities, private val settingsService: PlayerSettingsService) : Listener {
    private val enabled = plugin.getConfig().getBoolean("phantoms.suppress-for-opted-out", true)

    open fun start() {
        plugin.getLogger().info("Phantom manager active: per-player phantom modes " +
            (if (enabled) "enabled" else "disabled") +
            " (time-since-rest is left untouched, so the Night Owl award is unaffected).")
    }

    private fun suppressesSpawn(uuid: UUID): Boolean = settingsService.isLoaded(uuid) && settingsService.getPhantomMode(uuid).suppressesSpawn()
    private fun suppressesAttack(uuid: UUID): Boolean = settingsService.isLoaded(uuid) && settingsService.getPhantomMode(uuid).suppressesAttack()

    /** Only cancel natural spawns when every nearby player's loaded preference is OFF. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (!enabled || event.getEntityType() != EntityType.PHANTOM || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return
        val at = event.getLocation()
        val world = at.getWorld() ?: return
        var sawSuppressor = false
        for (player in world.getPlayers()) {
            val loc = player.getLocation()
            val dx = loc.getX() - at.getX()
            val dz = loc.getZ() - at.getZ()
            val dy = Math.abs(loc.getY() - at.getY())
            if (dx * dx + dz * dz <= H_RADIUS_SQ && dy <= V_RADIUS) {
                // A nearby player who permits spawns or has not loaded yet may own this spawn.
                if (!suppressesSpawn(player.getUniqueId())) return
                sawSuppressor = true
            }
        }
        if (sawSuppressor) event.setCancelled(true)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onPhantomTarget(event: EntityTargetLivingEntityEvent) {
        if (!enabled || event.getEntityType() != EntityType.PHANTOM) return
        val target = event.getTarget()
        if (target is Player && suppressesAttack(target.getUniqueId())) event.setCancelled(true)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onPhantomDamage(event: EntityDamageByEntityEvent) {
        if (!enabled || event.getDamager().getType() != EntityType.PHANTOM) return
        val victim = event.getEntity()
        if (victim is Player && suppressesAttack(victim.getUniqueId())) event.setCancelled(true)
    }

    companion object {
        private const val H_RADIUS_SQ = 16.0 * 16.0
        private const val V_RADIUS = 48.0
    }
}
