package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.entity.ShulkerDuplicateEvent;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Raid;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Strider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BellResonateEvent;
import org.bukkit.event.block.BellRingEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.StriderTemperatureChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the projectile, raid and ridden-mob tasks in Bingo #3. */
public final class BingoCardThreeCombatListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final int SHULKER_DUPLICATION_CORRELATION_TICKS = 1;
    // A vanilla bell begins resonating shortly after it is rung and applies the
    // reveal roughly 45 ticks later. Leave a little margin for tick timing.
    private static final int BELL_RESONANCE_CORRELATION_TICKS = 60;
    private static final int PROJECTILE_RETENTION_TICKS = 20 * 60 * 5;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final Map<UUID, ShulkerHit> shulkerHits = new HashMap<>();
    private final Map<BlockKey, TimedAttempt> bellRings = new HashMap<>();
    private final Map<UUID, PiercingShot> piercingShots = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;
    private int lastPruneTick = Integer.MIN_VALUE;

    public BingoCardThreeCombatListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);

        if (event.getEntity() instanceof ShulkerBullet bullet
                && event.getHitEntity() instanceof Shulker hitShulker
                && bullet.getShooter() instanceof Shulker firingShulker
                && !firingShulker.getUniqueId().equals(hitShulker.getUniqueId())
                && bullet.getTarget() instanceof Player target
                && tracking.test(target, BingoTask.SHULKER_BULLET_DUPLICATE)) {
            AttemptToken token = attemptToken(target.getUniqueId());
            ShulkerHit hit = new ShulkerHit(target.getUniqueId(), tick, token);
            UUID shulkerId = hitShulker.getUniqueId();
            putBounded(shulkerHits, shulkerId, hit);
            Bukkit.getScheduler().runTaskLater(
                    plugin, () -> shulkerHits.remove(shulkerId, hit), 2L);
        }

        if (event.getEntity() instanceof AbstractArrow
                && event.getHitBlock() != null) {
            piercingShots.remove(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShulkerDuplicated(ShulkerDuplicateEvent event) {
        int tick = Bukkit.getCurrentTick();
        ShulkerHit hit = shulkerHits.remove(event.getParent().getUniqueId());
        if (hit == null
                || !isFresh(hit.tick(), tick, SHULKER_DUPLICATION_CORRELATION_TICKS)
                || !isCurrent(hit.playerId(), hit.token())) {
            return;
        }

        UUID childId = event.getEntity().getUniqueId();
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmShulkerDuplicate(hit, childId));
    }

    private void confirmShulkerDuplicate(ShulkerHit hit, UUID childId) {
        Player player = Bukkit.getPlayer(hit.playerId());
        if (player != null
                && Bukkit.getEntity(childId) instanceof Shulker child
                && child.isValid()
                && !child.isDead()
                && isCurrent(hit.playerId(), hit.token())
                && tracking.test(player, BingoTask.SHULKER_BULLET_DUPLICATE)) {
            completion.accept(player, BingoTask.SHULKER_BULLET_DUPLICATE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStriderTemperatureChanged(StriderTemperatureChangeEvent event) {
        Strider strider = event.getEntity();
        if (event.isShivering() || !strider.isInLava() || strider.getPassengers().isEmpty()) {
            return;
        }

        Entity controllingPassenger = strider.getPassengers().getFirst();
        if (controllingPassenger instanceof Player player
                && tracking.test(player, BingoTask.WARM_RIDDEN_STRIDER)) {
            completion.accept(player, BingoTask.WARM_RIDDEN_STRIDER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBellRung(BellRingEvent event) {
        BlockKey bell = BlockKey.from(event.getBlock());
        bellRings.remove(bell);
        if (!(event.getEntity() instanceof Player player)
                || !tracking.test(player, BingoTask.RAID_BELL_REVEAL_THREE)) {
            return;
        }

        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);
        TimedAttempt attempt = new TimedAttempt(
                player.getUniqueId(), tick, attemptToken(player.getUniqueId()));
        putBounded(bellRings, bell, attempt);
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> bellRings.remove(bell, attempt), BELL_RESONANCE_CORRELATION_TICKS + 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBellResonated(BellResonateEvent event) {
        BlockKey bell = BlockKey.from(event.getBlock());
        TimedAttempt attempt = bellRings.remove(bell);
        int tick = Bukkit.getCurrentTick();
        if (attempt == null
                || !isFresh(attempt.tick(), tick, BELL_RESONANCE_CORRELATION_TICKS)
                || !isCurrent(attempt.playerId(), attempt.token())) {
            return;
        }
        long raiders = event.getResonatedEntities().stream()
                .filter(entity -> entity instanceof Raider)
                .map(entity -> (Raider) entity)
                .filter(Raider::isValid)
                .filter(raider -> !raider.isDead())
                .filter(raider -> {
                    Raid raid = raider.getRaid();
                    return raid != null
                            && raid.isStarted()
                            && raid.getStatus() == Raid.RaidStatus.ONGOING;
                })
                .map(Entity::getUniqueId)
                .distinct()
                .count();
        if (raiders < 3) return;
        Player player = Bukkit.getPlayer(attempt.playerId());
        if (player != null && tracking.test(player, BingoTask.RAID_BELL_REVEAL_THREE)) {
            completion.accept(player, BingoTask.RAID_BELL_REVEAL_THREE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowShot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getBow() == null
                || event.getBow().getType() != Material.CROSSBOW
                || event.getBow().getEnchantmentLevel(Enchantment.PIERCING) <= 0
                || !(event.getProjectile() instanceof AbstractArrow arrow)
                || arrow.getPierceLevel() <= 0
                || !tracking.test(player, BingoTask.PIERCING_ARROW_HIT_THREE)) {
            return;
        }

        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);
        putBounded(
                piercingShots,
                arrow.getUniqueId(),
                new PiercingShot(
                        player.getUniqueId(), tick, attemptToken(player.getUniqueId())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow)
                || !(event.getEntity() instanceof Mob mob)
                || event.getFinalDamage() <= 0.0) {
            return;
        }

        PiercingShot shot = piercingShots.get(arrow.getUniqueId());
        if (shot == null || !isCurrent(shot.playerId(), shot.token())) return;
        Player player = Bukkit.getPlayer(shot.playerId());
        if (player == null || !tracking.test(player, BingoTask.PIERCING_ARROW_HIT_THREE)) {
            piercingShots.remove(arrow.getUniqueId(), shot);
            return;
        }

        shot.hitMobs().add(mob.getUniqueId());
        if (shot.hitMobs().size() >= 3) {
            piercingShots.remove(arrow.getUniqueId(), shot);
            completion.accept(player, BingoTask.PIERCING_ARROW_HIT_THREE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        piercingShots.remove(entityId);
        if (event.getEntity() instanceof Shulker) shulkerHits.remove(entityId);
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        shulkerHits.values().removeIf(hit -> hit.playerId().equals(playerId));
        bellRings.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        piercingShots.values().removeIf(shot -> shot.playerId().equals(playerId));
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
        lastPruneTick = Integer.MIN_VALUE;
        shulkerHits.clear();
        bellRings.clear();
        piercingShots.clear();
    }

    private void pruneTransientStateIfDue(int tick) {
        if (lastPruneTick != Integer.MIN_VALUE) {
            int age = tick - lastPruneTick;
            if (age >= 0 && age < 20) return;
        }
        lastPruneTick = tick;
        shulkerHits.values().removeIf(hit ->
                !isFresh(hit.tick(), tick, SHULKER_DUPLICATION_CORRELATION_TICKS));
        bellRings.values().removeIf(attempt ->
                !isFresh(attempt.tick(), tick, BELL_RESONANCE_CORRELATION_TICKS));
        piercingShots.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > PROJECTILE_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
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

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record TimedAttempt(UUID playerId, int tick, AttemptToken token) {}

    private record ShulkerHit(UUID playerId, int tick, AttemptToken token) {}

    private record PiercingShot(
            UUID playerId, int tick, AttemptToken token, Set<UUID> hitMobs) {
        private PiercingShot(UUID playerId, int tick, AttemptToken token) {
            this(playerId, tick, token, new LinkedHashSet<>());
        }
    }
}
