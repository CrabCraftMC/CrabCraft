package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.entity.WaterBottleSplashEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Nautilus;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the adventure and mob-interaction tasks in Bingo #3. */
public final class BingoCardThreeAdventureListener implements BingoDetector {
    private static final int MAX_TRACKED_JOHNNIES = 4_096;
    private static final String JOHNNY_NAME = "Johnny";

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final Map<UUID, UUID> johnnyOwners = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;

    public BingoCardThreeAdventureListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNautilusTamed(EntityTameEvent event) {
        if (event.getEntity() instanceof Nautilus
                && event.getOwner() instanceof Player player
                && tracking.test(player, BingoTask.TAME_NAUTILUS)) {
            completion.accept(player, BingoTask.TAME_NAUTILUS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityNamed(PlayerNameEntityEvent event) {
        if (!(event.getEntity() instanceof Vindicator vindicator)
                || event.getName() == null
                || !JOHNNY_NAME.equals(PlainTextComponentSerializer.plainText().serialize(event.getName()))
                || !tracking.test(event.getPlayer(), BingoTask.JOHNNY_VINDICATOR_KILL)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        UUID vindicatorId = vindicator.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmJohnnyName(playerId, vindicatorId, token));
    }

    private void confirmJohnnyName(UUID playerId, UUID vindicatorId, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(vindicatorId);
        if (player == null
                || !(entity instanceof Vindicator vindicator)
                || !vindicator.isJohnny()
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.JOHNNY_VINDICATOR_KILL)) {
            return;
        }

        putBounded(johnnyOwners, vindicatorId, playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID deadEntityId = event.getEntity().getUniqueId();
        if (event.getEntity() instanceof Enemy
                && event.getDamageSource().getCausingEntity() instanceof Vindicator vindicator) {
            UUID playerId = johnnyOwners.get(vindicator.getUniqueId());
            Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
            if (player != null
                    && vindicator.isJohnny()
                    && tracking.test(player, BingoTask.JOHNNY_VINDICATOR_KILL)) {
                completion.accept(player, BingoTask.JOHNNY_VINDICATOR_KILL);
                johnnyOwners.remove(vindicator.getUniqueId(), playerId);
            }
        }
        johnnyOwners.remove(deadEntityId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGhastHooked(ProjectileHitEvent event) {
        if (event.getEntity() instanceof FishHook hook
                && event.getHitEntity() instanceof Ghast
                && hook.getShooter() instanceof Player player
                && tracking.test(player, BingoTask.HOOK_GHAST)) {
            completion.accept(player, BingoTask.HOOK_GHAST);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWaterBottleSplashed(WaterBottleSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player player)
                || !tracking.test(player, BingoTask.WATER_BOTTLE_EXTINGUISH_THREE)) {
            return;
        }

        Set<UUID> extinguishedMobs = new HashSet<>();
        for (LivingEntity entity : event.getToExtinguish()) {
            if (entity instanceof Mob) extinguishedMobs.add(entity.getUniqueId());
        }
        if (extinguishedMobs.size() >= 3) {
            completion.accept(player, BingoTask.WATER_BOTTLE_EXTINGUISH_THREE);
        }
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        johnnyOwners.values().removeIf(playerId::equals);
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
        johnnyOwners.clear();
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
    }

    private static <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_TRACKED_JOHNNIES) {
            map.remove(map.keySet().iterator().next());
        }
        map.put(key, value);
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}
}
