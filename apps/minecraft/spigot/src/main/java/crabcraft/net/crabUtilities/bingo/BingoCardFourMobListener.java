package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Stray;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

/** Event-driven detectors for the mob-interaction tasks in Bingo #4. */
public final class BingoCardFourMobListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final long MAX_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000;

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final NamespacedKey mooshroomOwnerKey;
    private final NamespacedKey mooshroomPrimedAtKey;
    private final NamespacedKey silverfishOwnerKey;
    private final NamespacedKey silverfishNamedAtKey;
    private final Map<BlockKey, OwnedPowderSnow> powderSnowOwners = new LinkedHashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;
    private long clearedAtMillis;

    public BingoCardFourMobListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.mooshroomOwnerKey = new NamespacedKey(plugin, "bingo_card4_mooshroom_owner");
        this.mooshroomPrimedAtKey = new NamespacedKey(plugin, "bingo_card4_mooshroom_primed_at");
        this.silverfishOwnerKey = new NamespacedKey(plugin, "bingo_card4_silverfish_owner");
        this.silverfishNamedAtKey = new NamespacedKey(plugin, "bingo_card4_silverfish_named_at");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSulfurCubeBucketed(PlayerBucketEntityEvent event) {
        if (!(event.getEntity() instanceof SulfurCube cube)) return;

        Player player = event.getPlayer();
        Material bodyItem = cube.getEquipment().getItem(EquipmentSlot.BODY).getType();
        if (isDiamondSulfurCubeBucketTarget(
                        cube.isAdult(), cube.getFuseTicks(), bodyItem, event.getOriginalBucket().getType())
                && tracking.test(player, BingoTask.SULFUR_CUBE_DIAMOND_BUCKET)) {
            completion.accept(player, BingoTask.SULFUR_CUBE_DIAMOND_BUCKET);
        }
    }

    static boolean isDiamondSulfurCubeBucketTarget(
            boolean adult, int fuseTicks, Material bodyItem, Material originalBucket) {
        return adult
                && fuseTicks < 0
                && bodyItem == Material.DIAMOND_BLOCK
                && originalBucket == Material.BUCKET;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSilverfishNamed(PlayerNameEntityEvent event) {
        if (!(event.getEntity() instanceof Silverfish silverfish)
                || !hasName(event.getName())) {
            return;
        }
        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.SILVERFISH_HIDE_IN_STONE)) return;

        PersistentDataContainer data = silverfish.getPersistentDataContainer();
        data.set(silverfishOwnerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(silverfishNamedAtKey, PersistentDataType.LONG, System.currentTimeMillis());
    }

    static boolean hasName(net.kyori.adventure.text.Component name) {
        return name != null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPowderSnowPlaced(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.POWDER_SNOW_BUCKET
                || !tracking.test(event.getPlayer(), BingoTask.FREEZE_SKELETON_STRAY)) {
            return;
        }

        schedulePowderSnowConfirmation(event.getPlayer(), BlockKey.from(event.getBlock()));
    }

    private void confirmPowderSnowPlaced(UUID playerId, BlockKey key, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Block block = blockAt(key);
        if (player == null
                || block == null
                || block.getType() != Material.POWDER_SNOW
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FREEZE_SKELETON_STRAY)) {
            return;
        }

        long now = System.currentTimeMillis();
        prunePowderSnowOwners(now);
        putBounded(powderSnowOwners, key, new OwnedPowderSnow(playerId, now));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkeletonFrozen(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.FROZEN
                || !(event.getEntity() instanceof Skeleton skeleton)
                || !(event.getTransformedEntity() instanceof Stray)
                || !skeleton.isInPowderedSnow()) {
            return;
        }

        OwnedPowderSnow owner = newestIntersectingPowderSnow(skeleton);
        if (owner == null) return;

        Player player = Bukkit.getPlayer(owner.playerId());
        if (player != null && tracking.test(player, BingoTask.FREEZE_SKELETON_STRAY)) {
            completion.accept(player, BingoTask.FREEZE_SKELETON_STRAY);
        }
    }

    private OwnedPowderSnow newestIntersectingPowderSnow(Skeleton skeleton) {
        long now = System.currentTimeMillis();
        prunePowderSnowOwners(now);
        UUID worldId = skeleton.getWorld().getUID();
        BoundingBox skeletonBounds = skeleton.getBoundingBox();
        OwnedPowderSnow newest = null;

        Iterator<Map.Entry<BlockKey, OwnedPowderSnow>> iterator =
                powderSnowOwners.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, OwnedPowderSnow> entry = iterator.next();
            BlockKey key = entry.getKey();
            if (!key.worldId().equals(worldId)
                    || !intersectsBlock(skeletonBounds, key.x(), key.y(), key.z())) {
                continue;
            }

            Block block = blockAt(key);
            if (block == null || block.getType() != Material.POWDER_SNOW) {
                iterator.remove();
                continue;
            }

            OwnedPowderSnow candidate = entry.getValue();
            if (newest == null || candidate.placedAtMillis() > newest.placedAtMillis()) {
                newest = candidate;
            }
        }
        return newest;
    }

    static boolean intersectsBlock(BoundingBox bounds, int x, int y, int z) {
        return bounds.overlaps(new BoundingBox(x, y, z, x + 1.0, y + 1.0, z + 1.0));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMooshroomInteracted(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof MushroomCow mooshroom)
                || !mooshroom.isAdult()
                || mooshroom.getVariant() != MushroomCow.Variant.BROWN) {
            return;
        }

        Player player = event.getPlayer();
        Material used = player.getInventory().getItem(event.getHand()).getType();
        if (used == Material.WITHER_ROSE) {
            beginMooshroomPrime(player, mooshroom);
        } else if (used == Material.BOWL) {
            beginMooshroomBowl(player, mooshroom);
        }
    }

    private void beginMooshroomPrime(Player player, MushroomCow mooshroom) {
        if (mooshroom.hasEffectsForNextStew()
                || !tracking.test(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        UUID mooshroomId = mooshroom.getUniqueId();
        PersistentMarker preliminaryMarker =
                new PersistentMarker(playerId, System.currentTimeMillis());
        writeMooshroomMarker(mooshroom, preliminaryMarker);
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmMooshroomPrimed(
                        playerId, mooshroomId, preliminaryMarker, token));
    }

    private void confirmMooshroomPrimed(
            UUID playerId,
            UUID mooshroomId,
            PersistentMarker preliminaryMarker,
            AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity entity = Bukkit.getEntity(mooshroomId);
        if (player == null
                || !(entity instanceof MushroomCow mooshroom)
                || !isPrimedBrownMooshroom(mooshroom)
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)) {
            if (entity instanceof MushroomCow currentMooshroom
                    && preliminaryMarker.equals(mooshroomMarker(currentMooshroom))) {
                clearMooshroomAttribution(currentMooshroom);
            }
            return;
        }

        if (!preliminaryMarker.equals(mooshroomMarker(mooshroom))) {
            return;
        }
    }

    private void beginMooshroomBowl(Player player, MushroomCow mooshroom) {
        if (!mooshroom.hasEffectForNextStew(PotionEffectType.WITHER)) return;

        PersistentMarker marker = mooshroomMarker(mooshroom);
        if (marker == null || !markerIsCurrent(marker)) return;

        UUID playerId = player.getUniqueId();
        UUID mooshroomId = mooshroom.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmMooshroomBowl(playerId, mooshroomId, marker, token));
    }

    private void confirmMooshroomBowl(
            UUID playerId, UUID mooshroomId, PersistentMarker expectedMarker, AttemptToken token) {
        Entity entity = Bukkit.getEntity(mooshroomId);
        if (!(entity instanceof MushroomCow mooshroom) || mooshroom.hasEffectsForNextStew()) {
            return;
        }

        PersistentMarker currentMarker = mooshroomMarker(mooshroom);
        if (!expectedMarker.equals(currentMarker)) return;
        clearMooshroomAttribution(mooshroom);

        Player player = Bukkit.getPlayer(playerId);
        if (player != null
                && expectedMarker.playerId().equals(playerId)
                && markerIsCurrent(expectedMarker)
                && isCurrent(playerId, token)
                && tracking.test(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)) {
            completion.accept(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW);
        }
    }

    private static boolean isPrimedBrownMooshroom(MushroomCow mooshroom) {
        return mooshroom.isAdult()
                && mooshroom.getVariant() == MushroomCow.Variant.BROWN
                && mooshroom.hasEffectForNextStew(PotionEffectType.WITHER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        BlockKey key = BlockKey.from(event.getBlockPlaced());
        removePowderSnow(key);
        // Powder Snow Bucket is a SolidBucketItem and uses BlockPlaceEvent on Paper 26.2.
        if (event.canBuild()
                && event.getBlockPlaced().getType() == Material.POWDER_SNOW
                && event.getItemInHand().getType() == Material.POWDER_SNOW_BUCKET
                && tracking.test(event.getPlayer(), BingoTask.FREEZE_SKELETON_STRAY)) {
            schedulePowderSnowConfirmation(event.getPlayer(), key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) {
        removePowderSnow(BlockKey.from(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBrokenByBlock(BlockBreakBlockEvent event) {
        removePowderSnow(BlockKey.from(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPowderSnowCollected(PlayerBucketFillEvent event) {
        removePowderSnow(BlockKey.from(event.getBlock()));
        removePowderSnow(BlockKey.from(event.getBlockClicked()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFaded(BlockFadeEvent event) {
        if (event.getNewState().getType() != Material.POWDER_SNOW) {
            removePowderSnow(BlockKey.from(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangedBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Silverfish silverfish
                && isSilverfishInfestation(event.getBlock().getType(), event.getTo())) {
            completeSilverfishInfestation(silverfish);
        }
        if (event.getTo() != Material.POWDER_SNOW) {
            removePowderSnow(BlockKey.from(event.getBlock()));
        }
    }

    private void completeSilverfishInfestation(Silverfish silverfish) {
        PersistentMarker marker = silverfishMarker(silverfish);
        if (marker == null || !markerIsCurrent(marker)) return;

        clearSilverfishAttribution(silverfish);
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && tracking.test(player, BingoTask.SILVERFISH_HIDE_IN_STONE)) {
            completion.accept(player, BingoTask.SILVERFISH_HIDE_IN_STONE);
        }
    }

    static boolean isSilverfishInfestation(Material from, Material to) {
        return switch (from) {
            case STONE -> to == Material.INFESTED_STONE;
            case COBBLESTONE -> to == Material.INFESTED_COBBLESTONE;
            case STONE_BRICKS -> to == Material.INFESTED_STONE_BRICKS;
            case MOSSY_STONE_BRICKS -> to == Material.INFESTED_MOSSY_STONE_BRICKS;
            case CRACKED_STONE_BRICKS -> to == Material.INFESTED_CRACKED_STONE_BRICKS;
            case CHISELED_STONE_BRICKS -> to == Material.INFESTED_CHISELED_STONE_BRICKS;
            case DEEPSLATE -> to == Material.INFESTED_DEEPSLATE;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(block -> removePowderSnow(BlockKey.from(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(block -> removePowderSnow(BlockKey.from(block)));
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
        powderSnowOwners.values().removeIf(owner -> owner.playerId().equals(playerId));
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
    }

    @Override
    public void clear() {
        detectorGeneration++;
        powderSnowOwners.clear();
        playerGenerations.clear();
        playerResetAtMillis.clear();
        clearedAtMillis = System.currentTimeMillis();
    }

    private void removePistonBlocks(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            BlockKey key = BlockKey.from(block);
            removePowderSnow(key);
            removePowderSnow(key.relative(direction));
        }
    }

    private void schedulePowderSnowConfirmation(Player player, BlockKey key) {
        UUID playerId = player.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmPowderSnowPlaced(playerId, key, token));
    }

    private void removePowderSnow(BlockKey key) {
        powderSnowOwners.remove(key);
    }

    private void prunePowderSnowOwners(long now) {
        powderSnowOwners.values().removeIf(owner -> !markerIsCurrent(
                new PersistentMarker(owner.playerId(), owner.placedAtMillis()), now));
    }

    private PersistentMarker mooshroomMarker(MushroomCow mooshroom) {
        PersistentDataContainer data = mooshroom.getPersistentDataContainer();
        String owner = data.get(mooshroomOwnerKey, PersistentDataType.STRING);
        Long primedAt = data.get(mooshroomPrimedAtKey, PersistentDataType.LONG);
        if (owner == null || primedAt == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), primedAt);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void writeMooshroomMarker(
            MushroomCow mooshroom, PersistentMarker marker) {
        PersistentDataContainer data = mooshroom.getPersistentDataContainer();
        data.set(mooshroomOwnerKey, PersistentDataType.STRING, marker.playerId().toString());
        data.set(mooshroomPrimedAtKey, PersistentDataType.LONG, marker.timestamp());
    }

    private PersistentMarker silverfishMarker(Silverfish silverfish) {
        PersistentDataContainer data = silverfish.getPersistentDataContainer();
        String owner = data.get(silverfishOwnerKey, PersistentDataType.STRING);
        Long namedAt = data.get(silverfishNamedAtKey, PersistentDataType.LONG);
        if (owner == null || namedAt == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), namedAt);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void clearMooshroomAttribution(MushroomCow mooshroom) {
        PersistentDataContainer data = mooshroom.getPersistentDataContainer();
        data.remove(mooshroomOwnerKey);
        data.remove(mooshroomPrimedAtKey);
    }

    private void clearSilverfishAttribution(Silverfish silverfish) {
        PersistentDataContainer data = silverfish.getPersistentDataContainer();
        data.remove(silverfishOwnerKey);
        data.remove(silverfishNamedAtKey);
    }

    private boolean markerIsCurrent(PersistentMarker marker) {
        return markerIsCurrent(marker, System.currentTimeMillis());
    }

    private boolean markerIsCurrent(PersistentMarker marker, long now) {
        long playerResetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L);
        return isFreshAttribution(
                marker.timestamp(), now, clearedAtMillis, playerResetAt, MAX_ATTRIBUTION_MILLIS);
    }

    static boolean isFreshAttribution(
            long timestamp, long now, long clearedAt, long playerResetAt, long maximumAge) {
        return timestamp > clearedAt
                && timestamp > playerResetAt
                && timestamp <= now
                && now - timestamp <= maximumAge;
    }

    private AttemptToken attemptToken(UUID playerId) {
        return new AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L));
    }

    private boolean isCurrent(UUID playerId, AttemptToken token) {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L);
    }

    private Block blockAt(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
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

        BlockKey relative(BlockFace face) {
            return new BlockKey(worldId, x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }
    }

    private record OwnedPowderSnow(UUID playerId, long placedAtMillis) {}

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PersistentMarker(UUID playerId, long timestamp) {}
}
