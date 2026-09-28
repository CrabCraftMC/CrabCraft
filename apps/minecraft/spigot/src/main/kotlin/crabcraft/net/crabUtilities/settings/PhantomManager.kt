package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabUtilities
import java.util.UUID
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent

/** Enforces phantom preferences without altering statistics used by the Night Owl award. */
open class PhantomManager(
    private val plugin: CrabUtilities,
    private val settingsService: PlayerSettingsService,
) : Listener {
    private val enabled = plugin.getConfig().getBoolean("phantoms.suppress-for-opted-out", true)

    open fun start() {
        plugin
            .getLogger()
            .info(
                "Phantom manager active: per-player phantom modes " +
                    (if (enabled) "enabled" else "disabled") +
                    " (time-since-rest is left untouched, so the Night Owl award is unaffected)."
            )
    }

    private fun suppressesSpawn(uuid: UUID) =
        settingsService.isLoaded(uuid) && settingsService.getPhantomMode(uuid).suppressesSpawn()

    private fun suppressesAttack(uuid: UUID) =
        settingsService.isLoaded(uuid) && settingsService.getPhantomMode(uuid).suppressesAttack()

    /** Only cancel when every nearby player positively opts out of spawning. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (
            !enabled ||
                event.entityType != EntityType.PHANTOM ||
                event.spawnReason != CreatureSpawnEvent.SpawnReason.NATURAL
        )
            return
        val at = event.location
        val world = at.world ?: return
        var sawSuppressor = false
        for (player in world.players) {
            val location = player.location
            val dx = location.x - at.x
            val dz = location.z - at.z
            val dy = Math.abs(location.y - at.y)
            if (dx * dx + dz * dz <= H_RADIUS_SQ && dy <= V_RADIUS) {
                // Unknown settings also allow the spawn, preserving other players' phantoms.
                if (!suppressesSpawn(player.uniqueId)) return
                sawSuppressor = true
            }
        }
        if (sawSuppressor) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onPhantomTarget(event: EntityTargetLivingEntityEvent) {
        if (!enabled || event.entityType != EntityType.PHANTOM) return
        val target = event.target
        if (target is Player && suppressesAttack(target.uniqueId)) event.isCancelled = true
    }

    /** Also catches a phantom already mid-swoop when the preference changed. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onPhantomDamage(event: EntityDamageByEntityEvent) {
        if (!enabled || event.damager.type != EntityType.PHANTOM) return
        val victim = event.entity
        if (victim is Player && suppressesAttack(victim.uniqueId)) event.isCancelled = true
    }

    companion object {
        private const val H_RADIUS_SQ = 16.0 * 16.0
        private const val V_RADIUS = 48.0
    }
}
