package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zoglin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the mob-interaction tasks on Bingo #5. */
public final class BingoCardFiveMobListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final long MAX_DROPPED_ITEM_AGE_MILLIS = 10L * 60 * 1_000;
    private static final long MAX_PERSISTENT_ATTRIBUTION_MILLIS =
            7L * 24 * 60 * 60 * 1_000;
    private static final double HEALTH_EPSILON = 1.0e-7;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final IntSupplier activeCardId;
    private final NamespacedKey hoglinOwnerKey;
    private final NamespacedKey hoglinNamedAtKey;
    private final NamespacedKey hoglinCardIdKey;
    private final NamespacedKey helmetOwnerKey;
    private final NamespacedKey helmetDroppedAtKey;
    private final NamespacedKey helmetCardIdKey;
    private final NamespacedKey cakeOwnerKey;
    private final NamespacedKey cakeDroppedAtKey;
    private final NamespacedKey cakeCardIdKey;
    private final Map<UUID, DroppedHelmet> droppedHelmets = new LinkedHashMap<>();
    private final Map<UUID, DroppedCake> droppedCakes = new LinkedHashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;

    public BingoCardFiveMobListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.activeCardId = activeCardId;
        this.hoglinOwnerKey = new NamespacedKey(plugin, "bingo_card5_hoglin_owner");
        this.hoglinNamedAtKey = new NamespacedKey(plugin, "bingo_card5_hoglin_named_at");
        this.hoglinCardIdKey = new NamespacedKey(plugin, "bingo_card5_hoglin_card");
        this.helmetOwnerKey = new NamespacedKey(plugin, "bingo_card5_helmet_owner");
        this.helmetDroppedAtKey = new NamespacedKey(plugin, "bingo_card5_helmet_dropped_at");
        this.helmetCardIdKey = new NamespacedKey(plugin, "bingo_card5_helmet_card");
        this.cakeOwnerKey = new NamespacedKey(plugin, "bingo_card5_cake_owner");
        this.cakeDroppedAtKey = new NamespacedKey(plugin, "bingo_card5_cake_dropped_at");
        this.cakeCardIdKey = new NamespacedKey(plugin, "bingo_card5_cake_card");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHelmetDropped(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Player player = event.getPlayer();
        if (!isHeadgear(item)
                || !tracking.test(player, BingoTask.MOB_EQUIPS_DROPPED_HELMET)) {
            return;
        }

        long now = System.currentTimeMillis();
        int cardId = activeCardId.getAsInt();
        if (!isUsableCardId(cardId)) return;

        PersistentMarker marker = new PersistentMarker(player.getUniqueId(), now, cardId);
        setMarker(
                event.getItemDrop(),
                helmetOwnerKey,
                helmetDroppedAtKey,
                helmetCardIdKey,
                marker);
        pruneDroppedHelmets(now);
        putBounded(
                droppedHelmets,
                event.getItemDrop().getUniqueId(),
                new DroppedHelmet(marker, item.asOne()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCakeDropped(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Player player = event.getPlayer();
        if (!isEligibleDroppedPandaCake(
                        item.getType(),
                        Tag.ITEMS_PANDA_EATS_FROM_GROUND.isTagged(item.getType()))
                || !tracking.test(player, BingoTask.FEED_PANDA_CAKE)) {
            return;
        }

        long now = System.currentTimeMillis();
        int cardId = activeCardId.getAsInt();
        if (!isUsableCardId(cardId)) return;

        PersistentMarker marker = new PersistentMarker(player.getUniqueId(), now, cardId);
        setMarker(
                event.getItemDrop(),
                cakeOwnerKey,
                cakeDroppedAtKey,
                cakeCardIdKey,
                marker);
        pruneDroppedCakes(now);
        putBounded(
                droppedCakes,
                event.getItemDrop().getUniqueId(),
                new DroppedCake(marker));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickedUp(EntityPickupItemEvent event) {
        UUID itemId = event.getItem().getUniqueId();
        DroppedCake cake = droppedCakes.remove(itemId);
        if (cake == null) {
            PersistentMarker marker = markerFrom(
                    event.getItem(), cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey);
            if (marker != null) cake = new DroppedCake(marker);
        }
        clearMarker(event.getItem(), cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey);
        if (cake != null && event.getEntity() instanceof Panda panda) {
            confirmPandaAcceptedCake(panda, cake);
        }

        DroppedHelmet dropped = droppedHelmets.remove(itemId);
        if (dropped == null) {
            PersistentMarker marker = markerFrom(
                    event.getItem(), helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey);
            if (marker != null) {
                dropped = new DroppedHelmet(marker, event.getItem().getItemStack().asOne());
            }
        }
        clearMarker(event.getItem(), helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey);
        if (dropped == null || !(event.getEntity() instanceof Enemy enemy)) return;

        EntityEquipment equipment = enemy.getEquipment();
        ItemStack previousHelmet = equipment.getItem(EquipmentSlot.HEAD).clone();
        UUID enemyId = enemy.getUniqueId();
        DroppedHelmet acceptedDrop = dropped;
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmHelmetEquipped(enemyId, previousHelmet, acceptedDrop));
    }

    private void confirmHelmetEquipped(
            UUID enemyId, ItemStack previousHelmet, DroppedHelmet dropped) {
        Entity entity = Bukkit.getEntity(enemyId);
        Player player = Bukkit.getPlayer(dropped.marker().playerId());
        if (!(entity instanceof Enemy enemy)
                || !enemy.isValid()
                || enemy.isDead()
                || player == null
                || !markerIsCurrent(
                        dropped.marker(),
                        System.currentTimeMillis(),
                        MAX_DROPPED_ITEM_AGE_MILLIS)
                || !tracking.test(player, BingoTask.MOB_EQUIPS_DROPPED_HELMET)) {
            return;
        }

        ItemStack equipped = enemy.getEquipment().getItem(EquipmentSlot.HEAD);
        if (equipped.isSimilar(dropped.item())
                && !previousHelmet.isSimilar(equipped)) {
            completion.accept(player, BingoTask.MOB_EQUIPS_DROPPED_HELMET);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobInteracted(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof IronGolem golem) {
            beginGolemRepairAttempt(event, golem);
        }
    }

    private void confirmPandaAcceptedCake(Panda panda, DroppedCake cake) {
        Player player = Bukkit.getPlayer(cake.marker().playerId());
        if (player != null
                && panda.isValid()
                && !panda.isDead()
                && markerIsCurrent(
                        cake.marker(),
                        System.currentTimeMillis(),
                        MAX_DROPPED_ITEM_AGE_MILLIS)
                && tracking.test(player, BingoTask.FEED_PANDA_CAKE)) {
            completion.accept(player, BingoTask.FEED_PANDA_CAKE);
        }
    }

    private void beginGolemRepairAttempt(PlayerInteractEntityEvent event, IronGolem golem) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getHand());
        AttributeInstance maxHealth = golem.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null
                || !canStartGolemRepair(
                        item == null ? null : item.getType(),
                        golem.getHealth(),
                        maxHealth.getValue())
                || !tracking.test(player, BingoTask.REPAIR_IRON_GOLEM)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID golemId = golem.getUniqueId();
        double previousHealth = golem.getHealth();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmGolemRepaired(playerId, golemId, previousHealth, token));
    }

    private void confirmGolemRepaired(
            UUID playerId, UUID golemId, double previousHealth, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(golemId);
        if (player != null
                && entity instanceof IronGolem golem
                && golem.isValid()
                && !golem.isDead()
                && healthIncreased(previousHealth, golem.getHealth())
                && isCurrent(playerId, token)
                && tracking.test(player, BingoTask.REPAIR_IRON_GOLEM)) {
            completion.accept(player, BingoTask.REPAIR_IRON_GOLEM);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHoglinNamed(PlayerNameEntityEvent event) {
        if (!(event.getEntity() instanceof Hoglin hoglin) || event.getName() == null) return;

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.NAME_HOGLIN_ZOGLIN)) return;

        int cardId = activeCardId.getAsInt();
        if (!isUsableCardId(cardId)) return;

        setMarker(
                hoglin,
                hoglinOwnerKey,
                hoglinNamedAtKey,
                hoglinCardIdKey,
                new PersistentMarker(
                        player.getUniqueId(), System.currentTimeMillis(), cardId));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHoglinTransformed(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.PIGLIN_ZOMBIFIED
                || !(event.getEntity() instanceof Hoglin hoglin)
                || !(event.getTransformedEntity() instanceof Zoglin)) {
            return;
        }

        PersistentMarker marker = markerFrom(
                hoglin, hoglinOwnerKey, hoglinNamedAtKey, hoglinCardIdKey);
        if (marker == null
                || !markerIsCurrent(
                        marker,
                        System.currentTimeMillis(),
                        MAX_PERSISTENT_ATTRIBUTION_MILLIS)) {
            return;
        }
        clearHoglinMarker(hoglin);

        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && tracking.test(player, BingoTask.NAME_HOGLIN_ZOGLIN)) {
            completion.accept(player, BingoTask.NAME_HOGLIN_ZOGLIN);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerged(ItemMergeEvent event) {
        // A merge makes it impossible to prove which physical item was equipped.
        droppedHelmets.remove(event.getEntity().getUniqueId());
        droppedHelmets.remove(event.getTarget().getUniqueId());
        droppedCakes.remove(event.getEntity().getUniqueId());
        droppedCakes.remove(event.getTarget().getUniqueId());
        clearDroppedItemMarkers(event.getEntity());
        clearDroppedItemMarkers(event.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickedUp(InventoryPickupItemEvent event) {
        droppedHelmets.remove(event.getItem().getUniqueId());
        droppedCakes.remove(event.getItem().getUniqueId());
        clearDroppedItemMarkers(event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        droppedHelmets.remove(event.getEntity().getUniqueId());
        droppedCakes.remove(event.getEntity().getUniqueId());
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
        droppedHelmets.entrySet().removeIf(entry -> {
            if (!entry.getValue().marker().playerId().equals(playerId)) return false;
            clearDroppedItemMarkers(Bukkit.getEntity(entry.getKey()));
            return true;
        });
        droppedCakes.entrySet().removeIf(entry -> {
            if (!entry.getValue().marker().playerId().equals(playerId)) return false;
            clearDroppedItemMarkers(Bukkit.getEntity(entry.getKey()));
            return true;
        });
    }

    @Override
    public void clear() {
        detectorGeneration++;
        droppedHelmets.clear();
        droppedCakes.clear();
        playerGenerations.clear();
        playerResetAtMillis.clear();
    }

    static boolean isHeadgear(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.HEAD;
    }

    static boolean isEligibleDroppedPandaCake(Material itemType, boolean pandaGroundFood) {
        return itemType == Material.CAKE && pandaGroundFood;
    }

    static boolean canStartGolemRepair(
            Material itemType, double currentHealth, double maximumHealth) {
        return itemType == Material.IRON_INGOT
                && currentHealth + HEALTH_EPSILON < maximumHealth;
    }

    static boolean healthIncreased(double previousHealth, double currentHealth) {
        return currentHealth > previousHealth + HEALTH_EPSILON;
    }

    private void clearHoglinMarker(Hoglin hoglin) {
        clearMarker(hoglin, hoglinOwnerKey, hoglinNamedAtKey, hoglinCardIdKey);
    }

    private void clearDroppedItemMarkers(Entity entity) {
        if (entity == null) return;
        clearMarker(entity, helmetOwnerKey, helmetDroppedAtKey, helmetCardIdKey);
        clearMarker(entity, cakeOwnerKey, cakeDroppedAtKey, cakeCardIdKey);
    }

    private boolean markerIsCurrent(PersistentMarker marker, long now, long maximumAge) {
        long resetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L);
        return markerMatchesCard(marker.cardId(), activeCardId.getAsInt())
                && isFreshAttribution(
                        marker.timestamp(),
                        now,
                        resetAt,
                        maximumAge);
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

    private void pruneDroppedHelmets(long now) {
        droppedHelmets.values().removeIf(drop ->
                !markerIsCurrent(drop.marker(), now, MAX_DROPPED_ITEM_AGE_MILLIS));
    }

    private void pruneDroppedCakes(long now) {
        droppedCakes.values().removeIf(drop ->
                !markerIsCurrent(drop.marker(), now, MAX_DROPPED_ITEM_AGE_MILLIS));
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

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
    }

    private static <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_TRANSIENT_ENTRIES) {
            Iterator<K> oldest = map.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        map.put(key, value);
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record DroppedHelmet(PersistentMarker marker, ItemStack item) {}

    private record DroppedCake(PersistentMarker marker) {}

    private record PersistentMarker(UUID playerId, long timestamp, int cardId) {}
}
