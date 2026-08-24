package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for the construction and transport tasks on Bingo #4. */
public final class BingoCardFourWorldListener implements BingoDetector {
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final double ENDER_PEARL_HORIZONTAL_DISTANCE_SQUARED = 100.0 * 100.0;
    private static final long MAX_PERSISTENT_ATTRIBUTION_MILLIS =
            7L * 24 * 60 * 60 * 1_000;
    private static final BlockFace[] CARTESIAN_FACES = {
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST
    };

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;
    private final NamespacedKey snowBuilderKey;
    private final NamespacedKey snowBuiltAtKey;
    private final NamespacedKey harnessOwnerKey;
    private final NamespacedKey harnessEquippedAtKey;
    private final Map<BlockKey, BuildAttempt> snowBuildAttempts = new LinkedHashMap<>();
    private final Map<UUID, HarnessAttempt> harnessAttempts = new LinkedHashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private final Map<UUID, Long> playerResetAtMillis = new HashMap<>();
    private long detectorGeneration;
    private long clearedAtMillis;

    public BingoCardFourWorldListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.snowBuilderKey = new NamespacedKey(plugin, "bingo_card4_snow_builder");
        this.snowBuiltAtKey = new NamespacedKey(plugin, "bingo_card4_snow_built_at");
        this.harnessOwnerKey = new NamespacedKey(plugin, "bingo_card4_harness_owner");
        this.harnessEquippedAtKey =
                new NamespacedKey(plugin, "bingo_card4_harness_equipped_at");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalCreated(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE
                || !(event.getEntity() instanceof Player player)
                || !tracking.test(player, BingoTask.FOUR_BY_FOUR_NETHER_PORTAL)) {
            return;
        }

        Set<BlockPoint> portalBlocks = new LinkedHashSet<>();
        for (BlockState state : event.getBlocks()) {
            if (state.getType() == Material.NETHER_PORTAL) {
                portalBlocks.add(BlockPoint.from(state));
            }
        }
        if (!formsFourByFourPortal(portalBlocks)) return;

        UUID playerId = player.getUniqueId();
        UUID worldId = event.getWorld().getUID();
        AttemptToken token = attemptToken(playerId);
        Set<BlockPoint> expected = Set.copyOf(portalBlocks);
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmPortal(playerId, worldId, expected, token));
    }

    private void confirmPortal(
            UUID playerId, UUID worldId, Set<BlockPoint> expected, AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        World world = Bukkit.getWorld(worldId);
        if (player == null
                || world == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FOUR_BY_FOUR_NETHER_PORTAL)) {
            return;
        }

        for (BlockPoint point : expected) {
            if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)
                    || world.getBlockAt(point.x(), point.y(), point.z()).getType()
                            != Material.NETHER_PORTAL) {
                return;
            }
        }
        completion.accept(player, BingoTask.FOUR_BY_FOUR_NETHER_PORTAL);
    }

    static boolean formsFourByFourPortal(Set<BlockPoint> points) {
        if (points.size() != 16) return false;

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPoint point : points) {
            minX = Math.min(minX, point.x());
            maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
            minZ = Math.min(minZ, point.z());
            maxZ = Math.max(maxZ, point.z());
        }

        if (maxY - minY != 3) return false;
        boolean xPlane = minX == maxX && maxZ - minZ == 3;
        boolean zPlane = minZ == maxZ && maxX - minX == 3;
        if (!xPlane && !zPlane) return false;

        for (int y = minY; y <= maxY; y++) {
            for (int horizontal = 0; horizontal < 4; horizontal++) {
                BlockPoint expected = xPlane
                        ? new BlockPoint(minX, y, minZ + horizontal)
                        : new BlockPoint(minX + horizontal, y, minZ);
                if (!points.contains(expected)) return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || !isHundredBlockHorizontalTeleport(event.getFrom(), event.getTo())
                || !tracking.test(event.getPlayer(), BingoTask.ENDER_PEARL_TELEPORT_HUNDRED)) {
            return;
        }

        completion.accept(event.getPlayer(), BingoTask.ENDER_PEARL_TELEPORT_HUNDRED);
    }

    static boolean isHundredBlockHorizontalTeleport(Location from, Location to) {
        return from.getWorld() != null
                && to.getWorld() != null
                && isHundredBlockHorizontalTeleport(
                        from.getWorld().getUID(),
                        from.getX(),
                        from.getZ(),
                        to.getWorld().getUID(),
                        to.getX(),
                        to.getZ());
    }

    static boolean isHundredBlockHorizontalTeleport(
            UUID fromWorld,
            double fromX,
            double fromZ,
            UUID toWorld,
            double toX,
            double toZ) {
        if (!fromWorld.equals(toWorld)) return false;
        double x = toX - fromX;
        double z = toZ - fromZ;
        return x * x + z * z >= ENDER_PEARL_HORIZONTAL_DISTANCE_SQUARED;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemPumpkinPlaced(BlockPlaceEvent event) {
        if (!event.canBuild() || !isGolemPumpkin(event.getBlockPlaced().getType())) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Block pumpkin = event.getBlockPlaced();

        if (!tracking.test(player, BingoTask.SNOW_GOLEM_KILLS_BLAZE)) return;
        BuildAttempt attempt = new BuildAttempt(playerId, tick, attemptToken(playerId));
        for (BlockFace direction : CARTESIAN_FACES) {
            if (pumpkin.getRelative(direction).getType() != Material.SNOW_BLOCK
                    || pumpkin.getRelative(direction, 2).getType() != Material.SNOW_BLOCK) {
                continue;
            }
            BlockKey key = BlockKey.from(pumpkin.getRelative(direction, 2));
            putBounded(snowBuildAttempts, key, attempt);
            expireBuildAttempt(snowBuildAttempts, key, attempt);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGolemSpawned(CreatureSpawnEvent event) {
        int tick = Bukkit.getCurrentTick();
        if (event.getEntity() instanceof Snowman snowman
                && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) {
            BuildAttempt attempt = snowBuildAttempts.remove(BlockKey.from(snowman.getLocation()));
            if (validBuildAttempt(attempt, tick, BingoTask.SNOW_GOLEM_KILLS_BLAZE)) {
                setMarker(
                        snowman,
                        snowBuilderKey,
                        snowBuiltAtKey,
                        attempt.playerId(),
                        System.currentTimeMillis());
            }
        }
    }

    private boolean validBuildAttempt(BuildAttempt attempt, int tick, BingoTask task) {
        if (attempt == null
                || attempt.tick() != tick
                || !isCurrent(attempt.playerId(), attempt.token())) {
            return false;
        }
        Player player = Bukkit.getPlayer(attempt.playerId());
        return player != null && tracking.test(player, task);
    }

    private void expireBuildAttempt(
            Map<BlockKey, BuildAttempt> attempts, BlockKey key, BuildAttempt attempt) {
        Bukkit.getScheduler().runTask(plugin, () -> attempts.remove(key, attempt));
    }

    private static boolean isGolemPumpkin(Material material) {
        return material == Material.CARVED_PUMPKIN || material == Material.JACK_O_LANTERN;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlazeKilled(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Blaze)
                || !(event.getDamageSource().getDirectEntity() instanceof Snowball snowball)
                || !(snowball.getShooter() instanceof Snowman snowman)) {
            return;
        }

        PersistentMarker marker = markerFrom(snowman, snowBuilderKey, snowBuiltAtKey);
        if (marker == null || !markerIsCurrent(marker)) return;
        Player player = Bukkit.getPlayer(marker.playerId());
        if (player != null && tracking.test(player, BingoTask.SNOW_GOLEM_KILLS_BLAZE)) {
            completion.accept(player, BingoTask.SNOW_GOLEM_KILLS_BLAZE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHappyGhastInteracted(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof HappyGhast ghast)
                || !ghast.isAdult()
                || !isHarness(event.getPlayer().getInventory().getItem(event.getHand()))) {
            return;
        }

        if (!isEmpty(ghast.getEquipment().getItem(EquipmentSlot.BODY))) return;
        clearMarker(ghast, harnessOwnerKey, harnessEquippedAtKey);

        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) return;

        UUID playerId = player.getUniqueId();
        UUID ghastId = ghast.getUniqueId();
        HarnessAttempt attempt = new HarnessAttempt(
                playerId,
                event.getPlayer().getInventory().getItem(event.getHand()).getType(),
                attemptToken(playerId));
        putBounded(harnessAttempts, ghastId, attempt);
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmHarnessEquipped(ghastId, attempt));
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> harnessAttempts.remove(ghastId, attempt), 2L);
    }

    private void confirmHarnessEquipped(UUID ghastId, HarnessAttempt attempt) {
        if (!attempt.equals(harnessAttempts.get(ghastId))) return;
        Player player = Bukkit.getPlayer(attempt.playerId());
        Entity entity = Bukkit.getEntity(ghastId);
        if (player == null
                || !(entity instanceof HappyGhast ghast)
                || !ghast.isValid()
                || ghast.isDead()
                || !ghast.isAdult()
                || ghast.getEquipment().getItem(EquipmentSlot.BODY).getType()
                        != attempt.harnessType()
                || !isHarness(ghast.getEquipment().getItem(EquipmentSlot.BODY))
                || !isCurrent(attempt.playerId(), attempt.token())
                || !tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) {
            return;
        }

        setMarker(
                ghast,
                harnessOwnerKey,
                harnessEquippedAtKey,
                attempt.playerId(),
                System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBoatLeashed(PlayerLeashEntityEvent event) {
        if (!(event.getEntity() instanceof Boat boat)
                || !(event.getLeashHolder() instanceof HappyGhast ghast)
                || !ghast.isAdult()
                || !isHarness(ghast.getEquipment().getItem(EquipmentSlot.BODY))) {
            return;
        }

        Player player = event.getPlayer();
        PersistentMarker marker = markerFrom(ghast, harnessOwnerKey, harnessEquippedAtKey);
        if (marker == null
                || !marker.playerId().equals(player.getUniqueId())
                || !markerIsCurrent(marker)
                || !tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) {
            return;
        }

        UUID enemyId = liveEnemyPassenger(boat);
        if (enemyId == null) return;

        UUID playerId = player.getUniqueId();
        UUID boatId = boat.getUniqueId();
        UUID ghastId = ghast.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        Bukkit.getScheduler().runTask(
                plugin,
                () -> confirmBoatLeashed(playerId, boatId, ghastId, enemyId, marker, token));
    }

    private void confirmBoatLeashed(
            UUID playerId,
            UUID boatId,
            UUID ghastId,
            UUID enemyId,
            PersistentMarker expectedMarker,
            AttemptToken token) {
        Player player = Bukkit.getPlayer(playerId);
        Entity boatEntity = Bukkit.getEntity(boatId);
        Entity ghastEntity = Bukkit.getEntity(ghastId);
        if (player == null
                || !(boatEntity instanceof Boat boat)
                || !(ghastEntity instanceof HappyGhast ghast)
                || !boat.isValid()
                || boat.isDead()
                || !boat.isLeashed()
                || !ghast.isValid()
                || ghast.isDead()
                || !ghast.isAdult()
                || !isHarness(ghast.getEquipment().getItem(EquipmentSlot.BODY))
                || !expectedMarker.equals(markerFrom(ghast, harnessOwnerKey, harnessEquippedAtKey))
                || !markerIsCurrent(expectedMarker)
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) {
            return;
        }

        Entity leashHolder;
        try {
            leashHolder = boat.getLeashHolder();
        } catch (IllegalStateException ignored) {
            return;
        }
        if (!ghastId.equals(leashHolder.getUniqueId())) return;

        boolean sameEnemyAboard = boat.getPassengers().stream()
                .anyMatch(passenger -> passenger.getUniqueId().equals(enemyId)
                        && passenger instanceof Enemy
                        && passenger.isValid()
                        && !passenger.isDead());
        if (sameEnemyAboard) {
            completion.accept(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT);
        }
    }

    private static UUID liveEnemyPassenger(Boat boat) {
        return boat.getPassengers().stream()
                .filter(Enemy.class::isInstance)
                .filter(Entity::isValid)
                .filter(passenger -> !passenger.isDead())
                .map(Entity::getUniqueId)
                .findFirst()
                .orElse(null);
    }

    private static boolean isHarness(ItemStack item) {
        return !isEmpty(item) && Tag.ITEMS_HARNESSES.isTagged(item.getType());
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty() || item.getType().isAir();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        EntityEquipmentChangedEvent.EquipmentChange bodyChange =
                event.getEquipmentChanges().get(EquipmentSlot.BODY);
        if (bodyChange != null && event.getEntity() instanceof HappyGhast ghast) {
            HarnessAttempt pending = harnessAttempts.get(ghast.getUniqueId());
            boolean expectedDirectEquip = isHarness(bodyChange.newItem())
                    && pending != null
                    && bodyChange.newItem().getType() == pending.harnessType();
            PersistentMarker existing = markerFrom(ghast, harnessOwnerKey, harnessEquippedAtKey);
            boolean persistedHarnessLoaded = isEmpty(bodyChange.oldItem())
                    && isHarness(bodyChange.newItem())
                    && existing != null
                    && markerIsCurrent(existing);
            if (!expectedDirectEquip && !persistedHarnessLoaded) {
                clearMarker(ghast, harnessOwnerKey, harnessEquippedAtKey);
                harnessAttempts.remove(ghast.getUniqueId());
            }
        }
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        playerResetAtMillis.put(playerId, System.currentTimeMillis());
        snowBuildAttempts.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        harnessAttempts.values().removeIf(attempt -> attempt.playerId().equals(playerId));
    }

    @Override
    public void clear() {
        detectorGeneration++;
        clearedAtMillis = System.currentTimeMillis();
        snowBuildAttempts.clear();
        harnessAttempts.clear();
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
            Entity entity, NamespacedKey ownerKey, NamespacedKey timestampKey) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String owner = data.get(ownerKey, PersistentDataType.STRING);
        Long timestamp = data.get(timestampKey, PersistentDataType.LONG);
        if (owner == null || timestamp == null) return null;
        try {
            return new PersistentMarker(UUID.fromString(owner), timestamp);
        } catch (IllegalArgumentException ignored) {
            clearMarker(entity, ownerKey, timestampKey);
            return null;
        }
    }

    private boolean markerIsCurrent(PersistentMarker marker) {
        long now = System.currentTimeMillis();
        long resetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L);
        return marker.timestamp() > clearedAtMillis
                && marker.timestamp() > resetAt
                && marker.timestamp() <= now
                && now - marker.timestamp() <= MAX_PERSISTENT_ATTRIBUTION_MILLIS;
    }

    private static void setMarker(
            Entity entity,
            NamespacedKey ownerKey,
            NamespacedKey timestampKey,
            UUID playerId,
            long timestamp) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(ownerKey, PersistentDataType.STRING, playerId.toString());
        data.set(timestampKey, PersistentDataType.LONG, timestamp);
    }

    private static void clearMarker(
            Entity entity, NamespacedKey ownerKey, NamespacedKey timestampKey) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.remove(ownerKey);
        data.remove(timestampKey);
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

    record BlockPoint(int x, int y, int z) {
        static BlockPoint from(BlockState state) {
            return new BlockPoint(state.getX(), state.getY(), state.getZ());
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        static BlockKey from(Location location) {
            return new BlockKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ());
        }
    }

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record BuildAttempt(UUID playerId, int tick, AttemptToken token) {}

    private record HarnessAttempt(UUID playerId, Material harnessType, AttemptToken token) {}

    private record PersistentMarker(UUID playerId, long timestamp) {}

}
