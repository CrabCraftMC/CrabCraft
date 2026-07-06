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
 * Makes shulker shell drops depend on who scored the kill, so hand-farming is
 * rewarded over fully automatic farms:
 *
 * <ul>
 *   <li><b>Player kill</b> (melee, arrow, etc.) → {@code player-kill-count} shells (default 2).</li>
 *   <li><b>Any other kill</b> (crushing/suffocation/fire/mob farms) → {@code other-count} shells (default 1).</li>
 * </ul>
 *
 * <p>The configured counts are deterministic and replace the vanilla 0–1
 * (plus Looting) roll, so farm output is predictable. A player kill is detected
 * via {@link org.bukkit.entity.LivingEntity#getKiller()}, which is non-null only
 * when a player dealt (or is credited with) the killing blow.
 *
 * <p>Disabled by default; the config is read live so a reload takes effect
 * without re-registration.
 */
public class ShulkerShellListener implements Listener {

    private final CrabUtilities plugin;

    public ShulkerShellListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(final EntityDeathEvent event) {
        if (!(event.getEntity() instanceof final Shulker shulker)) {
            return;
        }
        if (!this.plugin.getConfig().getBoolean("shulker-shells.enabled", false)) {
            return;
        }

        final int count = shulker.getKiller() != null
                ? this.plugin.getConfig().getInt("shulker-shells.player-kill-count", 2)
                : this.plugin.getConfig().getInt("shulker-shells.other-count", 1);

        // Replace vanilla's variable shell drop with the fixed configured count.
        event.getDrops().removeIf(drop -> drop.getType() == Material.SHULKER_SHELL);
        if (count > 0) {
            event.getDrops().add(new ItemStack(Material.SHULKER_SHELL, count));
        }
    }
}
