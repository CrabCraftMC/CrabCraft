package crabcraft.net.crabUtilities.bingo;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.LlamaInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Mob interaction and exact-entity attribution detectors for Bingo #6. */
public final class BingoCardSixMobListener implements BingoDetector {
    private static final long MAX_PERSISTENT_ATTRIBUTION_MILLIS =
            7L * 24 * 60 * 60 * 1_000;
    private static final int MAX_STARTUP_DEFERRAL_ATTEMPTS = 300;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final IntSupplier activeCardId;
    private final NamespacedKey mendingOwnerKey;
    private final NamespacedKey mendingDroppedAtKey;
    private final NamespacedKey mendingCardIdKey;
    private final NamespacedKey poisonOwnerKey;
    private final NamespacedKey poisonThrownAtKey;
    private final NamespacedKey poisonCardIdKey;
    private final NamespacedKey markerPlayerRunKey;
    private final NamespacedKey playerRunKey;
    private final Map<UUID, PendingLavaDestruction> pendingLavaDestructions = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;

    public BingoCardSixMobListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.activeCardId = activeCardId;
        this.mendingOwnerKey = new NamespacedKey(plugin, "bingo6_mending_owner");
        this.mendingDroppedAtKey = new NamespacedKey(plugin, "bingo6_mending_dropped");
        this.mendingCardIdKey = new NamespacedKey(plugin, "bingo6_mending_card");
        this.poisonOwnerKey = new NamespacedKey(plugin, "bingo6_poison_owner");
        this.poisonThrownAtKey = new NamespacedKey(plugin, "bingo6_poison_thrown");
        this.poisonCardIdKey = new NamespacedKey(plugin, "bingo6_poison_card");
        this.markerPlayerRunKey = new NamespacedKey(plugin, "bingo6_marker_run");
        this.playerRunKey = new NamespacedKey(plugin, "bingo6_mob_run");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLlamaInteracted(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Llama llama)) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(event.getHand());
        if (!isCarpet(held)
                || isCarpet(llama.getInventory().getDecor())
                || !tracking.test(player, BingoTask.CARPET_LLAMA)) {
            return;
        }
        scheduleLlamaCarpetCheck(player, llama);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLlamaInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory() instanceof LlamaInventory inventory)
                || !(inventory.getHolder() instanceof Llama llama)
                || isCarpet(inventory.getDecor())
                || !clickCanDecorateLlama(event, player, inventory.getSize())
                || !tracking.test(player, BingoTask.CARPET_LLAMA)) {
            return;
        }
        scheduleLlamaCarpetCheck(player, llama);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLlamaInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory() instanceof LlamaInventory inventory)
                || !(inventory.getHolder() instanceof Llama llama)
                || isCarpet(inventory.getDecor())
                || !isCarpet(event.getOldCursor())
                || !event.getRawSlots().contains(1)
                || !tracking.test(player, BingoTask.CARPET_LLAMA)) {
            return;
        }
        scheduleLlamaCarpetCheck(player, llama);
    }

    private void scheduleLlamaCarpetCheck(Player player, Llama llama) {
        UUID playerId = player.getUniqueId();
        UUID llamaId = llama.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player currentPlayer = Bukkit.getPlayer(playerId);
            Entity currentEntity = Bukkit.getEntity(llamaId);
            if (currentPlayer != null
                    && currentEntity instanceof Llama currentLlama
                    && currentLlama.isValid()
                    && !currentLlama.isDead()
                    && isCurrent(playerId, token)
                    && tracking.test(currentPlayer, BingoTask.CARPET_LLAMA)
                    && isCarpet(currentLlama.getInventory().getDecor())) {
                completion.accept(currentPlayer, BingoTask.CARPET_LLAMA);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMendingBookDropped(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItemDrop();
        int cardId = activeCardId.getAsInt();
        clearMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        if (cardId == Integer.MIN_VALUE
                || !isMendingBook(item.getItemStack())
                || !tracking.test(player, BingoTask.THROW_MENDING_BOOK_IN_LAVA)) {
            return;
        }
        setMarker(
                item,
                mendingOwnerKey,
                mendingDroppedAtKey,
                mendingCardIdKey,
                new PersistentMarker(
                        player.getUniqueId(),
                        System.currentTimeMillis(),
                        cardId,
                        playerRun(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDroppedItemDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)
                || event.getCause() != EntityDamageEvent.DamageCause.LAVA
                || !isMendingBook(item.getItemStack())
                || !itemDamageIsLethal(
                        item.getHealth(),
                        event.getOriginalDamage(EntityDamageEvent.DamageModifier.BASE),
                        event.getDamage())) {
            return;
        }

        PersistentMarker marker = currentMarker(
                item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        if (marker == null) return;

        UUID itemId = item.getUniqueId();
        PendingLavaDestruction pending = new PendingLavaDestruction(
                marker, attemptToken(marker.playerId()));
        pendingLavaDestructions.put(itemId, pending);
        Bukkit.getScheduler().runTask(
                plugin, () -> pendingLavaDestructions.remove(itemId, pending));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMarkedItemMergeConflict(ItemMergeEvent event) {
        Item source = event.getEntity();
        Item target = event.getTarget();
        PersistentMarker sourceMarker = currentMarker(
                source, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        if (sourceMarker == null) return;

        PersistentMarker targetMarker = currentMarker(
                target, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        if (targetMarker == null) return;

        if (!sourceMarker.playerId().equals(targetMarker.playerId())
                || sourceMarker.cardId() != targetMarker.cardId()
                || sourceMarker.playerRun() != targetMarker.playerRun()) {
            // One merged entity cannot preserve exact ownership for both books.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMarkedItemMerged(ItemMergeEvent event) {
        Item source = event.getEntity();
        Item target = event.getTarget();
        PersistentMarker sourceMarker = currentMarker(
                source, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        if (sourceMarker == null) return;

        PersistentMarker targetMarker = currentMarker(
                target, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        if (targetMarker == null || sourceMarker.timestamp() > targetMarker.timestamp()) {
            setMarker(
                    target,
                    mendingOwnerKey,
                    mendingDroppedAtKey,
                    mendingCardIdKey,
                    sourceMarker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickedUpItem(EntityPickupItemEvent event) {
        forgetMendingItem(event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickedUpItem(InventoryPickupItemEvent event) {
        forgetMendingItem(event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        PendingLavaDestruction pending = pendingLavaDestructions.remove(
                event.getEntity().getUniqueId());
        if (pending == null
                || event.getCause() != EntityRemoveEvent.Cause.DEATH
                || !(event.getEntity() instanceof Item item)
                || !pending.marker().equals(markerFrom(
                        item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey))
                || !isCurrent(pending.marker().playerId(), pending.token())) {
            return;
        }

        clearMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
        completePersistentOrDefer(
                pending.marker(), BingoTask.THROW_MENDING_BOOK_IN_LAVA, 0);
    }

    private void forgetMendingItem(Item item) {
        pendingLavaDestructions.remove(item.getUniqueId());
        clearMarker(item, mendingOwnerKey, mendingDroppedAtKey, mendingCardIdKey);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRavagerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Ravager ravager)
                || !(event.getEntity() instanceof Player player)
                || !canStartRavagerStun(
                        player.isBlocking(), activelyBlocksWithShield(player), ravager.getStunnedTicks())
                || !tracking.test(player, BingoTask.STUN_RAVAGER)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID ravagerId = ravager.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player currentPlayer = Bukkit.getPlayer(playerId);
            Entity currentEntity = Bukkit.getEntity(ravagerId);
            if (currentPlayer != null
                    && currentEntity instanceof Ravager currentRavager
                    && currentRavager.isValid()
                    && !currentRavager.isDead()
                    && isCurrent(playerId, token)
                    && tracking.test(currentPlayer, BingoTask.STUN_RAVAGER)
                    && becameStunned(0, currentRavager.getStunnedTicks())) {
                completion.accept(currentPlayer, BingoTask.STUN_RAVAGER);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLingeringPotionSplash(LingeringPotionSplashEvent event) {
        ThrownPotion potion = event.getEntity();
        if (!(potion.getShooter() instanceof Player player)
                || !hasPoison(potion.getEffects())
                || !tracking.test(player, BingoTask.POISON_BEE)) {
            return;
        }
        int cardId = activeCardId.getAsInt();
        if (cardId == Integer.MIN_VALUE) return;
        setMarker(
                event.getAreaEffectCloud(),
                poisonOwnerKey,
                poisonThrownAtKey,
                poisonCardIdKey,
                new PersistentMarker(
                        player.getUniqueId(),
                        System.currentTimeMillis(),
                        cardId,
                        playerRun(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeePotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Bee)
                || !PotionEffectType.POISON.equals(event.getModifiedType())
                || event.getNewEffect() == null
                || !poisonApplicationAccepted(event.getAction(), event.isOverride())) {
            return;
        }

        if (event.getCause() == EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD) {
            PersistentMarker marker = lingeringPotionMarker(event.getSource());
            if (marker != null) {
                completePersistentOrDefer(marker, BingoTask.POISON_BEE, 0);
            }
            return;
        }

        Player player = event.getCause() == EntityPotionEffectEvent.Cause.POTION_SPLASH
                ? splashPotionOwner(event.getSource())
                : null;
        if (player != null && tracking.test(player, BingoTask.POISON_BEE)) {
            completion.accept(player, BingoTask.POISON_BEE);
        }
    }

    private Player splashPotionOwner(Entity source) {
        Player directPlayer = source instanceof Player player ? player : null;
        Player projectilePlayer = source instanceof ThrownPotion potion
                        && potion.getShooter() instanceof Player player
                ? player : null;
        if (!splashSourceCanResolvePlayer(
                directPlayer != null, projectilePlayer != null)) return null;
        return directPlayer != null ? directPlayer : projectilePlayer;
    }

    private PersistentMarker lingeringPotionMarker(Entity source) {
        if (!(source instanceof AreaEffectCloud cloud)) return null;
        return currentMarker(cloud, poisonOwnerKey, poisonThrownAtKey, poisonCardIdKey);
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            PersistentDataContainer data = player.getPersistentDataContainer();
            long nextRun = data.getOrDefault(
                            playerRunKey, PersistentDataType.LONG, 0L)
                    + 1L;
            data.set(playerRunKey, PersistentDataType.LONG, nextRun);
        }
        pendingLavaDestructions.entrySet().removeIf(
                entry -> entry.getValue().marker().playerId().equals(playerId));
    }

    @Override
    public void clear() {
        detectorGeneration++;
        pendingLavaDestructions.clear();
        playerGenerations.clear();
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(
                detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return attemptIsCurrent(
                detectorGeneration,
                playerGenerations.getOrDefault(playerId, 0L),
                token.detectorGeneration(),
                token.playerGeneration());
    }

    private PersistentMarker currentMarker(
            Entity entity, NamespacedKey ownerKey, NamespacedKey timeKey, NamespacedKey cardKey) {
        PersistentMarker marker = markerFrom(entity, ownerKey, timeKey, cardKey);
        if (marker == null) {
            clearMarker(entity, ownerKey, timeKey, cardKey);
            return null;
        }
        long now = System.currentTimeMillis();
        if (!timestampIsCurrent(
                marker.timestamp(), now, MAX_PERSISTENT_ATTRIBUTION_MILLIS)) {
            clearMarker(entity, ownerKey, timeKey, cardKey);
            return null;
        }
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && marker.playerRun() != playerRun(player)) {
            clearMarker(entity, ownerKey, timeKey, cardKey);
            return null;
        }
        int cardId = activeCardId.getAsInt();
        if (cardId != Integer.MIN_VALUE && marker.cardId() != cardId) {
            clearMarker(entity, ownerKey, timeKey, cardKey);
            return null;
        }
        return marker;
    }

    private boolean markerIsCurrent(PersistentMarker marker) {
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player == null) return false;
        return markerIsCurrent(
                marker.cardId(),
                activeCardId.getAsInt(),
                marker.timestamp(),
                System.currentTimeMillis(),
                marker.playerRun(),
                playerRun(player),
                MAX_PERSISTENT_ATTRIBUTION_MILLIS);
    }

    private boolean markerOwnerStateIsCurrent(PersistentMarker marker) {
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player == null) return false;
        return persistentOwnerStateIsCurrent(
                marker.timestamp(),
                System.currentTimeMillis(),
                marker.playerRun(),
                playerRun(player),
                MAX_PERSISTENT_ATTRIBUTION_MILLIS);
    }

    private long playerRun(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(playerRunKey, PersistentDataType.LONG, 0L);
    }

    private void completePersistentOrDefer(
            PersistentMarker marker, BingoTask task, int attempts) {
        int cardId = activeCardId.getAsInt();
        Player player = Bukkit.getPlayer(marker.playerId());
        boolean timestampCurrent = timestampIsCurrent(
                marker.timestamp(),
                System.currentTimeMillis(),
                MAX_PERSISTENT_ATTRIBUTION_MILLIS);
        if (shouldDeferPersistentCompletion(
                marker.cardId(),
                cardId,
                player != null,
                timestampCurrent,
                attempts,
                MAX_STARTUP_DEFERRAL_ATTEMPTS)) {
                Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> completePersistentOrDefer(marker, task, attempts + 1),
                        20L);
            return;
        }
        if (cardId == Integer.MIN_VALUE || player == null) return;

        if (markerIsCurrent(marker)
                && tracking.test(player, task)) {
            completion.accept(player, task);
        }
    }

    private PersistentMarker markerFrom(
            Entity entity, NamespacedKey ownerKey, NamespacedKey timeKey, NamespacedKey cardKey) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        Long timestamp = data.get(timeKey, PersistentDataType.LONG);
        Integer cardId = data.get(cardKey, PersistentDataType.INTEGER);
        Long playerRun = data.get(markerPlayerRunKey, PersistentDataType.LONG);
        if (owner == null || timestamp == null || cardId == null || playerRun == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), timestamp, cardId, playerRun);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void setMarker(
            Entity entity,
            NamespacedKey ownerKey,
            NamespacedKey timeKey,
            NamespacedKey cardKey,
            PersistentMarker marker) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, marker.playerId().toString());
        data.set(timeKey, PersistentDataType.LONG, marker.timestamp());
        data.set(cardKey, PersistentDataType.INTEGER, marker.cardId());
        data.set(markerPlayerRunKey, PersistentDataType.LONG, marker.playerRun());
    }

    private void clearMarker(
            Entity entity, NamespacedKey ownerKey, NamespacedKey timeKey, NamespacedKey cardKey) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.remove(ownerKey);
        data.remove(timeKey);
        data.remove(cardKey);
        data.remove(markerPlayerRunKey);
    }

    static boolean isCarpet(ItemStack item) {
        return item != null
                && !item.isEmpty()
                && Tag.ITEMS_WOOL_CARPETS.isTagged(item.getType());
    }

    static boolean clickCanDecorateLlama(
            InventoryClickEvent event, Player player, int topInventorySize) {
        int hotbarButton = event.getHotbarButton();
        return clickCanDecorateLlama(
                event.getRawSlot(),
                topInventorySize,
                event.getAction(),
                isCarpet(event.getCursor()),
                isCarpet(event.getCurrentItem()),
                event.getClick() == ClickType.SWAP_OFFHAND
                        && isCarpet(player.getInventory().getItemInOffHand()),
                hotbarButton >= 0
                        && isCarpet(player.getInventory().getItem(hotbarButton)));
    }

    static boolean clickCanDecorateLlama(
            int rawSlot,
            int topInventorySize,
            InventoryAction action,
            boolean cursorCarpet,
            boolean currentCarpet,
            boolean offhandSwapWithCarpet,
            boolean hotbarSwapWithCarpet) {
        if (rawSlot == 1) {
            return cursorCarpet || offhandSwapWithCarpet || hotbarSwapWithCarpet;
        }
        return rawSlot >= topInventorySize
                && action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && currentCarpet;
    }

    static boolean isMendingBook(ItemStack item) {
        return item != null
                && item.getItemMeta() instanceof EnchantmentStorageMeta meta
                && isMendingBook(item.getType(), meta.hasStoredEnchant(Enchantment.MENDING));
    }

    static boolean isMendingBook(Material material, boolean hasStoredMending) {
        return material == Material.ENCHANTED_BOOK && hasStoredMending;
    }

    static boolean itemDamageIsLethal(
            int health, double originalDamage, double eventDamage) {
        return health > 0
                && Double.isFinite(originalDamage)
                && originalDamage > 0.0
                && Double.isFinite(eventDamage)
                && eventDamage != 0.0
                && (int) ((float) health - (float) originalDamage) <= 0;
    }

    static boolean canStartRavagerStun(
            boolean playerBlocking, boolean playerHoldsShield, int stunnedTicks) {
        return playerBlocking && playerHoldsShield && stunnedTicks <= 0;
    }

    static boolean becameStunned(int previousTicks, int currentTicks) {
        return previousTicks <= 0 && currentTicks > 0;
    }

    static boolean hasPoison(Collection<PotionEffect> effects) {
        return effects.stream().anyMatch(
                effect -> PotionEffectType.POISON.equals(effect.getType()));
    }

    static boolean splashSourceCanResolvePlayer(
            boolean sourceIsPlayer, boolean thrownPotionHasPlayerShooter) {
        return sourceIsPlayer || thrownPotionHasPlayerShooter;
    }

    static boolean poisonApplicationAccepted(
            EntityPotionEffectEvent.Action action, boolean overridesExistingEffect) {
        return action == EntityPotionEffectEvent.Action.ADDED
                || (action == EntityPotionEffectEvent.Action.CHANGED
                        && overridesExistingEffect);
    }

    static boolean markerIsCurrent(
            int markerCardId,
            int activeCardId,
            long timestamp,
            long now,
            long markerPlayerRun,
            long currentPlayerRun,
            long maximumAge) {
        return activeCardId != Integer.MIN_VALUE
                && markerCardId == activeCardId
                && persistentOwnerStateIsCurrent(
                        timestamp,
                        now,
                        markerPlayerRun,
                        currentPlayerRun,
                        maximumAge);
    }

    static boolean persistentOwnerStateIsCurrent(
            long timestamp,
            long now,
            long markerPlayerRun,
            long currentPlayerRun,
            long maximumAge) {
        return markerPlayerRun == currentPlayerRun
                && timestampIsCurrent(timestamp, now, maximumAge);
    }

    static boolean timestampIsCurrent(long timestamp, long now, long maximumAge) {
        return timestamp <= now && now - timestamp <= maximumAge;
    }

    static boolean shouldDeferPersistentCompletion(
            int markerCardId,
            int activeCardId,
            boolean ownerOnline,
            boolean timestampCurrent,
            int attempts,
            int maximumAttempts) {
        return (activeCardId == Integer.MIN_VALUE
                        || (!ownerOnline && markerCardId == activeCardId))
                && timestampCurrent
                && attempts < maximumAttempts;
    }

    static boolean attemptIsCurrent(
            long detectorGeneration,
            long playerGeneration,
            long tokenDetectorGeneration,
            long tokenPlayerGeneration) {
        return detectorGeneration == tokenDetectorGeneration
                && playerGeneration == tokenPlayerGeneration;
    }

    private static boolean activelyBlocksWithShield(Player player) {
        return player.getActiveItem().getType() == Material.SHIELD;
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PersistentMarker(
            UUID playerId, long timestamp, int cardId, long playerRun) {}

    private record PendingLavaDestruction(PersistentMarker marker, AttemptToken token) {}
}
