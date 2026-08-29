package crabcraft.net.crabUtilities.bingo;

import com.destroystokyo.paper.event.entity.ThrownEggHatchEvent;
import io.papermc.paper.event.entity.EntityDamageItemEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the attribution-heavy challenge tasks in Bingo #5. */
public final class BingoCardFiveChallengeListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final long MAX_PERSISTENT_ATTRIBUTION_MILLIS =
            7L * 24 * 60 * 60 * 1_000;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final IntSupplier activeCardId;
    private final boolean allowCreativeObserverForTesting;
    private final NamespacedKey endermanOwnerKey;
    private final NamespacedKey endermanNamedAtKey;
    private final NamespacedKey endermanCardIdKey;
    private final NamespacedKey endCrystalOwnerKey;
    private final NamespacedKey endCrystalPlacedAtKey;
    private final NamespacedKey endCrystalCardIdKey;
    private final NamespacedKey eggOwnerKey;
    private final NamespacedKey eggThrownAtKey;
    private final NamespacedKey eggCardIdKey;
    private final Map<UUID, PillagerShotAttempt> pillagerShots = new HashMap<>();
    private final Map<UUID, PersistentMarker> thrownEggs = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;

    public BingoCardFiveChallengeListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId) {
        this(plugin, tracking, completion, activeCardId, false);
    }

    /**
     * The fifth argument is exclusively for the loopback test harness. Production must keep it
     * false so Pillager shots remain attributed to their actual player target.
     */
    public BingoCardFiveChallengeListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId,
            boolean allowCreativeObserverForTesting) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.activeCardId = activeCardId;
        this.allowCreativeObserverForTesting = allowCreativeObserverForTesting;
        this.endermanOwnerKey = new NamespacedKey(plugin, "bingo_card5_enderman_owner");
        this.endermanNamedAtKey = new NamespacedKey(plugin, "bingo_card5_enderman_named_at");
        this.endermanCardIdKey = new NamespacedKey(plugin, "bingo_card5_enderman_card");
        this.endCrystalOwnerKey = new NamespacedKey(plugin, "bingo_card5_crystal_owner");
        this.endCrystalPlacedAtKey = new NamespacedKey(plugin, "bingo_card5_crystal_placed_at");
        this.endCrystalCardIdKey = new NamespacedKey(plugin, "bingo_card5_crystal_card");
        this.eggOwnerKey = new NamespacedKey(plugin, "bingo_card5_egg_owner");
        this.eggThrownAtKey = new NamespacedKey(plugin, "bingo_card5_egg_thrown_at");
        this.eggCardIdKey = new NamespacedKey(plugin, "bingo_card5_egg_card");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEndermanNamed(PlayerNameEntityEvent event) {
        if (!(event.getEntity() instanceof Enderman enderman)) return;

        clearMarker(enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey);
        Player player = event.getPlayer();
        if (event.getName() != null
                && tracking.test(player, BingoTask.ENDERMAN_KILLED_BY_ENDERMITES_ONLY)) {
            int cardId = activeCardId.getAsInt();
            if (!isUsableCardId(cardId)) return;
            setMarker(
                    enderman,
                    endermanOwnerKey,
                    endermanNamedAtKey,
                    endermanCardIdKey,
                    new PersistentMarker(
                            player.getUniqueId(), System.currentTimeMillis(), cardId));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEndermanDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Enderman enderman)) return;

        PersistentMarker marker = markerFrom(
                enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey);
        if (marker == null) return;
        if (!markerIsCurrent(marker)
                || !ChallengePolicy.retainsEndermanMarker(
                        event.getFinalDamage(),
                        ChallengePolicy.isEndermiteMeleeDamage(event.getDamageSource()))) {
            clearMarker(enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        detectEndermanKilledByEndermites(event);
        detectEndCrystalHostileKill(event);
    }

    private void detectEndermanKilledByEndermites(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enderman enderman)
                || !ChallengePolicy.isEndermiteMeleeDamage(event.getDamageSource())) {
            return;
        }

        PersistentMarker marker = markerFrom(
                enderman, endermanOwnerKey, endermanNamedAtKey, endermanCardIdKey);
        if (marker == null || !markerIsCurrent(marker)) return;

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null
                && tracking.test(player, BingoTask.ENDERMAN_KILLED_BY_ENDERMITES_ONLY)) {
            completion.accept(player, BingoTask.ENDERMAN_KILLED_BY_ENDERMITES_ONLY);
        }
    }

    private void detectEndCrystalHostileKill(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)
                || !ChallengePolicy.isExplosionDamage(event.getDamageSource())) {
            return;
        }

        EnderCrystal crystal = exactEndCrystalSource(event.getDamageSource());
        if (crystal == null) return;

        PersistentMarker marker = markerFrom(
                crystal, endCrystalOwnerKey, endCrystalPlacedAtKey, endCrystalCardIdKey);
        if (marker == null || !markerIsCurrent(marker)) return;

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null
                && tracking.test(player, BingoTask.PLAYER_END_CRYSTAL_HOSTILE_KILL)) {
            completion.accept(player, BingoTask.PLAYER_END_CRYSTAL_HOSTILE_KILL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPillagerShot(EntityShootBowEvent event) {
        ItemStack bow = event.getBow();
        if (!(event.getEntity() instanceof Pillager pillager)
                || bow == null
                || bow.getType() != Material.CROSSBOW) {
            return;
        }

        Player player = pillager.getTarget() instanceof Player target
                ? target
                : creativeObserverForTesting(pillager);
        if (player == null || !tracking.test(player, BingoTask.DISARM_PILLAGER)) return;

        UUID pillagerId = pillager.getUniqueId();
        PillagerShotAttempt attempt = new PillagerShotAttempt(
                player.getUniqueId(),
                bow.clone(),
                Bukkit.getCurrentTick(),
                attemptToken(player.getUniqueId()));
        putBounded(pillagerShots, pillagerId, attempt);

        // A successful vanilla shot damages the Crossbow synchronously after this event.
        // Discard the correlation on the following tick if no matching damage arrived.
        Bukkit.getScheduler().runTask(plugin, () -> pillagerShots.remove(pillagerId, attempt));
    }

    private Player creativeObserverForTesting(Pillager pillager) {
        if (!allowCreativeObserverForTesting) return null;

        Player closest = null;
        double closestDistanceSquared = 32.0 * 32.0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getGameMode() != GameMode.CREATIVE
                    || !candidate.getWorld().equals(pillager.getWorld())
                    || !tracking.test(candidate, BingoTask.DISARM_PILLAGER)) {
                continue;
            }

            double distanceSquared = candidate.getLocation()
                    .distanceSquared(pillager.getLocation());
            if (distanceSquared <= closestDistanceSquared) {
                closest = candidate;
                closestDistanceSquared = distanceSquared;
            }
        }
        return closest;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPillagerCrossbowDamaged(EntityDamageItemEvent event) {
        ItemStack damagedItem = event.getItem();
        if (!(event.getEntity() instanceof Pillager pillager)
                || damagedItem.getType() != Material.CROSSBOW) {
            return;
        }

        PillagerShotAttempt attempt = pillagerShots.remove(pillager.getUniqueId());
        if (attempt == null
                || attempt.shotTick() != Bukkit.getCurrentTick()
                || !attempt.crossbow().isSimilar(damagedItem)
                || !isCurrent(attempt.playerId(), attempt.token())
                || !ChallengePolicy.damageBreaksItem(damagedItem, event.getDamage())) {
            return;
        }

        UUID pillagerId = pillager.getUniqueId();
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmPillagerDisarmed(pillagerId, attempt.playerId(), attempt.token()));
    }

    private void confirmPillagerDisarmed(
            UUID pillagerId, UUID playerId, AttemptToken token) {
        Entity entity = Bukkit.getEntity(pillagerId);
        Player player = Bukkit.getPlayer(playerId);
        if (entity instanceof Pillager pillager
                && pillager.isValid()
                && !pillager.isDead()
                && player != null
                && isCurrent(playerId, token)
                && ChallengePolicy.isUnarmed(
                        pillager.getEquipment().getItemInMainHand(),
                        pillager.getEquipment().getItemInOffHand())
                && tracking.test(player, BingoTask.DISARM_PILLAGER)) {
            completion.accept(player, BingoTask.DISARM_PILLAGER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggLaunched(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Egg egg)
                || !(egg.getShooter() instanceof Player player)
                || !tracking.test(player, BingoTask.HATCH_THROWN_CHICKEN)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        int cardId = activeCardId.getAsInt();
        if (!isUsableCardId(cardId)) return;

        PersistentMarker marker =
                new PersistentMarker(playerId, System.currentTimeMillis(), cardId);
        setMarker(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey, marker);
        putBounded(thrownEggs, egg.getUniqueId(), marker);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEggHatched(ThrownEggHatchEvent event) {
        Egg egg = event.getEgg();
        PersistentMarker marker = thrownEggs.remove(egg.getUniqueId());
        if (marker == null) {
            marker = markerFrom(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey);
        }
        clearMarker(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey);
        if (marker == null
                || !markerIsCurrent(marker)
                || !ChallengePolicy.isChickenHatch(
                        event.isHatching(), event.getNumHatches(), event.getHatchingType())) {
            return;
        }

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player == null || !tracking.test(player, BingoTask.HATCH_THROWN_CHICKEN)) return;
        completion.accept(player, BingoTask.HATCH_THROWN_CHICKEN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEndCrystalPlaced(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;

        clearMarker(crystal, endCrystalOwnerKey, endCrystalPlacedAtKey, endCrystalCardIdKey);
        Player player = event.getPlayer();
        if (player != null && tracking.test(player, BingoTask.PLAYER_END_CRYSTAL_HOSTILE_KILL)) {
            int cardId = activeCardId.getAsInt();
            if (!isUsableCardId(cardId)) return;
            setMarker(
                    crystal,
                    endCrystalOwnerKey,
                    endCrystalPlacedAtKey,
                    endCrystalCardIdKey,
                    new PersistentMarker(
                            player.getUniqueId(), System.currentTimeMillis(), cardId));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        pillagerShots.remove(entityId);
        thrownEggs.remove(entityId);
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
        pillagerShots.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        thrownEggs.entrySet().removeIf(entry -> {
            if (!entry.getValue().playerId().equals(playerId)) return false;
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Egg egg) {
                clearMarker(egg, eggOwnerKey, eggThrownAtKey, eggCardIdKey);
            }
            return true;
        });
    }

    @Override
    public void clear() {
        detectorGeneration++;
        pillagerShots.clear();
        thrownEggs.clear();
        playerGenerations.clear();
        playerResetAtMillis.clear();
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(
                detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration()
                        == playerGenerations.getOrDefault(playerId, 0L);
    }

    private PersistentMarker markerFrom(
            Entity entity,
            NamespacedKey ownerKey,
            NamespacedKey timestampKey,
            NamespacedKey cardIdKey) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        Long timestamp = data.get(timestampKey, PersistentDataType.LONG);
        Integer cardId = data.get(cardIdKey, PersistentDataType.INTEGER);
        if (owner == null || timestamp == null || cardId == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), timestamp, cardId);
        } catch (IllegalArgumentException ignored) {
            clearMarker(entity, ownerKey, timestampKey, cardIdKey);
            return null;
        }
    }

    private boolean markerIsCurrent(PersistentMarker marker) {
        long now = System.currentTimeMillis();
        long resetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L);
        return markerMatchesCard(marker.cardId(), activeCardId.getAsInt())
                && isFreshAttribution(
                        marker.timestamp(),
                        now,
                        resetAt,
                        MAX_PERSISTENT_ATTRIBUTION_MILLIS);
    }

    static boolean isFreshAttribution(
            long timestamp,
            long now,
            long playerResetAt,
            long maximumAge) {
        return timestamp > playerResetAt
                && timestamp <= now
                && now - timestamp <= maximumAge;
    }

    static boolean markerMatchesCard(int markerCardId, int currentCardId) {
        return isUsableCardId(currentCardId) && markerCardId == currentCardId;
    }

    private static boolean isUsableCardId(int cardId) {
        return cardId != Integer.MIN_VALUE;
    }

    private static void setMarker(
            Entity entity,
            NamespacedKey ownerKey,
            NamespacedKey timestampKey,
            NamespacedKey cardIdKey,
            PersistentMarker marker) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, marker.playerId().toString());
        data.set(timestampKey, PersistentDataType.LONG, marker.timestamp());
        data.set(cardIdKey, PersistentDataType.INTEGER, marker.cardId());
    }

    private static void clearMarker(
            Entity entity,
            NamespacedKey ownerKey,
            NamespacedKey timestampKey,
            NamespacedKey cardIdKey) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.remove(ownerKey);
        data.remove(timestampKey);
        data.remove(cardIdKey);
    }

    private static EnderCrystal exactEndCrystalSource(DamageSource source) {
        Entity direct = source.getDirectEntity();
        if (direct instanceof EnderCrystal crystal) return crystal;
        Entity causing = source.getCausingEntity();
        return causing instanceof EnderCrystal crystal ? crystal : null;
    }

    private static <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_TRANSIENT_ENTRIES) {
            map.remove(map.keySet().iterator().next());
        }
        map.put(key, value);
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PersistentMarker(UUID playerId, long timestamp, int cardId) {}

    private record PillagerShotAttempt(
            UUID playerId, ItemStack crossbow, int shotTick, AttemptToken token) {}

    static final class ChallengePolicy {
        private ChallengePolicy() {}

        static boolean isEndermiteMeleeDamage(DamageSource source) {
            DamageType type = source.getDamageType();
            if (!DamageType.MOB_ATTACK.equals(type)
                    && !DamageType.MOB_ATTACK_NO_AGGRO.equals(type)) {
                return false;
            }
            Entity causing = source.getCausingEntity();
            Entity direct = source.getDirectEntity();
            return causing instanceof Endermite || direct instanceof Endermite;
        }

        static boolean isExplosionDamage(DamageSource source) {
            DamageType type = source.getDamageType();
            return DamageType.EXPLOSION.equals(type)
                    || DamageType.PLAYER_EXPLOSION.equals(type);
        }

        static boolean retainsEndermanMarker(double finalDamage, boolean endermiteMeleeDamage) {
            return finalDamage <= 0.0 || endermiteMeleeDamage;
        }

        static boolean damageBreaksItem(ItemStack item, int appliedDamage) {
            if (!(item.getItemMeta() instanceof Damageable damageable)
                    || damageable.isUnbreakable()) {
                return false;
            }
            int maximumDamage = damageable.hasMaxDamage()
                    ? damageable.getMaxDamage()
                    : item.getType().getMaxDurability();
            return damageBreaksItem(
                    damageable.getDamage(), maximumDamage, appliedDamage);
        }

        static boolean damageBreaksItem(
                int currentDamage, int maximumDamage, int appliedDamage) {
            return currentDamage >= 0
                    && maximumDamage > 0
                    && appliedDamage > 0
                    && (long) currentDamage + appliedDamage >= maximumDamage;
        }

        static boolean isChickenHatch(
                boolean hatching, int numberOfHatches, EntityType hatchType) {
            return hatching && numberOfHatches > 0 && hatchType == EntityType.CHICKEN;
        }

        static boolean isUnarmed(ItemStack mainHand, ItemStack offHand) {
            return isEmpty(mainHand) && isEmpty(offHand);
        }

        private static boolean isEmpty(ItemStack item) {
            return item == null || item.isEmpty() || item.getType().isAir();
        }
    }
}
