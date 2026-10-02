package crabcraft.net.crabUtilities.awards;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Counts player-attributed harvest drops, never pickups; starts at deployment. */
public final class HarvestAwardTracker implements Listener {

    static final Map<String, NamespacedKey> DATA_KEYS = Map.of(
            "harvest_honeycomb", new NamespacedKey("crabutilities", "harvest_honeycomb_v1"),
            "harvest_cocoa", new NamespacedKey("crabutilities", "harvest_cocoa_v1"),
            "harvest_ink_sac", new NamespacedKey("crabutilities", "harvest_ink_sac_v1"),
            "harvest_glow_ink_sac", new NamespacedKey("crabutilities", "harvest_glow_ink_sac_v1"));

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scores(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearBlockEvent event) {
        if (event.isCancelled()) return;
        Material block = event.getBlock().getType();
        if (block != Material.BEEHIVE && block != Material.BEE_NEST) return;
        add(event.getPlayer(), "harvest_honeycomb", count(event.getDrops(), Material.HONEYCOMB));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        if (event.isCancelled() || event.getBlockState().getType() != Material.COCOA) return;
        if (!(event.getBlockState().getBlockData() instanceof Ageable cocoa)
                || cocoa.getAge() != cocoa.getMaximumAge()) return;
        add(event.getPlayer(), "harvest_cocoa", count(
                event.getItems().stream().map(item -> item.getItemStack()).toList(), Material.COCOA_BEANS));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (event.isCancelled()) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (event.getEntityType() == EntityType.SQUID) {
            add(killer, "harvest_ink_sac", count(event.getDrops(), Material.INK_SAC));
        } else if (event.getEntityType() == EntityType.GLOW_SQUID) {
            add(killer, "harvest_glow_ink_sac", count(event.getDrops(), Material.GLOW_INK_SAC));
        }
    }

    private static long count(Iterable<ItemStack> drops, Material material) {
        long count = 0;
        for (ItemStack drop : drops) {
            if (drop.getType() == material) count += drop.getAmount();
        }
        return count;
    }

    private static void add(Player player, String award, long amount) {
        if (amount <= 0) return;
        long previous = scores(player).get(award);
        player.getPersistentDataContainer().set(DATA_KEYS.get(award), PersistentDataType.LONG,
                Math.addExact(previous, amount));
    }

    /** Called on the server thread; counters survive logout, restart and reload. */
    public static Map<String, Long> scores(Player player) {
        Map<String, Long> scores = new HashMap<>();
        var data = player.getPersistentDataContainer();
        DATA_KEYS.forEach((award, key) -> {
            Long count = data.get(key, PersistentDataType.LONG);
            if (count == null) {
                count = 0L;
                data.set(key, PersistentDataType.LONG, count);
            }
            if (count < 0) throw new IllegalArgumentException("Invalid harvest count for " + award);
            scores.put(award, count);
        });
        return Map.copyOf(scores);
    }

    /** Missing or unreadable offline counters must not overwrite existing scores. */
    public static Map<String, Long> scores(Path playerDataFile) {
        if (!Files.isRegularFile(playerDataFile)) return Map.of();
        try {
            var playerData = NbtIo.readCompressed(playerDataFile, NbtAccounter.defaultQuota());
            var values = playerData.getCompound("BukkitValues");
            if (values.isEmpty()) return Map.of();
            Map<String, Long> scores = new HashMap<>();
            DATA_KEYS.forEach((award, key) -> values.get().getLong(key.toString())
                    .filter(count -> count >= 0).ifPresent(count -> scores.put(award, count)));
            return Map.copyOf(scores);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
