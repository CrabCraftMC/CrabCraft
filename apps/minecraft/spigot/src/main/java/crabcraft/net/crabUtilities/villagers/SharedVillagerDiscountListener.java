package crabcraft.net.crabUtilities.villagers;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shares villager discounts earned by cures and raid victories with nearby players. */
public final class SharedVillagerDiscountListener implements Listener {
    private static final String CONFIG_PATH = "tweaks.shared-villager-discounts";
    private static final double DEFAULT_RADIUS = 100D;
    private static final long CURE_APPLICATION_DELAY_TICKS = 5L;
    private static final int CURE_MAJOR_POSITIVE = 20;
    private static final int CURE_MINOR_POSITIVE = 25;

    private final CrabUtilities plugin;

    public SharedVillagerDiscountListener(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onZombieVillagerCure(EntityTransformEvent event) {
        if (!isEnabled()
                || event.getTransformReason() != EntityTransformEvent.TransformReason.CURED
                || !(event.getEntity() instanceof ZombieVillager zombieVillager)
                || zombieVillager.getConversionPlayer() == null
                || !(event.getTransformedEntity() instanceof Villager villager)) {
            return;
        }

        List<UUID> nearbyPlayerIds = nearbyPlayers(villager.getWorld(), villager.getLocation(), radius()).stream()
                .map(Player::getUniqueId)
                .toList();
        if (nearbyPlayerIds.isEmpty()) {
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!villager.isValid()) {
                return;
            }
            nearbyPlayerIds.forEach(playerId -> applyCureDiscount(villager, playerId));
        }, CURE_APPLICATION_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaidFinish(RaidFinishEvent event) {
        if (!isEnabled()) {
            return;
        }

        PotionEffect heroDiscount = event.getWinners().stream()
                .map(player -> player.getPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (heroDiscount == null) {
            return;
        }

        for (Player player : nearbyPlayers(event.getWorld(), event.getRaid().getLocation(), radius())) {
            player.addPotionEffect(heroDiscount);
        }
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean(CONFIG_PATH + ".enabled", false);
    }

    private double radius() {
        return Math.max(0D, plugin.getConfig().getDouble(CONFIG_PATH + ".radius", DEFAULT_RADIUS));
    }

    static List<Player> nearbyPlayers(World world, Location origin, double radius) {
        if (radius <= 0D) {
            return List.of();
        }
        double radiusSquared = radius * radius;
        return world.getNearbyPlayers(origin, radius).stream()
                .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
                .filter(player -> player.getLocation().distanceSquared(origin) <= radiusSquared)
                .toList();
    }

    static void applyCureDiscount(Villager villager, UUID playerId) {
        Reputation reputation = villager.getReputation(playerId);
        reputation.setReputation(ReputationType.MAJOR_POSITIVE,
                Math.max(reputation.getReputation(ReputationType.MAJOR_POSITIVE), CURE_MAJOR_POSITIVE));
        reputation.setReputation(ReputationType.MINOR_POSITIVE,
                Math.max(reputation.getReputation(ReputationType.MINOR_POSITIVE), CURE_MINOR_POSITIVE));
        villager.setReputation(playerId, reputation);
    }
}
