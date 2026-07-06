package crabcraft.net.crabUtilities.shulker;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Material;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Multiplies shulker shell drops based on who scored the kill, so hand-farming
 * is rewarded over fully automatic farms — without removing the vanilla luck.
 *
 * <p>Vanilla Java only ever drops a single shell, and Looting raises the
 * <em>chance</em> it drops (50% base, +6.25% per Looting level) rather than the
 * count. This tweak leaves that roll untouched: it only steps in <em>after</em>
 * a shell has actually dropped and multiplies the amount. So the drop can still
 * whiff (0 shells), Looting still improves the odds, and when it does land a
 * player kill yields {@code player-kill-multiplier}× shells (default 2×) while
 * any other kill yields {@code other-multiplier}× (default 1×, i.e. vanilla).
 *
 * <p>A player kill is detected via {@link org.bukkit.entity.LivingEntity#getKiller()},
 * which is non-null only when a player dealt (or is credited with) the killing
 * blow. Disabled by default; config is read live so a reload takes effect
 * without re-registration.
 */
public class ShulkerShellListener implements Listener {

    private final CrabUtilities plugin;

    public ShulkerShellListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(final EntityDeathEvent event) {
        if (!(event.getEntity() instanceof final Shulker shulker)) {
            return;
        }
        if (!this.plugin.getConfig().getBoolean("shulker-shells.enabled", false)) {
            return;
        }

        final int configured = shulker.getKiller() != null
                ? this.plugin.getConfig().getInt("shulker-shells.player-kill-multiplier", 2)
                : this.plugin.getConfig().getInt("shulker-shells.other-multiplier", 1);
        final int multiplier = Math.max(1, configured);
        if (multiplier == 1) {
            return; // leave the vanilla drop exactly as rolled
        }

        // Scale whatever vanilla actually rolled — nothing if the drop whiffed,
        // so the chance to get no shell is preserved.
        for (final ItemStack drop : event.getDrops()) {
            if (drop.getType() == Material.SHULKER_SHELL) {
                drop.setAmount(drop.getAmount() * multiplier);
            }
        }
    }
}
