package crabcraft.net.crabUtilities.happyghast

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Entity
import org.bukkit.entity.HappyGhast
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityMountEvent

/** Applies a transient speed modifier only while a player is riding a happy ghast. */
open class HappyGhastSpeedManager(plugin: CrabUtilities) : Listener {
    private val modifierKey = NamespacedKey(plugin, "happy_ghast_ridden_speed_boost")
    private val multiplier = plugin.config.getDouble("tweaks.happy-ghast.ridden-speed-boost.multiplier", 2.0)
    private val enabled: Boolean

    init {
        var configEnabled = plugin.config.getBoolean("tweaks.happy-ghast.ridden-speed-boost.enabled", false)
        if (configEnabled && multiplier <= 0.0) {
            plugin.logger.warning(
                "tweaks.happy-ghast.ridden-speed-boost.multiplier must be positive (got $multiplier) — feature disabled."
            )
            configEnabled = false
        }
        enabled = configEnabled
    }

    open fun isEnabled() = enabled

    open fun getMultiplier() = multiplier

    open fun start() {
        if (!enabled) return
        for (world in Bukkit.getWorlds()) for (ghast in world.getEntitiesByClass(HappyGhast::class.java)) {
            if (hasPlayerPassenger(ghast, null)) applyBoost(ghast)
        }
    }

    open fun shutdown() {
        for (world in Bukkit.getWorlds()) for (ghast in world.getEntitiesByClass(HappyGhast::class.java)) removeBoost(
            ghast
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onMount(event: EntityMountEvent) {
        if (!enabled) return
        val ghast = event.mount as? HappyGhast ?: return
        if (event.entity is Player) applyBoost(ghast)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onDismount(event: EntityDismountEvent) {
        if (!enabled) return
        val ghast = event.dismounted as? HappyGhast ?: return
        // The departing rider may still be in the passenger list at event time.
        if (!hasPlayerPassenger(ghast, event.entity)) removeBoost(ghast)
    }

    private fun hasPlayerPassenger(ghast: HappyGhast, except: Entity?) =
        ghast.passengers.any { it is Player && it !== except }

    private fun applyBoost(ghast: HappyGhast) {
        val flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED) ?: return
        flyingSpeed.removeModifier(modifierKey)
        flyingSpeed.addTransientModifier(
            AttributeModifier(
                modifierKey,
                multiplier - 1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1,
            )
        )
    }

    private fun removeBoost(ghast: HappyGhast) {
        ghast.getAttribute(Attribute.FLYING_SPEED)?.removeModifier(modifierKey)
    }
}
