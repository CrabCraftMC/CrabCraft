package crabcraft.net.crabUtilities.happyghast

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Entity
import org.bukkit.entity.HappyGhast
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityMountEvent

/** Applies a transient flying-speed boost only while Happy Ghasts have player passengers. */
open class HappyGhastSpeedManager(private val plugin: CrabUtilities) : Listener {
    private val modifierKey = NamespacedKey(plugin, "happy_ghast_ridden_speed_boost")
    private val enabled: Boolean
    private val multiplier = plugin.getConfig().getDouble("tweaks.happy-ghast.ridden-speed-boost.multiplier", 2.0)
    init {
        var configEnabled = plugin.getConfig().getBoolean("tweaks.happy-ghast.ridden-speed-boost.enabled", false)
        if (configEnabled && multiplier <= 0.0) {
            plugin.getLogger().warning("tweaks.happy-ghast.ridden-speed-boost.multiplier must be positive (got $multiplier) — feature disabled.")
            configEnabled = false
        }
        enabled = configEnabled
    }
    open fun isEnabled(): Boolean = enabled
    open fun getMultiplier(): Double = multiplier
    /** Pick up already mounted ghasts after a reload. */
    open fun start() {
        if (!enabled) return
        for (world in Bukkit.getWorlds()) for (ghast in world.getEntitiesByClass(HappyGhast::class.java)) {
            if (hasPlayerPassenger(ghast, null)) applyBoost(ghast)
        }
    }
    open fun shutdown() {
        for (world in Bukkit.getWorlds()) for (ghast in world.getEntitiesByClass(HappyGhast::class.java)) removeBoost(ghast)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onMount(event: EntityMountEvent) {
        if (!enabled) return
        val ghast = event.getMount() as? HappyGhast ?: return
        if (event.getEntity() is Player) applyBoost(ghast)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onDismount(event: EntityDismountEvent) {
        if (!enabled) return
        val ghast = event.getDismounted() as? HappyGhast ?: return
        // The departing rider may still be in the passenger list at event time.
        if (!hasPlayerPassenger(ghast, event.getEntity())) removeBoost(ghast)
    }
    private fun hasPlayerPassenger(ghast: HappyGhast, except: Entity?): Boolean =
        ghast.getPassengers().any { it is Player && it !== except }
    private fun applyBoost(ghast: HappyGhast) {
        val flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED) ?: return
        flyingSpeed.removeModifier(modifierKey)
        // MULTIPLY_SCALAR_1 uses base * (1 + amount).
        flyingSpeed.addTransientModifier(AttributeModifier(modifierKey, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
    }
    private fun removeBoost(ghast: HappyGhast) { ghast.getAttribute(Attribute.FLYING_SPEED)?.removeModifier(modifierKey) }
}
