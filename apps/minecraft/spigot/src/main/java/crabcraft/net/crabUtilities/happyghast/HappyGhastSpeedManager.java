package crabcraft.net.crabUtilities.happyghast;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;

/**
 * Speeds up Happy Ghasts, but only while a player is riding them.
 *
 * <p>A ridden happy ghast's travel speed is the pilot's input scaled by
 * {@code 3.9 x flying_speed} (vanilla base 0.05, about 3.6 blocks/sec), so the
 * boost is applied as a {@code MULTIPLY_SCALAR_1} modifier on the
 * {@code flying_speed} attribute: ridden speed scales linearly with the
 * configured multiplier. The Speed potion effect does not affect happy ghasts,
 * so the attribute is the only lever.
 *
 * <p>The modifier is <em>transient</em> (never written to the entity's saved
 * data), so a crash or unload mid-ride can never leave a permanently fast
 * ghast behind. It is added when a player mounts and removed when the last
 * player passenger dismounts; ghasts already being ridden are picked up by a
 * sweep in {@link #start()} so {@code /crabutilities reload} keeps mid-flight
 * riders boosted, and {@link #shutdown()} sweeps the modifier off every loaded
 * ghast so disabling the feature takes effect immediately.
 */
public class HappyGhastSpeedManager implements Listener {

    private final CrabUtilities plugin;
    private final NamespacedKey modifierKey;
    private final boolean enabled;
    private final double multiplier;

    public HappyGhastSpeedManager(CrabUtilities plugin) {
        this.plugin = plugin;
        this.modifierKey = new NamespacedKey(plugin, "happy_ghast_ridden_speed_boost");
        boolean configEnabled = plugin.getConfig().getBoolean("happy-ghast.ridden-speed-boost.enabled", false);
        this.multiplier = plugin.getConfig().getDouble("happy-ghast.ridden-speed-boost.multiplier", 2.0);
        if (configEnabled && multiplier <= 0.0) {
            plugin.getLogger().warning("happy-ghast.ridden-speed-boost.multiplier must be positive (got "
                    + multiplier + ") — feature disabled.");
            configEnabled = false;
        }
        this.enabled = configEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getMultiplier() {
        return multiplier;
    }

    /** Boost any ghast that is already being ridden (e.g. across a config reload). */
    public void start() {
        if (!enabled) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
                if (hasPlayerPassenger(ghast, null)) {
                    applyBoost(ghast);
                }
            }
        }
    }

    /** Strip the boost from every loaded ghast so a disable/reload restores vanilla speed. */
    public void shutdown() {
        for (World world : Bukkit.getWorlds()) {
            for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
                removeBoost(ghast);
            }
        }
    }

    /**
     * MONITOR so the boost is only applied once every plugin that might cancel
     * the mount has had its say; the modifier is entity state, not event state.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getMount() instanceof HappyGhast ghast && event.getEntity() instanceof Player) {
            applyBoost(ghast);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (!enabled) {
            return;
        }
        if (!(event.getDismounted() instanceof HappyGhast ghast)) {
            return;
        }
        // A ghast seats up to four riders; keep the boost while any other
        // player is still aboard. The departing rider may or may not still be
        // in the passenger list at event time, so exclude it explicitly.
        if (!hasPlayerPassenger(ghast, event.getEntity())) {
            removeBoost(ghast);
        }
    }

    private boolean hasPlayerPassenger(HappyGhast ghast, Entity except) {
        for (Entity passenger : ghast.getPassengers()) {
            if (passenger instanceof Player && passenger != except) {
                return true;
            }
        }
        return false;
    }

    private void applyBoost(HappyGhast ghast) {
        AttributeInstance flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED);
        if (flyingSpeed == null) {
            return;
        }
        flyingSpeed.removeModifier(modifierKey);
        // MULTIPLY_SCALAR_1 computes value = base * (1 + amount), so a
        // configured multiplier of 2.0 becomes an amount of 1.0.
        flyingSpeed.addTransientModifier(new AttributeModifier(
                modifierKey, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private void removeBoost(HappyGhast ghast) {
        AttributeInstance flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED);
        if (flyingSpeed != null) {
            flyingSpeed.removeModifier(modifierKey);
        }
    }
}
