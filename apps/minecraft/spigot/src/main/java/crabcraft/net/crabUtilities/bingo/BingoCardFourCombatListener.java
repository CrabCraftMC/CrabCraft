package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.DecoratedPot;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Breeze;
import org.bukkit.entity.BreezeWindCharge;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the combat and projectile tasks in Bingo #4. */
public final class BingoCardFourCombatListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final int SPEAR_SESSION_RETENTION_TICKS = 20 * 16;
    private static final int BREEZE_CHARGE_RETENTION_TICKS = 20 * 60;
    private static final int PROJECTILE_RETENTION_TICKS = 20 * 60 * 5;
    private static final double POT_MINIMUM_DISTANCE_SQUARED = 10.0 * 10.0;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final Map<UUID, SpearSession> spearSessions = new HashMap<>();
    private final Map<UUID, BreezeChargeAttempt> breezeCharges = new HashMap<>();
    private final Map<UUID, ProjectileAttempt> potProjectiles = new HashMap<>();
    private final Map<UUID, FireworkAttempt> fireworkShots = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;
    private int lastPruneTick = Integer.MIN_VALUE;

    public BingoCardFourCombatListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpearUseStarted(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()
                || event.getHand() == null
                || event.useItemInHand() == Event.Result.DENY
                || event.getItem() == null
                || !isSpear(event.getItem())
                || !tracking.test(event.getPlayer(), BingoTask.SPEAR_HIT_THREE)) {
            return;
        }

        beginSpearSession(
                event.getPlayer(), event.getHand(), event.getItem().getType(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStoppedUsingItem(PlayerStopUsingItemEvent event) {
        spearSessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunched(ProjectileLaunchEvent event) {
        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);

        Projectile projectile = event.getEntity();
        if (projectile instanceof BreezeWindCharge
                && projectile.getShooter() instanceof Breeze breeze) {
            putBounded(
                    breezeCharges,
                    projectile.getUniqueId(),
                    new BreezeChargeAttempt(
                            breeze.getUniqueId(), tick, detectorGeneration, null, null));
        }

        if (!(projectile.getShooter() instanceof Player player)) return;

        if (tracking.test(player, BingoTask.PROJECTILE_SMASH_FILLED_POT)) {
            putBounded(
                    potProjectiles,
                    projectile.getUniqueId(),
                    new ProjectileAttempt(
                            player.getUniqueId(),
                            projectile.getWorld().getUID(),
                            projectile.getX(),
                            projectile.getY(),
                            projectile.getZ(),
                            tick,
                            attemptToken(player.getUniqueId())));
        }

        if (projectile instanceof Firework firework
                && firework.isShotAtAngle()
                && tracking.test(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)) {
            putBounded(
                    fireworkShots,
                    firework.getUniqueId(),
                    new FireworkAttempt(
                            firework.getUniqueId(),
                            player.getUniqueId(),
                            tick,
                            attemptToken(player.getUniqueId())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof BreezeWindCharge charge
                && event.getDamager() instanceof Player player) {
            rememberBreezeDeflection(charge, player);
        }

        if (!(event.getEntity() instanceof Mob mob)
                || event.getFinalDamage() <= 0.0
                || !DamageType.SPEAR.equals(event.getDamageSource().getDamageType())
                || !(event.getDamageSource().getCausingEntity() instanceof Player player)
                || !tracking.test(player, BingoTask.SPEAR_HIT_THREE)
                || !player.hasActiveItem()
                || !isSpear(player.getActiveItem())) {
            return;
        }

        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);
        UUID playerId = player.getUniqueId();
        EquipmentSlot activeHand = player.getActiveItemHand();
        Material spearType = player.getActiveItem().getType();
        SpearSession session = spearSessions.get(playerId);
        if (!CombatPolicy.sameSpearSession(
                session != null,
                session == null ? null : session.hand(),
                activeHand,
                session == null ? null : session.spearType(),
                spearType,
                session == null ? tick : session.tick(),
                tick,
                SPEAR_SESSION_RETENTION_TICKS)
                || !isCurrent(playerId, session.token())) {
            session = beginSpearSession(player, activeHand, spearType, tick);
        }

        session.hitMobs().add(mob.getUniqueId());
        if (session.hitMobs().size() >= 3) {
            spearSessions.remove(playerId, session);
            completion.accept(player, BingoTask.SPEAR_HIT_THREE);
        }
    }

    private void rememberBreezeDeflection(BreezeWindCharge charge, Player player) {
        UUID chargeId = charge.getUniqueId();
        BreezeChargeAttempt attempt = breezeCharges.get(chargeId);
        if (attempt == null
                || attempt.detectorGeneration() != detectorGeneration
                || !tracking.test(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)) {
            return;
        }

        breezeCharges.put(
                chargeId,
                attempt.withDeflector(
                        player.getUniqueId(), attemptToken(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerProjectileHit(ProjectileHitEvent event) {
        if (event.getHitEntity() instanceof BreezeWindCharge charge
                && event.getEntity().getShooter() instanceof Player player) {
            rememberBreezeDeflection(charge, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);
        detectReflectedBreezeKill(event);
        detectFireworkKills(event);
    }

    private void detectReflectedBreezeKill(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Breeze breeze)) return;

        DamageSource source = event.getDamageSource();
        if (!(source.getDirectEntity() instanceof BreezeWindCharge charge)
                || !(source.getCausingEntity() instanceof Player player)) {
            return;
        }

        BreezeChargeAttempt attempt = breezeCharges.remove(charge.getUniqueId());
        if (attempt == null
                || attempt.deflectorId() == null
                || attempt.token() == null
                || !CombatPolicy.isExactReflectedBreezeKill(
                        attempt.originalBreezeId(),
                        breeze.getUniqueId(),
                        attempt.deflectorId(),
                        player.getUniqueId())
                || attempt.detectorGeneration() != detectorGeneration
                || !isCurrent(attempt.deflectorId(), attempt.token())
                || !tracking.test(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE)) {
            return;
        }

        completion.accept(player, BingoTask.REFLECTED_BREEZE_WIND_CHARGE);
    }

    private void detectFireworkKills(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;

        DamageSource source = event.getDamageSource();
        if (!(source.getDirectEntity() instanceof Firework firework)
                || !(source.getCausingEntity() instanceof Player player)) {
            return;
        }

        FireworkAttempt attempt = fireworkShots.get(firework.getUniqueId());
        if (attempt == null
                || !CombatPolicy.isExactFireworkKill(
                        attempt.fireworkId(),
                        firework.getUniqueId(),
                        attempt.playerId(),
                        player.getUniqueId())
                || !isCurrent(attempt.playerId(), attempt.token())
                || !tracking.test(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO)) {
            return;
        }

        attempt.killedMobs().add(event.getEntity().getUniqueId());
        if (attempt.killedMobs().size() >= 2) {
            fireworkShots.remove(firework.getUniqueId(), attempt);
            completion.accept(player, BingoTask.CROSSBOW_FIREWORK_KILL_TWO);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileChangedBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Projectile projectile)
                || event.getBlock().getType() != Material.DECORATED_POT
                || !CombatPolicy.isPotBreakReplacement(event.getTo())) {
            return;
        }

        ProjectileAttempt attempt = potProjectiles.remove(projectile.getUniqueId());
        if (attempt == null) return;

        ItemStack storedItem = event.getBlock().getState() instanceof DecoratedPot pot
                ? pot.getInventory().getItem()
                : null;
        Block block = event.getBlock();
        double distanceSquared = distanceSquared(attempt, block);
        if (!CombatPolicy.isQualifyingPotSmash(
                        storedItem != null && !storedItem.isEmpty(),
                        attempt.worldId().equals(block.getWorld().getUID()),
                        distanceSquared,
                        POT_MINIMUM_DISTANCE_SQUARED)
                || !isCurrent(attempt.playerId(), attempt.token())) {
            return;
        }

        Player player = Bukkit.getPlayer(attempt.playerId());
        if (player != null
                && tracking.test(player, BingoTask.PROJECTILE_SMASH_FILLED_POT)) {
            completion.accept(player, BingoTask.PROJECTILE_SMASH_FILLED_POT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        breezeCharges.remove(entityId);
        potProjectiles.remove(entityId);
        fireworkShots.remove(entityId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        invalidatePlayerAttempts(event.getPlayer().getUniqueId());
    }

    @Override
    public void resetPlayer(UUID playerId) {
        invalidatePlayerAttempts(playerId);
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
        lastPruneTick = Integer.MIN_VALUE;
        spearSessions.clear();
        breezeCharges.clear();
        potProjectiles.clear();
        fireworkShots.clear();
    }

    private SpearSession beginSpearSession(
            Player player, EquipmentSlot hand, Material spearType, int tick) {
        UUID playerId = player.getUniqueId();
        SpearSession session = new SpearSession(
                playerId,
                tick,
                hand,
                spearType,
                attemptToken(playerId),
                new LinkedHashSet<>());
        putBounded(spearSessions, playerId, session);
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> spearSessions.remove(playerId, session),
                SPEAR_SESSION_RETENTION_TICKS + 1L);
        return session;
    }

    private void invalidatePlayerAttempts(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        spearSessions.remove(playerId);
        potProjectiles.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        fireworkShots.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        breezeCharges.replaceAll((ignored, attempt) ->
                playerId.equals(attempt.deflectorId()) ? attempt.withoutDeflector() : attempt);
    }

    private void pruneTransientStateIfDue(int tick) {
        if (lastPruneTick != Integer.MIN_VALUE) {
            int age = tick - lastPruneTick;
            if (age >= 0 && age < 20) return;
        }
        lastPruneTick = tick;
        spearSessions.values().removeIf(session ->
                !isFresh(session.tick(), tick, SPEAR_SESSION_RETENTION_TICKS));
        breezeCharges.values().removeIf(attempt ->
                !isFresh(attempt.tick(), tick, BREEZE_CHARGE_RETENTION_TICKS)
                        || attempt.detectorGeneration() != detectorGeneration);
        potProjectiles.values().removeIf(attempt ->
                !isFresh(attempt.tick(), tick, PROJECTILE_RETENTION_TICKS)
                        || !isCurrent(attempt.playerId(), attempt.token()));
        fireworkShots.values().removeIf(attempt ->
                !isFresh(attempt.tick(), tick, PROJECTILE_RETENTION_TICKS)
                        || !isCurrent(attempt.playerId(), attempt.token()));
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
    }

    private static boolean isSpear(ItemStack item) {
        return item != null && isSpear(item.getType());
    }

    private static boolean isSpear(Material material) {
        return material != null && Tag.ITEMS_SPEARS.isTagged(material);
    }

    private static double distanceSquared(ProjectileAttempt attempt, Block block) {
        if (!attempt.worldId().equals(block.getWorld().getUID())) {
            return Double.NaN;
        }
        double x = block.getX() + 0.5 - attempt.x();
        double y = block.getY() + 0.5 - attempt.y();
        double z = block.getZ() + 0.5 - attempt.z();
        return x * x + y * y + z * z;
    }

    private static boolean isFresh(int earlier, int current, int maximumAge) {
        int age = current - earlier;
        return age >= 0 && age <= maximumAge;
    }

    private static <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_TRANSIENT_ENTRIES) {
            map.remove(map.keySet().iterator().next());
        }
        map.put(key, value);
    }

    static final class CombatPolicy {
        private CombatPolicy() {}

        static boolean sameSpearSession(
                boolean hasSession,
                EquipmentSlot recordedHand,
                EquipmentSlot activeHand,
                Material recordedSpear,
                Material activeSpear,
                int startedTick,
                int currentTick,
                int maximumAge) {
            return hasSession
                    && recordedHand == activeHand
                    && recordedSpear == activeSpear
                    && isFresh(startedTick, currentTick, maximumAge);
        }

        static boolean isExactReflectedBreezeKill(
                UUID originalBreezeId,
                UUID victimBreezeId,
                UUID deflectorId,
                UUID causingPlayerId) {
            return originalBreezeId.equals(victimBreezeId)
                    && deflectorId.equals(causingPlayerId);
        }

        static boolean isPotBreakReplacement(Material replacement) {
            return replacement == Material.AIR || replacement == Material.WATER;
        }

        static boolean isExactFireworkKill(
                UUID launchedFireworkId,
                UUID damagingFireworkId,
                UUID shooterId,
                UUID causingPlayerId) {
            return launchedFireworkId.equals(damagingFireworkId)
                    && shooterId.equals(causingPlayerId);
        }

        static boolean isQualifyingPotSmash(
                boolean containsItem,
                boolean sameWorld,
                double distanceSquared,
                double minimumDistanceSquared) {
            return containsItem
                    && sameWorld
                    && Double.isFinite(distanceSquared)
                    && distanceSquared >= minimumDistanceSquared;
        }
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record SpearSession(
            UUID playerId,
            int tick,
            EquipmentSlot hand,
            Material spearType,
            AttemptToken token,
            Set<UUID> hitMobs) {}

    private record BreezeChargeAttempt(
            UUID originalBreezeId,
            int tick,
            long detectorGeneration,
            UUID deflectorId,
            AttemptToken token) {
        BreezeChargeAttempt withDeflector(UUID playerId, AttemptToken token) {
            return new BreezeChargeAttempt(
                    originalBreezeId, tick, detectorGeneration, playerId, token);
        }

        BreezeChargeAttempt withoutDeflector() {
            return new BreezeChargeAttempt(
                    originalBreezeId, tick, detectorGeneration, null, null);
        }
    }

    private record ProjectileAttempt(
            UUID playerId,
            UUID worldId,
            double x,
            double y,
            double z,
            int tick,
            AttemptToken token) {}

    private record FireworkAttempt(
            UUID fireworkId,
            UUID playerId,
            int tick,
            AttemptToken token,
            Set<UUID> killedMobs) {
        private FireworkAttempt(
                UUID fireworkId, UUID playerId, int tick, AttemptToken token) {
            this(fireworkId, playerId, tick, token, new LinkedHashSet<>());
        }
    }
}
