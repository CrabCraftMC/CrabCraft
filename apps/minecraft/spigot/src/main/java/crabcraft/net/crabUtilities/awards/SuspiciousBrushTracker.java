package crabcraft.net.crabUtilities.awards;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/** Records completed archaeology brushes in Minecraft's per-block mined stats. */
public final class SuspiciousBrushTracker implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Material brushed = completedBrushMaterial(event.getBlock().getType(), event.getTo());
        if (brushed != null) {
            player.incrementStatistic(Statistic.MINE_BLOCK, brushed);
        }
    }

    static Material completedBrushMaterial(Material from, Material to) {
        if (from == Material.SUSPICIOUS_SAND && to == Material.SAND) {
            return from;
        }
        if (from == Material.SUSPICIOUS_GRAVEL && to == Material.GRAVEL) {
            return from;
        }
        return null;
    }
}
