package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.inventory.ItemCraftedEvent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Inventory, crafting and per-player progress detectors for Bingo #6. */
public final class BingoCardSixMechanicsListener implements BingoDetector {
    private static final int FISH_TREASURE = 1;
    private static final int FISH_JUNK = 2;
    private static final int ALL_FISH_CATEGORIES = FISH_TREASURE | FISH_JUNK;
    private static final Set<Material> TREASURE_ITEMS = EnumSet.of(
            Material.BOW,
            Material.ENCHANTED_BOOK,
            Material.NAME_TAG,
            Material.NAUTILUS_SHELL,
            Material.SADDLE);
    private static final Set<Material> JUNK_ITEMS = EnumSet.of(
            Material.LILY_PAD,
            Material.BOWL,
            Material.LEATHER,
            Material.LEATHER_BOOTS,
            Material.ROTTEN_FLESH,
            Material.STICK,
            Material.STRING,
            Material.POTION,
            Material.BONE,
            Material.INK_SAC,
            Material.TRIPWIRE_HOOK,
            Material.BAMBOO);

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final IntSupplier activeCardId;
    private final NamespacedKey progressCardKey;
    private final NamespacedKey fishFlagsKey;
    private final NamespacedKey enchantedItemsKey;
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, PendingResult> pendingPotCrafts = new HashMap<>();
    private final Map<UUID, PendingResult> pendingSmithingDrops = new HashMap<>();
    private long detectorGeneration;

    public BingoCardSixMechanicsListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.activeCardId = activeCardId;
        this.progressCardKey = new NamespacedKey(plugin, "bingo6_progress_card");
        this.fishFlagsKey = new NamespacedKey(plugin, "bingo6_fish_flags");
        this.enchantedItemsKey = new NamespacedKey(plugin, "bingo6_enchanted_items");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishCaught(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !(event.getCaught() instanceof Item item)
                || !tracking.test(player, BingoTask.FISH_TREASURE_AND_JUNK)) {
            return;
        }

        int category = fishingCategory(item.getItemStack());
        if (category == 0) return;

        PersistentDataContainer data = cardProgress(player);
        int flags = data.getOrDefault(fishFlagsKey, PersistentDataType.INTEGER, 0) | category;
        data.set(fishFlagsKey, PersistentDataType.INTEGER, flags);
        if (flags == ALL_FISH_CATEGORIES) {
            completion.accept(player, BingoTask.FISH_TREASURE_AND_JUNK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemEnchanted(EnchantItemEvent event) {
        Player player = event.getEnchanter();
        if (event.getEnchantsToAdd().isEmpty()
                || !tracking.test(player, BingoTask.ENCHANT_FIVE_ITEMS)) {
            return;
        }

        PersistentDataContainer data = cardProgress(player);
        Set<Material> materials = decodeMaterials(
                data.getOrDefault(enchantedItemsKey, PersistentDataType.STRING, ""));
        materials.add(event.getItem().getType());
        data.set(enchantedItemsKey, PersistentDataType.STRING, encodeMaterials(materials));
        if (materials.size() >= 5) {
            completion.accept(player, BingoTask.ENCHANT_FIVE_ITEMS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnderChestClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getView().getTopInventory().getType() != InventoryType.ENDER_CHEST
                || !canChangeInventory(event.getAction())
                || !tracking.test(player, BingoTask.FILL_ENDER_CHEST)
                || occupiedSlots(event.getView().getTopInventory()) >= 27) {
            return;
        }
        scheduleEnderChestCheck(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnderChestDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getView().getTopInventory().getType() != InventoryType.ENDER_CHEST
                || event.getRawSlots().stream().noneMatch(slot -> slot < 27)
                || !tracking.test(player, BingoTask.FILL_ENDER_CHEST)
                || occupiedSlots(event.getView().getTopInventory()) >= 27) {
            return;
        }
        scheduleEnderChestCheck(player);
    }

    private void scheduleEnderChestCheck(Player player) {
        UUID playerId = player.getUniqueId();
        AttemptToken token = tokenFor(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current != null
                    && isCurrent(playerId, token)
                    && tracking.test(current, BingoTask.FILL_ENDER_CHEST)
                    && occupiedSlots(current.getEnderChest()) == 27) {
                completion.accept(current, BingoTask.FILL_ENDER_CHEST);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithingResultTaken(SmithItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getInventory()
                || event.getSlotType() != InventoryType.SlotType.RESULT
                || !isResultTakeAction(event.getAction())
                || !tracking.test(player, BingoTask.APPLY_ARMOUR_TRIM)) {
            return;
        }

        SmithingInventory inventory = event.getInventory();
        ItemStack before = inventory.getInputEquipment();
        ItemStack result = event.getCurrentItem();
        if (!appliesNewTrim(before, result)) return;

        ItemStack expectedResult = result.clone();
        StackSnapshot[] inputs = smithingInputs(inventory);
        UUID playerId = player.getUniqueId();
        AttemptToken token = tokenFor(playerId);
        if (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            PendingResult pending = new PendingResult(expectedResult, token);
            pendingSmithingDrops.put(playerId, pending);
            Bukkit.getScheduler().runTask(
                    plugin, () -> pendingSmithingDrops.remove(playerId, pending));
        }
        if (isDropResultAction(event.getAction())) {
            return;
        }

        int resultCountBefore = matchingItemCount(player, expectedResult);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current != null
                    && isCurrent(playerId, token)
                    && tracking.test(current, BingoTask.APPLY_ARMOUR_TRIM)
                    && resultCountIncreased(
                            resultCountBefore, matchingItemCount(current, expectedResult))
                    && everyInputWasConsumed(inputs, smithingInputs(inventory))) {
                completion.accept(current, BingoTask.APPLY_ARMOUR_TRIM);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSmithingResultDropped(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        PendingResult pending = pendingSmithingDrops.remove(player.getUniqueId());
        if (pending != null
                && isCurrent(player.getUniqueId(), pending.token())
                && event.getItemDrop().getItemStack().isSimilar(pending.result())
                && tracking.test(player, BingoTask.APPLY_ARMOUR_TRIM)) {
            completion.accept(player, BingoTask.APPLY_ARMOUR_TRIM);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecoratedPotCraftAttempt(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !isResultTakeAction(event.getAction())
                || event.getCurrentItem() == null
                || event.getCurrentItem().getType() != Material.DECORATED_POT
                || !(event.getInventory() instanceof CraftingInventory crafting)
                || !tracking.test(player, BingoTask.FOUR_SHERD_DECORATED_POT)) {
            return;
        }

        ItemStack[] matrix = crafting.getMatrix();
        if (!hasFourDistinctSherds(matrix)) return;

        UUID playerId = player.getUniqueId();
        AttemptToken token = tokenFor(playerId);
        PendingResult pending = new PendingResult(event.getCurrentItem().clone(), token);
        pendingPotCrafts.put(playerId, pending);
        Bukkit.getScheduler().runTask(
                plugin, () -> pendingPotCrafts.remove(playerId, pending));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemCrafted(ItemCraftedEvent event) {
        Player player = event.getPlayer();
        PendingResult pending = pendingPotCrafts.remove(player.getUniqueId());
        ItemStack crafted = event.getCraftedItem();
        if (pending != null
                && crafted.getType() == Material.DECORATED_POT
                && crafted.isSimilar(pending.result())
                && isCurrent(player.getUniqueId(), pending.token())
                && tracking.test(player, BingoTask.FOUR_SHERD_DECORATED_POT)) {
            completion.accept(player, BingoTask.FOUR_SHERD_DECORATED_POT);
        }
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        pendingPotCrafts.remove(playerId);
        pendingSmithingDrops.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) clearPlayerProgress(player);
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
        pendingPotCrafts.clear();
        pendingSmithingDrops.clear();
    }

    private PersistentDataContainer cardProgress(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        int cardId = activeCardId.getAsInt();
        Integer storedCardId = data.get(progressCardKey, PersistentDataType.INTEGER);
        if (storedCardId == null || storedCardId != cardId) {
            data.set(progressCardKey, PersistentDataType.INTEGER, cardId);
            data.remove(fishFlagsKey);
            data.remove(enchantedItemsKey);
        }
        return data;
    }

    private void clearPlayerProgress(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.remove(progressCardKey);
        data.remove(fishFlagsKey);
        data.remove(enchantedItemsKey);
    }

    private AttemptToken tokenFor(UUID playerId) {
        return new AttemptToken(
                detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return detectorGeneration == token.detectorGeneration()
                && playerGenerations.getOrDefault(playerId, 0L) == token.playerGeneration();
    }

    static int fishingCategory(ItemStack item) {
        return fishingCategory(item.getType(), !item.getEnchantments().isEmpty());
    }

    static int fishingCategory(Material material, boolean enchanted) {
        if (material == Material.FISHING_ROD) {
            // Vanilla has a damaged, unenchanted rod in junk and an enchanted rod
            // in treasure, so Material alone is deliberately not enough here.
            return enchanted ? FISH_TREASURE : FISH_JUNK;
        }
        if (TREASURE_ITEMS.contains(material)) return FISH_TREASURE;
        if (JUNK_ITEMS.contains(material)) return FISH_JUNK;
        return 0;
    }

    static Set<Material> decodeMaterials(String encoded) {
        if (encoded == null || encoded.isBlank()) return EnumSet.noneOf(Material.class);
        return Arrays.stream(encoded.split(","))
                .map(Material::matchMaterial)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
    }

    static String encodeMaterials(Set<Material> materials) {
        return materials.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    static int occupiedSlots(Inventory inventory) {
        int occupied = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && !item.getType().isAir()) occupied++;
        }
        return occupied;
    }

    static boolean appliesNewTrim(ItemStack before, ItemStack result) {
        if (result == null || !(result.getItemMeta() instanceof ArmorMeta resultMeta)
                || !resultMeta.hasTrim()) {
            return false;
        }
        if (before == null || !(before.getItemMeta() instanceof ArmorMeta beforeMeta)
                || !beforeMeta.hasTrim()) {
            return true;
        }
        return !java.util.Objects.equals(beforeMeta.getTrim(), resultMeta.getTrim());
    }

    static boolean hasFourDistinctSherds(ItemStack[] matrix) {
        Material[] materials = new Material[matrix.length];
        for (int index = 0; index < matrix.length; index++) {
            ItemStack item = matrix[index];
            materials[index] = item == null || item.getType().isAir()
                    ? null : item.getType();
        }
        return hasFourDistinctSherdMaterials(materials);
    }

    static boolean hasFourDistinctSherdMaterials(Material[] matrix) {
        Set<Material> sherds = new HashSet<>();
        int sherdSlots = 0;
        for (Material material : matrix) {
            if (material == null) continue;
            if (!material.name().endsWith("_POTTERY_SHERD")) return false;
            sherdSlots++;
            sherds.add(material);
        }
        return sherdSlots == 4 && sherds.size() == 4;
    }

    static StackSnapshot[] smithingInputs(SmithingInventory inventory) {
        return snapshots(new ItemStack[] {
            inventory.getInputTemplate(),
            inventory.getInputEquipment(),
            inventory.getInputMineral()
        });
    }

    static StackSnapshot[] snapshots(ItemStack[] items) {
        StackSnapshot[] snapshots = new StackSnapshot[items.length];
        for (int index = 0; index < items.length; index++) {
            ItemStack item = items[index];
            if (item != null && !item.getType().isAir()) {
                snapshots[index] = new StackSnapshot(item.getType(), item.getAmount());
            }
        }
        return snapshots;
    }

    static boolean everyInputWasConsumed(StackSnapshot[] before, StackSnapshot[] after) {
        if (before.length != after.length) return false;
        boolean hadInput = false;
        for (int index = 0; index < before.length; index++) {
            StackSnapshot previous = before[index];
            if (previous == null) continue;
            hadInput = true;
            StackSnapshot current = after[index];
            if (current != null
                    && (current.material() != previous.material()
                            || current.amount() >= previous.amount())) {
                return false;
            }
        }
        return hadInput;
    }

    static int matchingItemCount(Player player, ItemStack expected) {
        int count = matchingItemCount(player.getInventory().getStorageContents(), expected);
        count += matchingItemCount(player.getInventory().getArmorContents(), expected);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand.isSimilar(expected)) count += offhand.getAmount();
        ItemStack cursor = player.getOpenInventory().getCursor();
        if (cursor != null && cursor.isSimilar(expected)) count += cursor.getAmount();
        return count;
    }

    static int matchingItemCount(ItemStack[] items, ItemStack expected) {
        int count = 0;
        for (ItemStack item : items) {
            if (item != null && item.isSimilar(expected)) count += item.getAmount();
        }
        return count;
    }

    static boolean resultCountIncreased(int before, int after) {
        return before >= 0 && after > before;
    }

    static boolean canChangeInventory(InventoryAction action) {
        return action != InventoryAction.NOTHING
                && action != InventoryAction.CLONE_STACK
                && action != InventoryAction.UNKNOWN;
    }

    static boolean isResultTakeAction(InventoryAction action) {
        return switch (action) {
            case PICKUP_ALL,
                    PICKUP_SOME,
                    PICKUP_HALF,
                    PICKUP_ONE,
                    DROP_ALL_SLOT,
                    DROP_ONE_SLOT,
                    MOVE_TO_OTHER_INVENTORY,
                    HOTBAR_MOVE_AND_READD,
                    HOTBAR_SWAP -> true;
            default -> false;
        };
    }

    static boolean isDropResultAction(InventoryAction action) {
        return action == InventoryAction.DROP_ALL_SLOT
                || action == InventoryAction.DROP_ONE_SLOT;
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PendingResult(ItemStack result, AttemptToken token) {}

    record StackSnapshot(Material material, int amount) {}
}
