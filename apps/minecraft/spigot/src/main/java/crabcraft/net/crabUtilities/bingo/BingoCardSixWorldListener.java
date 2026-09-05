package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.IntSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.PortalType;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Campfire;
import org.bukkit.block.Conduit;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven world and block detectors for Bingo #6. */
public final class BingoCardSixWorldListener implements BingoDetector {
    private static final int FULL_CONDUIT_FRAME_BLOCKS = 42;
    private static final int CONDUIT_SEARCH_RADIUS = 2;
    private static final long MAX_GHAST_ATTRIBUTION_MILLIS =
            7L * 24 * 60 * 60 * 1_000;
    private static final int MAX_STARTUP_DEFERRAL_ATTEMPTS = 300;
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();
    private static final Set<Material> CONDUIT_FRAME_MATERIALS = Set.of(
            Material.PRISMARINE,
            Material.PRISMARINE_BRICKS,
            Material.DARK_PRISMARINE,
            Material.SEA_LANTERN);
    private static final Set<Material> FISH_BUCKETS = Set.of(
            Material.COD_BUCKET,
            Material.SALMON_BUCKET,
            Material.PUFFERFISH_BUCKET,
            Material.TROPICAL_FISH_BUCKET);

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final IntSupplier activeCardId;
    private final NamespacedKey ghastOwnerKey;
    private final NamespacedKey ghastNamedAtKey;
    private final NamespacedKey ghastCardIdKey;
    private final NamespacedKey ghastPlayerRunKey;
    private final NamespacedKey ghastArrivedAtKey;
    private final NamespacedKey playerRunKey;
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, PersistentMarker> pendingGhastConfirmations = new HashMap<>();
    private long detectorGeneration;

    public BingoCardSixWorldListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            IntSupplier activeCardId) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.activeCardId = activeCardId;
        this.ghastOwnerKey = new NamespacedKey(plugin, "bingo_card6_ghast_owner");
        this.ghastNamedAtKey = new NamespacedKey(plugin, "bingo_card6_ghast_named_at");
        this.ghastCardIdKey = new NamespacedKey(plugin, "bingo_card6_ghast_card");
        this.ghastPlayerRunKey = new NamespacedKey(plugin, "bingo_card6_ghast_run");
        this.ghastArrivedAtKey = new NamespacedKey(plugin, "bingo_card6_ghast_arrived");
        this.playerRunKey = new NamespacedKey(plugin, "bingo_card6_world_run");
        Bukkit.getScheduler().runTask(plugin, () -> resumeLoadedGhasts(null));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlaced(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null
                || !(event.getEntity() instanceof Painting painting)
                || !isFourByFour(painting)
                || !tracking.test(player, BingoTask.HANG_FOUR_BY_FOUR_PAINTING)) {
            return;
        }

        completion.accept(player, BingoTask.HANG_FOUR_BY_FOUR_PAINTING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockInteracted(PlayerInteractEvent event) {
        if (event.getHand() == null
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY
                || event.useItemInHand() == Event.Result.DENY
                || event.getClickedBlock() == null
                || event.getItem() == null) {
            return;
        }

        detectHangingSignOutline(event);
        detectCampfireFilled(event);
    }

    private void detectHangingSignOutline(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        ItemStack item = event.getItem();
        if (block == null
                || item == null
                || item.getType() != Material.GLOW_INK_SAC
                || !Tag.ALL_HANGING_SIGNS.isTagged(block.getType())
                || !(block.getState() instanceof Sign sign)) {
            return;
        }

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.OUTLINE_HANGING_SIGN)) return;

        Side side = sign.getInteractableSideFor(player);
        if (side == null
                || sign.getSide(side).isGlowingText()
                || !hasVisibleText(sign, side)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        BlockKey signKey = BlockKey.from(block);
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmHangingSignOutlined(playerId, signKey, side, token));
    }

    private void confirmHangingSignOutlined(
            UUID playerId, BlockKey signKey, Side side, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Block block = blockAt(signKey);
        if (player == null
                || block == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.OUTLINE_HANGING_SIGN)
                || !Tag.ALL_HANGING_SIGNS.isTagged(block.getType())
                || !(block.getState() instanceof Sign sign)
                || !hasVisibleText(sign, side)
                || !sign.getSide(side).isGlowingText()) {
            return;
        }

        completion.accept(player, BingoTask.OUTLINE_HANGING_SIGN);
    }

    private void detectCampfireFilled(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null
                || !Tag.CAMPFIRES.isTagged(block.getType())
                || !(block.getState() instanceof Campfire campfire)
                || occupiedCampfireSlots(campfire) != campfire.getSize() - 1) {
            return;
        }

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)) return;

        UUID playerId = player.getUniqueId();
        BlockKey campfireKey = BlockKey.from(block);
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmCampfireFilled(playerId, campfireKey, token));
    }

    private void confirmCampfireFilled(
            UUID playerId, BlockKey campfireKey, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Block block = blockAt(campfireKey);
        if (player == null
                || block == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)
                || !(block.getState() instanceof Campfire campfire)
                || occupiedCampfireSlots(campfire) != campfire.getSize()) {
            return;
        }

        completion.accept(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowInteractsWithButton(EntityInteractEvent event) {
        Entity entity = event.getEntity();
        Block hitBlock = event.getBlock();
        if (!(entity instanceof Projectile projectile)
                || !isArrow(projectile.getType())
                || !Tag.WOODEN_BUTTONS.isTagged(hitBlock.getType())
                || !(projectile.getShooter() instanceof Player player)
                || !tracking.test(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        BlockKey buttonKey = BlockKey.from(hitBlock);
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmButtonPowered(playerId, buttonKey, token));
    }

    private void confirmButtonPowered(
            UUID playerId, BlockKey buttonKey, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Block button = blockAt(buttonKey);
        if (player == null
                || button == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)
                || !Tag.WOODEN_BUTTONS.isTagged(button.getType())
                || !(button.getBlockData() instanceof Powerable powerable)
                || !powerable.isPowered()) {
            return;
        }

        completion.accept(player, BingoTask.SHOOT_BUTTON_WITH_ARROW);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (!event.canBuild()
                || !CONDUIT_FRAME_MATERIALS.contains(event.getBlockPlaced().getType())
                || CONDUIT_FRAME_MATERIALS.contains(event.getBlockReplacedState().getType())) {
            return;
        }

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.FULLY_POWER_CONDUIT)) return;

        UUID playerId = player.getUniqueId();
        BlockKey frameKey = BlockKey.from(event.getBlockPlaced());
        List<BlockKey> completedConduits = findCompletedConduits(event.getBlockPlaced());
        if (completedConduits.isEmpty()) return;
        AttemptToken token = attemptToken(playerId);
        // Vanilla refreshes a Conduit's frame cache on a 40-tick cadence rather
        // than synchronously with every neighbouring block placement.
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> confirmConduitFullyPowered(
                        playerId, frameKey, completedConduits, token),
                45L);
    }

    private void confirmConduitFullyPowered(
            UUID playerId,
            BlockKey frameKey,
            List<BlockKey> completedConduits,
            AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Block placedFrame = blockAt(frameKey);
        if (player == null
                || placedFrame == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FULLY_POWER_CONDUIT)
                || !CONDUIT_FRAME_MATERIALS.contains(placedFrame.getType())) {
            return;
        }

        for (BlockKey conduitKey : completedConduits) {
            Block block = blockAt(conduitKey);
            if (block != null
                    && block.getState() instanceof Conduit conduit
                    && isFullyPoweredBy(conduit, placedFrame)) {
                completion.accept(player, BingoTask.FULLY_POWER_CONDUIT);
                return;
            }
        }
    }

    private List<BlockKey> findCompletedConduits(Block placedFrame) {
        List<BlockKey> completed = new ArrayList<>();
        World world = placedFrame.getWorld();
        for (int x = placedFrame.getX() - CONDUIT_SEARCH_RADIUS;
                x <= placedFrame.getX() + CONDUIT_SEARCH_RADIUS;
                x++) {
            for (int y = placedFrame.getY() - CONDUIT_SEARCH_RADIUS;
                    y <= placedFrame.getY() + CONDUIT_SEARCH_RADIUS;
                    y++) {
                for (int z = placedFrame.getZ() - CONDUIT_SEARCH_RADIUS;
                        z <= placedFrame.getZ() + CONDUIT_SEARCH_RADIUS;
                        z++) {
                    Block candidate = world.getBlockAt(x, y, z);
                    int dx = placedFrame.getX() - x;
                    int dy = placedFrame.getY() - y;
                    int dz = placedFrame.getZ() - z;
                    if (candidate.getType() == Material.CONDUIT
                            && isConduitFrameOffset(dx, dy, dz)
                            && hasCompleteConduitFrame(candidate)) {
                        completed.add(BlockKey.from(candidate));
                    }
                }
            }
        }
        return List.copyOf(completed);
    }

    private static boolean hasCompleteConduitFrame(Block conduit) {
        World world = conduit.getWorld();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (isConduitFrameOffset(dx, dy, dz)
                            && !CONDUIT_FRAME_MATERIALS.contains(world.getBlockAt(
                                            conduit.getX() + dx,
                                            conduit.getY() + dy,
                                            conduit.getZ() + dz)
                                    .getType())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishBucketEmptied(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() == World.Environment.NETHER
                && isFishBucket(event.getBucket())
                && tracking.test(player, BingoTask.PLACE_FISH_IN_NETHER)) {
            completion.accept(player, BingoTask.PLACE_FISH_IN_NETHER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGhastNamed(PlayerNameEntityEvent event) {
        if (!(event.getEntity() instanceof Ghast ghast)) return;

        clearGhastMarker(ghast);
        Player player = event.getPlayer();
        int cardId = activeCardId.getAsInt();
        if (event.getName() == null
                || PLAIN.serialize(event.getName()).isBlank()
                || cardId == Integer.MIN_VALUE
                || !tracking.test(player, BingoTask.NAMED_GHAST_OVERWORLD)) {
            return;
        }

        setGhastMarker(
                ghast,
                new PersistentMarker(
                        player.getUniqueId(),
                        System.currentTimeMillis(),
                        cardId,
                        playerRun(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGhastPortal(EntityPortalEvent event) {
        if (!(event.getEntity() instanceof Ghast ghast)
                || event.getPortalType() != PortalType.NETHER
                || event.getFrom().getWorld() == null
                || event.getFrom().getWorld().getEnvironment() != World.Environment.NETHER
                || event.getTo() == null
                || event.getTo().getWorld() == null
                || event.getTo().getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }

        PersistentMarker marker = ghastMarker(ghast);
        if (marker == null
                || !timestampIsCurrent(
                        marker.timestamp(),
                        System.currentTimeMillis(),
                        MAX_GHAST_ATTRIBUTION_MILLIS)) {
            return;
        }
        int cardId = activeCardId.getAsInt();
        if (cardId != Integer.MIN_VALUE && marker.cardId() != cardId) {
            clearGhastMarker(ghast);
            return;
        }
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && marker.playerRun() != playerRun(player)) {
            clearGhastMarker(ghast);
            return;
        }

        ghast.getPersistentDataContainer().set(
                ghastArrivedAtKey, PersistentDataType.LONG, System.currentTimeMillis());
        scheduleGhastConfirmation(ghast, marker);
    }

    @EventHandler
    public void onEntitiesLoaded(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Ghast ghast) resumeGhastConfirmation(ghast, null);
        }
    }

    @EventHandler
    public void onPlayerJoined(PlayerJoinEvent event) {
        resumeLoadedGhasts(event.getPlayer().getUniqueId());
    }

    private void resumeLoadedGhasts(UUID ownerFilter) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            for (Ghast ghast : world.getEntitiesByClass(Ghast.class)) {
                resumeGhastConfirmation(ghast, ownerFilter);
            }
        }
    }

    private void resumeGhastConfirmation(Ghast ghast, UUID ownerFilter) {
        if (ghast.getWorld().getEnvironment() != World.Environment.NORMAL
                || !hasArrivedInOverworldMarker(ghast)) return;
        PersistentMarker marker = ghastMarker(ghast);
        if (marker == null
                || (ownerFilter != null && !ownerFilter.equals(marker.playerId()))) return;
        int cardId = activeCardId.getAsInt();
        if (!timestampIsCurrent(
                        marker.timestamp(),
                        System.currentTimeMillis(),
                        MAX_GHAST_ATTRIBUTION_MILLIS)
                || (cardId != Integer.MIN_VALUE && cardId != marker.cardId())) {
            clearGhastMarker(ghast);
            return;
        }
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && marker.playerRun() != playerRun(player)) {
            clearGhastMarker(ghast);
            return;
        }
        scheduleGhastConfirmation(ghast, marker);
    }

    private void scheduleGhastConfirmation(Ghast ghast, PersistentMarker marker) {
        UUID ghastId = ghast.getUniqueId();
        PersistentMarker previous = pendingGhastConfirmations.put(ghastId, marker);
        if (marker.equals(previous)) return;
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmGhastInOverworld(ghastId, marker, 0));
    }

    private void confirmGhastInOverworld(
            UUID ghastId, PersistentMarker marker, int attempts) {
        if (!marker.equals(pendingGhastConfirmations.get(ghastId))) return;
        int cardId = activeCardId.getAsInt();
        if (!timestampIsCurrent(
                        marker.timestamp(),
                        System.currentTimeMillis(),
                        MAX_GHAST_ATTRIBUTION_MILLIS)
                || (cardId != Integer.MIN_VALUE && cardId != marker.cardId())) {
            pendingGhastConfirmations.remove(ghastId, marker);
            Entity staleEntity = Bukkit.getEntity(ghastId);
            if (staleEntity instanceof Ghast staleGhast) clearGhastMarker(staleGhast);
            return;
        }

        Entity entity = Bukkit.getEntity(ghastId);
        Player player = Bukkit.getPlayer(marker.playerId());
        if (!(entity instanceof Ghast ghast)) {
            deferGhastConfirmation(ghastId, marker, attempts);
            return;
        }
        if (!ghast.isValid()
                || ghast.isDead()
                || ghast.getWorld().getEnvironment() != World.Environment.NORMAL
                || !hasArrivedInOverworldMarker(ghast)
                || !marker.equals(ghastMarker(ghast))) {
            pendingGhastConfirmations.remove(ghastId, marker);
            return;
        }

        if (player != null && marker.playerRun() != playerRun(player)) {
            pendingGhastConfirmations.remove(ghastId, marker);
            clearGhastMarker(ghast);
            return;
        }
        if (persistentConfirmationNeedsContext(
                marker.cardId(), cardId, player != null)) {
            if (shouldDeferPersistentConfirmation(
                    marker.cardId(),
                    cardId,
                    player != null,
                    true,
                    attempts,
                    MAX_STARTUP_DEFERRAL_ATTEMPTS)) {
                deferGhastConfirmation(ghastId, marker, attempts);
            } else {
                // Leave the PDC marker intact so a later entity-load or player-join
                // scan can start a fresh bounded confirmation window.
                pendingGhastConfirmations.remove(ghastId, marker);
            }
            return;
        }
        if (player == null || !markerIsCurrent(marker)) {
            pendingGhastConfirmations.remove(ghastId, marker);
            clearGhastMarker(ghast);
            return;
        }
        if (!tracking.test(player, BingoTask.NAMED_GHAST_OVERWORLD)) {
            // Retain the persistent attribution, but release this in-memory
            // attempt so a later entity-load or player-join scan can retry.
            pendingGhastConfirmations.remove(ghastId, marker);
            return;
        }

        pendingGhastConfirmations.remove(ghastId, marker);
        clearGhastMarker(ghast);
        completion.accept(player, BingoTask.NAMED_GHAST_OVERWORLD);
    }

    private void deferGhastConfirmation(
            UUID ghastId, PersistentMarker marker, int attempts) {
        if (attempts >= MAX_STARTUP_DEFERRAL_ATTEMPTS) {
            pendingGhastConfirmations.remove(ghastId, marker);
            return;
        }
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> confirmGhastInOverworld(ghastId, marker, attempts + 1),
                20L);
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
    }

    @Override
    public void clear() {
        detectorGeneration++;
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

    private Block blockAt(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
    }

    private PersistentMarker ghastMarker(Ghast ghast) {
        PersistentDataContainer data = ghast.getPersistentDataContainer();
        String owner = data.get(ghastOwnerKey, PersistentDataType.STRING);
        Long namedAt = data.get(ghastNamedAtKey, PersistentDataType.LONG);
        Integer cardId = data.get(ghastCardIdKey, PersistentDataType.INTEGER);
        Long playerRun = data.get(ghastPlayerRunKey, PersistentDataType.LONG);
        if (owner == null || namedAt == null || cardId == null || playerRun == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), namedAt, cardId, playerRun);
        } catch (IllegalArgumentException ignored) {
            clearGhastMarker(ghast);
            return null;
        }
    }

    private void setGhastMarker(Ghast ghast, PersistentMarker marker) {
        PersistentDataContainer data = ghast.getPersistentDataContainer();
        data.set(ghastOwnerKey, PersistentDataType.STRING, marker.playerId().toString());
        data.set(ghastNamedAtKey, PersistentDataType.LONG, marker.timestamp());
        data.set(ghastCardIdKey, PersistentDataType.INTEGER, marker.cardId());
        data.set(ghastPlayerRunKey, PersistentDataType.LONG, marker.playerRun());
    }

    private void clearGhastMarker(Ghast ghast) {
        pendingGhastConfirmations.remove(ghast.getUniqueId());
        PersistentDataContainer data = ghast.getPersistentDataContainer();
        data.remove(ghastOwnerKey);
        data.remove(ghastNamedAtKey);
        data.remove(ghastCardIdKey);
        data.remove(ghastPlayerRunKey);
        data.remove(ghastArrivedAtKey);
    }

    private boolean hasArrivedInOverworldMarker(Ghast ghast) {
        return ghast.getPersistentDataContainer().has(
                ghastArrivedAtKey, PersistentDataType.LONG);
    }

    private boolean markerIsCurrent(PersistentMarker marker) {
        long now = System.currentTimeMillis();
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player == null) return false;
        return markerIsCurrent(
                marker.cardId(),
                activeCardId.getAsInt(),
                marker.timestamp(),
                now,
                marker.playerRun(),
                playerRun(player),
                MAX_GHAST_ATTRIBUTION_MILLIS);
    }

    private long playerRun(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(playerRunKey, PersistentDataType.LONG, 0L);
    }

    static boolean isFourByFour(Painting painting) {
        return isFourByFour(
                painting.getArt().getBlockWidth(), painting.getArt().getBlockHeight());
    }

    static boolean isFourByFour(int width, int height) {
        return width == 4 && height == 4;
    }

    private static boolean hasVisibleText(Sign sign, Side side) {
        return sign.getSide(side).lines().stream()
                .map(PLAIN::serialize)
                .anyMatch(line -> !line.isBlank());
    }

    static int occupiedCampfireSlots(Campfire campfire) {
        int occupied = 0;
        for (int slot = 0; slot < campfire.getSize(); slot++) {
            ItemStack item = campfire.getItem(slot);
            if (item != null && !item.isEmpty() && !item.getType().isAir()) occupied++;
        }
        return occupied;
    }

    static boolean isArrow(EntityType type) {
        return type == EntityType.ARROW || type == EntityType.SPECTRAL_ARROW;
    }

    static boolean isFullyPoweredBy(Conduit conduit, Block placedFrame) {
        boolean placedFrameIncluded = conduit.getFrameBlocks().stream()
                .anyMatch(frame -> frame.equals(placedFrame));
        return isFullyPoweredConduit(
                conduit.isActive(), conduit.getFrameBlockCount(), placedFrameIncluded);
    }

    static boolean isFullyPoweredConduit(
            boolean active, int frameBlockCount, boolean placedFrameIncluded) {
        return active
                && frameBlockCount >= FULL_CONDUIT_FRAME_BLOCKS
                && placedFrameIncluded;
    }

    static boolean isConduitFrameOffset(int dx, int dy, int dz) {
        if (Math.abs(dx) > 2 || Math.abs(dy) > 2 || Math.abs(dz) > 2) return false;
        if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1) return false;
        return (dx == 0 && (Math.abs(dy) == 2 || Math.abs(dz) == 2))
                || (dy == 0 && (Math.abs(dx) == 2 || Math.abs(dz) == 2))
                || (dz == 0 && (Math.abs(dx) == 2 || Math.abs(dy) == 2));
    }

    static boolean isFishBucket(Material material) {
        return FISH_BUCKETS.contains(material);
    }

    static boolean attemptIsCurrent(
            long detectorGeneration,
            long playerGeneration,
            long tokenDetectorGeneration,
            long tokenPlayerGeneration) {
        return detectorGeneration == tokenDetectorGeneration
                && playerGeneration == tokenPlayerGeneration;
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
                && markerPlayerRun == currentPlayerRun
                && timestampIsCurrent(timestamp, now, maximumAge);
    }

    static boolean timestampIsCurrent(long timestamp, long now, long maximumAge) {
        return timestamp <= now && now - timestamp <= maximumAge;
    }

    static boolean shouldDeferPersistentConfirmation(
            int markerCardId,
            int activeCardId,
            boolean ownerOnline,
            boolean timestampCurrent,
            int attempts,
            int maximumAttempts) {
        return persistentConfirmationNeedsContext(
                        markerCardId, activeCardId, ownerOnline)
                && timestampCurrent
                && attempts < maximumAttempts;
    }

    static boolean persistentConfirmationNeedsContext(
            int markerCardId, int activeCardId, boolean ownerOnline) {
        return activeCardId == Integer.MIN_VALUE
                || (!ownerOnline && markerCardId == activeCardId);
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record PersistentMarker(
            UUID playerId, long timestamp, int cardId, long playerRun) {}

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey from(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
