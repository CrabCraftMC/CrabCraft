package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.LodestoneTracker;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Snow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the construction and world-interaction tasks on Bingo #5. */
public final class BingoCardFiveWorldListener implements BingoDetector {
    private static final int MAX_OWNED_BLOCKS_PER_PLAYER = 4_096;
    private static final int REQUIRED_DRIPLEAF_HEIGHT = 10;
    private static final int REQUIRED_SNOW_HEIGHTS = 8;
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final IntSupplier activeCardId;
    private final Map<BlockKey, UUID> dripleafOwners = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<BlockKey>> dripleavesByPlayer = new HashMap<>();
    private final Map<BlockKey, UUID> snowOwners = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<BlockKey>> snowByPlayer = new HashMap<>();
    private final Map<UUID, CompassBindingAttempt> compassBindingAttempts = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;

    public BingoCardFiveWorldListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.activeCardId = activeCardId;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        BlockKey key = BlockKey.from(block);
        UUID existingSnowOwner = ownerOf(key, OwnershipKind.SNOW);
        removeOwnedBlock(
                key, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer);

        if (!event.canBuild()) {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (isBigDripleaf(block.getType())
                && tracking.test(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)) {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
            putOwnedBlock(
                    key,
                    playerId,
                    OwnershipKind.DRIPLEAF,
                    dripleafOwners,
                    dripleavesByPlayer);
            if (ownedDripleafColumnHeight(key, playerId) >= REQUIRED_DRIPLEAF_HEIGHT) {
                completion.accept(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF);
            }
            return;
        }

        if (block.getType() == Material.SNOW
                && tracking.test(player, BingoTask.SNOW_EVERY_HEIGHT)) {
            boolean beganWithFreshBlock = event.getBlockReplacedState().getType() != Material.SNOW;
            boolean continuedOwnLayers = playerId.equals(existingSnowOwner);
            if (canOwnSnowLayer(beganWithFreshBlock, continuedOwnLayers)) {
                putOwnedBlock(
                        key, playerId, OwnershipKind.SNOW, snowOwners, snowByPlayer);
                if (connectedSnowCoversEveryHeight(key, playerId)) {
                    completion.accept(player, BingoTask.SNOW_EVERY_HEIGHT);
                }
            } else {
                removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
            }
        } else {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFertilised(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player == null
                || !tracking.test(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)) {
            return;
        }

        Set<BlockKey> changedDripleaf = new LinkedHashSet<>();
        for (BlockState state : event.getBlocks()) {
            if (isBigDripleaf(state.getType())) {
                changedDripleaf.add(BlockKey.from(state));
            }
        }
        if (changedDripleaf.isEmpty()) return;

        UUID playerId = player.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmDripleafGrowth(playerId, changedDripleaf, token));
    }

    private void confirmDripleafGrowth(
            UUID playerId, Set<BlockKey> changedDripleaf, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)) {
            return;
        }

        for (BlockKey key : changedDripleaf) {
            Block block = blockAt(key);
            if (block == null || !isBigDripleaf(block.getType())) continue;
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
            putOwnedBlock(
                    key,
                    playerId,
                    OwnershipKind.DRIPLEAF,
                    dripleafOwners,
                    dripleavesByPlayer);
            if (ownedDripleafColumnHeight(key, playerId) >= REQUIRED_DRIPLEAF_HEIGHT) {
                completion.accept(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCompassUsed(PlayerInteractEvent event) {
        if (event.getHand() == null
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.LODESTONE
                || event.getItem() == null
                || event.getItem().getType() != Material.COMPASS) {
            return;
        }

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.LODESTONE_COMPASS)) return;

        BlockKey lodestone = BlockKey.from(event.getClickedBlock());
        UUID playerId = player.getUniqueId();
        int previousBoundCount = countCompassesPointingAt(player, lodestone);
        CompassBindingAttempt attempt = new CompassBindingAttempt(
                lodestone, previousBoundCount, attemptToken(playerId));
        compassBindingAttempts.put(playerId, attempt);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmCompassBound(playerId, attempt));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCompassDropped(PlayerDropItemEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        CompassBindingAttempt attempt = compassBindingAttempts.get(playerId);
        ItemStack item = event.getItemDrop().getItemStack();
        if (attempt == null
                || !isCurrent(playerId, attempt.token)
                || item.getType() != Material.COMPASS) {
            return;
        }

        LodestoneTracker tracker = item.getData(DataComponentTypes.LODESTONE_TRACKER);
        if (tracker != null && pointsAt(tracker, attempt.lodestone)) {
            attempt.matchingDropObserved = true;
        }
    }

    private void confirmCompassBound(UUID playerId, CompassBindingAttempt attempt) {
        compassBindingAttempts.remove(playerId, attempt);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null
                || !isCurrent(playerId, attempt.token)
                || !tracking.test(player, BingoTask.LODESTONE_COMPASS)) {
            return;
        }

        int currentBoundCount = countCompassesPointingAt(player, attempt.lodestone);
        if (compassBindingSucceeded(
                attempt.previousBoundCount, currentBoundCount, attempt.matchingDropObserved)) {
            completion.accept(player, BingoTask.LODESTONE_COMPASS);
        }
    }

    private static int countCompassesPointingAt(Player player, BlockKey lodestone) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != Material.COMPASS) continue;
            LodestoneTracker tracker = item.getData(DataComponentTypes.LODESTONE_TRACKER);
            if (tracker != null && pointsAt(tracker, lodestone)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecartFuelled(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof PoweredMinecart minecart)) return;
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (item == null
                || !Tag.ITEMS_FURNACE_MINECART_FUEL.isTagged(item.getType())
                || !tracking.test(event.getPlayer(), BingoTask.POWER_FURNACE_MINECART)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        UUID minecartId = minecart.getUniqueId();
        int previousFuel = minecart.getFuel();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmMinecartFuelled(playerId, minecartId, previousFuel, token));
    }

    private void confirmMinecartFuelled(
            UUID playerId, UUID minecartId, int previousFuel, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(minecartId);
        if (player != null
                && entity instanceof PoweredMinecart minecart
                && minecart.isValid()
                && !minecart.isDead()
                && fuelIncreased(previousFuel, minecart.getFuel())
                && isCurrent(playerId, token)
                && tracking.test(player, BingoTask.POWER_FURNACE_MINECART)) {
            completion.accept(player, BingoTask.POWER_FURNACE_MINECART);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) {
        removeTrackedBlock(BlockKey.from(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBrokenByBlock(BlockBreakBlockEvent event) {
        removeTrackedBlock(BlockKey.from(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrown(BlockGrowEvent event) {
        removeTrackedBlock(BlockKey.from(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFaded(BlockFadeEvent event) {
        removeTrackedBlock(BlockKey.from(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangedBlock(EntityChangeBlockEvent event) {
        BlockKey key = BlockKey.from(event.getBlock());
        if (!isBigDripleaf(event.getTo())) {
            removeOwnedBlock(
                    key, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer);
        }
        if (event.getTo() != Material.SNOW) {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(block -> removeTrackedBlock(BlockKey.from(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(block -> removeTrackedBlock(BlockKey.from(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        removePistonBlocks(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        removePistonBlocks(event.getBlocks(), event.getDirection());
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
        compassBindingAttempts.remove(playerId);
        removeOwnedBlocks(
                playerId, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer);
        removeOwnedBlocks(playerId, OwnershipKind.SNOW, snowOwners, snowByPlayer);
    }

    @Override
    public void clear() {
        detectorGeneration++;
        dripleafOwners.clear();
        dripleavesByPlayer.clear();
        snowOwners.clear();
        snowByPlayer.clear();
        compassBindingAttempts.clear();
        playerGenerations.clear();
        playerResetAtMillis.clear();
    }

    private int ownedDripleafColumnHeight(BlockKey origin, UUID playerId) {
        Block originBlock = blockAt(origin);
        if (originBlock == null
                || !isBigDripleaf(originBlock.getType())
                || !playerId.equals(ownerOf(origin, OwnershipKind.DRIPLEAF))) {
            return 0;
        }

        World world = originBlock.getWorld();
        int bottom = origin.y();
        while (bottom > world.getMinHeight()
                && isOwnedDripleaf(origin.withY(bottom - 1), playerId)) {
            bottom--;
        }

        int height = 0;
        for (int y = bottom; y < world.getMaxHeight(); y++) {
            if (!isOwnedDripleaf(origin.withY(y), playerId)) break;
            height++;
        }
        return height;
    }

    private boolean isOwnedDripleaf(BlockKey key, UUID playerId) {
        if (!playerId.equals(ownerOf(key, OwnershipKind.DRIPLEAF))) return false;
        Block block = blockAt(key);
        return block != null && isBigDripleaf(block.getType());
    }

    private boolean connectedSnowCoversEveryHeight(BlockKey origin, UUID playerId) {
        if (!playerId.equals(ownerOf(origin, OwnershipKind.SNOW))) return false;

        boolean[] present = new boolean[REQUIRED_SNOW_HEIGHTS + 1];
        int distinct = 0;
        Set<BlockKey> visited = new HashSet<>();
        ArrayDeque<BlockKey> pending = new ArrayDeque<>();
        pending.add(origin);

        while (!pending.isEmpty()) {
            BlockKey key = pending.removeFirst();
            if (!visited.add(key)
                    || !playerId.equals(ownerOf(key, OwnershipKind.SNOW))) continue;

            Block block = blockAt(key);
            if (block == null || !(block.getBlockData() instanceof Snow snow)) continue;
            int layers = snow.getLayers();
            if (layers >= 1 && layers <= REQUIRED_SNOW_HEIGHTS && !present[layers]) {
                present[layers] = true;
                distinct++;
                if (distinct == REQUIRED_SNOW_HEIGHTS) return true;
            }

            for (BlockFace face : HORIZONTAL_FACES) {
                BlockKey adjacent = key.relative(face);
                if (!visited.contains(adjacent)
                        && playerId.equals(ownerOf(adjacent, OwnershipKind.SNOW))) {
                    pending.addLast(adjacent);
                }
            }
        }
        return false;
    }

    static boolean pointsAt(LodestoneTracker tracker, BlockKey lodestone) {
        Location location = tracker.location();
        return location != null
                && location.getWorld() != null
                && location.getWorld().getUID().equals(lodestone.worldId())
                && location.getBlockX() == lodestone.x()
                && location.getBlockY() == lodestone.y()
                && location.getBlockZ() == lodestone.z();
    }

    static boolean isBigDripleaf(Material material) {
        return material == Material.BIG_DRIPLEAF || material == Material.BIG_DRIPLEAF_STEM;
    }

    private void removePistonBlocks(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            BlockKey key = BlockKey.from(block);
            removeTrackedBlock(key);
            removeTrackedBlock(key.relative(direction));
        }
    }

    private void removeTrackedBlock(BlockKey key) {
        removeOwnedBlock(
                key, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer);
        removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer);
    }

    private Block blockAt(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
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

    static boolean canOwnSnowLayer(boolean beganWithFreshBlock, boolean continuedOwnLayers) {
        return beganWithFreshBlock || continuedOwnLayers;
    }

    static boolean fuelIncreased(int previousFuel, int currentFuel) {
        return currentFuel > previousFuel;
    }

    static boolean compassBindingSucceeded(
            int previousBoundCount, int currentBoundCount, boolean matchingDropObserved) {
        return currentBoundCount > previousBoundCount || matchingDropObserved;
    }

    private void putOwnedBlock(
            BlockKey key,
            UUID playerId,
            OwnershipKind kind,
            Map<BlockKey, UUID> owners,
            Map<UUID, LinkedHashSet<BlockKey>> byPlayer) {
        int cardId = activeCardId.getAsInt();
        if (cardId == Integer.MIN_VALUE) return;
        setOwnershipMarker(
                key,
                kind,
                new BlockOwnership(cardId, playerId, System.currentTimeMillis()));
        cacheOwnedBlock(key, playerId, owners, byPlayer);
    }

    private void cacheOwnedBlock(
            BlockKey key,
            UUID playerId,
            Map<BlockKey, UUID> owners,
            Map<UUID, LinkedHashSet<BlockKey>> byPlayer) {
        UUID previous = owners.put(key, playerId);
        if (previous != null && !previous.equals(playerId)) {
            LinkedHashSet<BlockKey> previousBlocks = byPlayer.get(previous);
            if (previousBlocks != null) {
                previousBlocks.remove(key);
                if (previousBlocks.isEmpty()) byPlayer.remove(previous);
            }
        }

        LinkedHashSet<BlockKey> owned = byPlayer.computeIfAbsent(
                playerId, ignored -> new LinkedHashSet<>());
        owned.add(key);
        while (owned.size() > MAX_OWNED_BLOCKS_PER_PLAYER) {
            Iterator<BlockKey> iterator = owned.iterator();
            if (!iterator.hasNext()) break;
            BlockKey oldest = iterator.next();
            iterator.remove();
            owners.remove(oldest, playerId);
        }
    }

    private void removeOwnedBlock(
            BlockKey key,
            OwnershipKind kind,
            Map<BlockKey, UUID> owners,
            Map<UUID, LinkedHashSet<BlockKey>> byPlayer) {
        clearOwnershipMarker(key, kind);
        UUID playerId = owners.remove(key);
        if (playerId == null) return;
        LinkedHashSet<BlockKey> owned = byPlayer.get(playerId);
        if (owned == null) return;
        owned.remove(key);
        if (owned.isEmpty()) byPlayer.remove(playerId);
    }

    private void removeOwnedBlocks(
            UUID playerId,
            OwnershipKind kind,
            Map<BlockKey, UUID> owners,
            Map<UUID, LinkedHashSet<BlockKey>> byPlayer) {
        Set<BlockKey> owned = byPlayer.remove(playerId);
        if (owned != null) {
            owned.forEach(key -> {
                owners.remove(key, playerId);
                clearOwnershipMarker(key, kind);
            });
        }
    }

    private UUID ownerOf(BlockKey key, OwnershipKind kind) {
        Chunk chunk = chunkAt(key);
        if (chunk == null) return null;

        NamespacedKey markerKey = ownershipKey(key, kind);
        String encoded = chunk.getPersistentDataContainer()
                .get(markerKey, PersistentDataType.STRING);
        BlockOwnership ownership = decodeOwnership(encoded);
        int cardId = activeCardId.getAsInt();
        long resetAt = ownership == null
                ? 0L
                : playerResetAtMillis.getOrDefault(ownership.playerId(), 0L);
        if (!ownershipIsCurrent(
                ownership, cardId, resetAt, System.currentTimeMillis())) {
            if (cardId != Integer.MIN_VALUE && encoded != null) {
                chunk.getPersistentDataContainer().remove(markerKey);
            }
            return null;
        }

        if (kind == OwnershipKind.DRIPLEAF) {
            cacheOwnedBlock(key, ownership.playerId(), dripleafOwners, dripleavesByPlayer);
        } else {
            cacheOwnedBlock(key, ownership.playerId(), snowOwners, snowByPlayer);
        }
        return ownership.playerId();
    }

    private void setOwnershipMarker(
            BlockKey key, OwnershipKind kind, BlockOwnership ownership) {
        Chunk chunk = chunkAt(key);
        if (chunk == null) return;
        chunk.getPersistentDataContainer().set(
                ownershipKey(key, kind),
                PersistentDataType.STRING,
                encodeOwnership(ownership));
    }

    private void clearOwnershipMarker(BlockKey key, OwnershipKind kind) {
        Chunk chunk = chunkAt(key);
        if (chunk != null) {
            chunk.getPersistentDataContainer().remove(ownershipKey(key, kind));
        }
    }

    private Chunk chunkAt(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        return world == null
                ? null
                : world.getChunkAt(Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16));
    }

    private NamespacedKey ownershipKey(BlockKey key, OwnershipKind kind) {
        return new NamespacedKey(
                plugin,
                "bingo_card5_" + kind.keyPart + "_"
                        + Math.floorMod(key.x(), 16) + "_" + key.y() + "_"
                        + Math.floorMod(key.z(), 16));
    }

    static String encodeOwnership(BlockOwnership ownership) {
        return ownership.cardId() + "|" + ownership.playerId() + "|" + ownership.timestamp();
    }

    static BlockOwnership decodeOwnership(String encoded) {
        if (encoded == null) return null;
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 3) return null;
        try {
            return new BlockOwnership(
                    Integer.parseInt(parts[0]),
                    UUID.fromString(parts[1]),
                    Long.parseLong(parts[2]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static boolean ownershipIsCurrent(
            BlockOwnership ownership, int activeCardId, long playerResetAt, long now) {
        return ownership != null
                && activeCardId != Integer.MIN_VALUE
                && ownership.cardId() == activeCardId
                && ownership.timestamp() > playerResetAt
                && ownership.timestamp() <= now;
    }

    record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        static BlockKey from(BlockState state) {
            return new BlockKey(
                    state.getWorld().getUID(), state.getX(), state.getY(), state.getZ());
        }

        BlockKey relative(BlockFace face) {
            return new BlockKey(worldId, x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }

        BlockKey withY(int nextY) {
            return new BlockKey(worldId, x, nextY, z);
        }
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    record BlockOwnership(int cardId, UUID playerId, long timestamp) {}

    private enum OwnershipKind {
        DRIPLEAF("dripleaf"),
        SNOW("snow");

        private final String keyPart;

        OwnershipKind(String keyPart) {
            this.keyPart = keyPart;
        }
    }

    private static final class CompassBindingAttempt {
        private final BlockKey lodestone;
        private final int previousBoundCount;
        private final AttemptToken token;
        private boolean matchingDropObserved;

        private CompassBindingAttempt(
                BlockKey lodestone, int previousBoundCount, AttemptToken token) {
            this.lodestone = lodestone;
            this.previousBoundCount = previousBoundCount;
            this.token = token;
        }
    }
}
