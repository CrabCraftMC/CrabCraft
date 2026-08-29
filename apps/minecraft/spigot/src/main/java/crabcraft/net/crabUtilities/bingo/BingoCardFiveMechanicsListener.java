package crabcraft.net.crabUtilities.bingo;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ChiseledBookshelfInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven inventory and equipment detectors for Bingo #5. */
public final class BingoCardFiveMechanicsListener implements BingoDetector {
    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;

    public BingoCardFiveMechanicsListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldronLevelChanged(CauldronLevelChangeEvent event) {
        if (event.getReason() != CauldronLevelChangeEvent.ChangeReason.BANNER_WASH
                || !(event.getEntity() instanceof Player player)
                || !tracking.test(player, BingoTask.CLEAN_BANNER_PATTERN)) {
            return;
        }

        // Vanilla only raises BANNER_WASH when a patterned banner is successfully
        // cleaned, so the uncancelled reason is stronger than inspecting either hand
        // after the interaction has already replaced the held ItemStack.
        completion.accept(player, BingoTask.CLEAN_BANNER_PATTERN);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrindstoneResultTaken(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory() instanceof GrindstoneInventory grindstone)
                || event.getClickedInventory() != grindstone
                || event.getSlotType() != InventoryType.SlotType.RESULT
                || !isResultTakeAction(event.getAction())
                || !tracking.test(player, BingoTask.REMOVE_ENCHANTMENT_GRINDSTONE)) {
            return;
        }

        ItemStack result = event.getCurrentItem();
        if (!removesNonCurseEnchantment(
                grindstone.getUpperItem(), grindstone.getLowerItem(), result)) {
            return;
        }

        completion.accept(player, BingoTask.REMOVE_ENCHANTMENT_GRINDSTONE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChiseledBookshelfInteracted(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()
                || event.getHand() == null
                || event.useInteractedBlock() == Event.Result.DENY
                || event.getItem() == null
                || event.getItem().getType() != Material.ENCHANTED_BOOK) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null
                || !(block.getState() instanceof ChiseledBookshelf shelf)
                || !tracking.test(player, BingoTask.FILL_CHISELED_BOOKSHELF_ENCHANTED)
                || !hasExactlyFiveEnchantedBooks(shelf.getInventory())) {
            return;
        }

        int emptySlot = onlyEmptySlot(shelf.getInventory().getStorageContents());
        Location interactionPoint = event.getInteractionPoint();
        if (emptySlot < 0
                || interactionPoint == null
                || shelf.getSlot(interactionPoint.toVector()
                                .subtract(block.getLocation().toVector()))
                        != emptySlot) {
            return;
        }

        UUID playerId = player.getUniqueId();
        AttemptToken token = tokenFor(playerId);
        BlockKey shelfKey = BlockKey.from(block);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmEnchantedBookshelf(playerId, shelfKey, emptySlot, token));
    }

    private void confirmEnchantedBookshelf(
            UUID playerId, BlockKey shelfKey, int insertedSlot, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        World world = Bukkit.getWorld(shelfKey.worldId());
        if (player == null
                || world == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FILL_CHISELED_BOOKSHELF_ENCHANTED)) {
            return;
        }

        Block block = world.getBlockAt(shelfKey.x(), shelfKey.y(), shelfKey.z());
        if (block.getState() instanceof ChiseledBookshelf shelf
                && shelf.getLastInteractedSlot() == insertedSlot
                && hasSixEnchantedBooks(shelf.getInventory())) {
            completion.accept(player, BingoTask.FILL_CHISELED_BOOKSHELF_ENCHANTED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerArmorChanged(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.WEAR_FOUR_ARMOUR_MATERIALS)) return;

        UUID playerId = player.getUniqueId();
        AttemptToken token = tokenFor(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> confirmFourArmorFamilies(playerId, token));
    }

    private void confirmFourArmorFamilies(UUID playerId, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.WEAR_FOUR_ARMOUR_MATERIALS)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        if (hasFourDistinctArmorFamilies(
                inventory.getHelmet(),
                inventory.getChestplate(),
                inventory.getLeggings(),
                inventory.getBoots())) {
            completion.accept(player, BingoTask.WEAR_FOUR_ARMOUR_MATERIALS);
        }
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
    }

    private AttemptToken tokenFor(UUID playerId) {
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

    static boolean attemptIsCurrent(
            long detectorGeneration,
            long playerGeneration,
            long tokenDetectorGeneration,
            long tokenPlayerGeneration) {
        return tokenDetectorGeneration == detectorGeneration
                && tokenPlayerGeneration == playerGeneration;
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

    static boolean removesNonCurseEnchantment(
            ItemStack upperInput, ItemStack lowerInput, ItemStack result) {
        Set<Enchantment> inputEnchantments = new HashSet<>();
        inputEnchantments.addAll(enchantments(upperInput));
        inputEnchantments.addAll(enchantments(lowerInput));
        Set<Enchantment> resultEnchantments = enchantments(result);
        return inputEnchantments.stream()
                .anyMatch(enchantment ->
                        !enchantment.isCursed() && !resultEnchantments.contains(enchantment));
    }

    private static Set<Enchantment> enchantments(ItemStack item) {
        if (isEmpty(item)) return Set.of();

        Set<Enchantment> enchantments = new HashSet<>(item.getEnchantments().keySet());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            enchantments.addAll(storageMeta.getStoredEnchants().keySet());
        }
        return enchantments;
    }

    static boolean hasExactlyFiveEnchantedBooks(ChiseledBookshelfInventory inventory) {
        return hasEnchantedBookLayout(inventory.getStorageContents(), 5, 1);
    }

    static boolean hasSixEnchantedBooks(ChiseledBookshelfInventory inventory) {
        return hasEnchantedBookLayout(inventory.getStorageContents(), 6, 0);
    }

    static boolean hasEnchantedBookLayout(
            ItemStack[] contents, int expectedBooks, int expectedEmptySlots) {
        return hasEnchantedBookMaterialLayout(
                materialContents(contents), expectedBooks, expectedEmptySlots);
    }

    static boolean hasEnchantedBookMaterialLayout(
            Material[] contents, int expectedBooks, int expectedEmptySlots) {
        if (contents == null || contents.length != 6) return false;

        int books = 0;
        int emptySlots = 0;
        for (Material material : contents) {
            if (isAirMaterial(material)) {
                emptySlots++;
            } else if (material == Material.ENCHANTED_BOOK) {
                books++;
            } else {
                return false;
            }
        }
        return books == expectedBooks && emptySlots == expectedEmptySlots;
    }

    static int onlyEmptySlot(ItemStack[] contents) {
        return onlyEmptyMaterialSlot(materialContents(contents));
    }

    static int onlyEmptyMaterialSlot(Material[] contents) {
        if (contents == null || contents.length != 6) return -1;

        int emptySlot = -1;
        for (int slot = 0; slot < contents.length; slot++) {
            Material material = contents[slot];
            if (!isAirMaterial(material)) continue;
            if (emptySlot >= 0) return -1;
            emptySlot = slot;
        }
        return emptySlot;
    }

    static boolean hasFourDistinctArmorFamilies(
            ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        return hasFourDistinctArmorMaterialFamilies(
                materialType(helmet),
                materialType(chestplate),
                materialType(leggings),
                materialType(boots));
    }

    static boolean hasFourDistinctArmorMaterialFamilies(
            Material helmet, Material chestplate, Material leggings, Material boots) {
        ArmorFamily helmetFamily = armorFamily(helmet, EquipmentSlot.HEAD);
        ArmorFamily chestFamily = armorFamily(chestplate, EquipmentSlot.CHEST);
        ArmorFamily legFamily = armorFamily(leggings, EquipmentSlot.LEGS);
        ArmorFamily bootFamily = armorFamily(boots, EquipmentSlot.FEET);
        if (helmetFamily == null
                || chestFamily == null
                || legFamily == null
                || bootFamily == null) {
            return false;
        }
        return EnumSet.of(helmetFamily, chestFamily, legFamily, bootFamily).size() == 4;
    }

    static ArmorFamily armorFamily(Material material, EquipmentSlot expectedSlot) {
        if (material == null) return null;
        return switch (material) {
            case LEATHER_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.LEATHER : null;
            case LEATHER_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.LEATHER : null;
            case LEATHER_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.LEATHER : null;
            case LEATHER_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.LEATHER : null;
            case CHAINMAIL_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.CHAINMAIL : null;
            case CHAINMAIL_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.CHAINMAIL : null;
            case CHAINMAIL_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.CHAINMAIL : null;
            case CHAINMAIL_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.CHAINMAIL : null;
            case COPPER_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.COPPER : null;
            case COPPER_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.COPPER : null;
            case COPPER_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.COPPER : null;
            case COPPER_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.COPPER : null;
            case IRON_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.IRON : null;
            case IRON_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.IRON : null;
            case IRON_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.IRON : null;
            case IRON_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.IRON : null;
            case GOLDEN_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.GOLD : null;
            case GOLDEN_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.GOLD : null;
            case GOLDEN_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.GOLD : null;
            case GOLDEN_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.GOLD : null;
            case DIAMOND_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.DIAMOND : null;
            case DIAMOND_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.DIAMOND : null;
            case DIAMOND_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.DIAMOND : null;
            case DIAMOND_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.DIAMOND : null;
            case NETHERITE_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.NETHERITE : null;
            case NETHERITE_CHESTPLATE -> expectedSlot == EquipmentSlot.CHEST ? ArmorFamily.NETHERITE : null;
            case NETHERITE_LEGGINGS -> expectedSlot == EquipmentSlot.LEGS ? ArmorFamily.NETHERITE : null;
            case NETHERITE_BOOTS -> expectedSlot == EquipmentSlot.FEET ? ArmorFamily.NETHERITE : null;
            case TURTLE_HELMET -> expectedSlot == EquipmentSlot.HEAD ? ArmorFamily.TURTLE : null;
            default -> null;
        };
    }

    private static Material[] materialContents(ItemStack[] contents) {
        if (contents == null) return null;
        Material[] materials = new Material[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            materials[slot] = materialType(contents[slot]);
        }
        return materials;
    }

    private static Material materialType(ItemStack item) {
        return isEmpty(item) ? null : item.getType();
    }

    private static boolean isAirMaterial(Material material) {
        return material == null
                || material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty() || item.getType().isAir();
    }

    enum ArmorFamily {
        LEATHER,
        CHAINMAIL,
        COPPER,
        IRON,
        GOLD,
        DIAMOND,
        NETHERITE,
        TURTLE
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
