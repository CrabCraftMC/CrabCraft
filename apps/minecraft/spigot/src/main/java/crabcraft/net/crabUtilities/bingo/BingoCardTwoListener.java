package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import io.papermc.paper.event.block.TargetHitEvent;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.SideChaining;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Shelf;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Bogged;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Turtle;
import org.bukkit.entity.Villager;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BellRingEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Event-driven detectors for Bingo #2. */
public final class BingoCardTwoListener implements BingoDetector {
    private static final int MAX_OWNED_BLOCKS_PER_PLAYER = 2_048;
    private static final int MAX_TRANSIENT_ENTRIES = 4_096;
    private static final int SHORT_CORRELATION_TICKS = 3;
    private static final int PROJECTILE_RETENTION_TICKS = 20 * 60 * 5;
    private static final int ITEM_RETENTION_TICKS = 20 * 60 * 10;
    private static final int PIGLIN_RETENTION_TICKS = 20 * 30;
    private static final double TEN_BLOCKS_SQUARED = 100.0;
    private static final double PARROT_DANCE_DISTANCE_SQUARED = 3.46 * 3.46;
    private static final BlockFace[] FACES = {
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST
    };
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final Set<EquipmentSlot> GOLD_ARMOUR_SLOTS =
            EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private final JavaPlugin plugin;
    private final BiPredicate<Player, BingoTask> tracking;
    private final BiConsumer<Player, BingoTask> completion;

    private final Map<UUID, ProjectileShot> projectileShots = new HashMap<>();
    private final Map<BellAttemptKey, Integer> bellAttempts = new HashMap<>();
    private final Map<BlockKey, UUID> berryBushOwners = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> berryBushesByPlayer = new HashMap<>();
    private final Map<UUID, TimedPlayer> berryDamage = new HashMap<>();
    private final Map<BlockKey, UUID> fireOwners = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> firesByPlayer = new HashMap<>();
    private final Map<UUID, MinecartContact> minecartContacts = new HashMap<>();
    private final Map<UUID, PrimedMinecart> primedMinecarts = new HashMap<>();
    private final Map<UUID, ScuteProvenance> turtleScutes = new HashMap<>();
    private final Map<UUID, ShelfAttempt> shelfAttempts = new HashMap<>();
    private final Map<UUID, PigShearAttempt> pigShearAttempts = new HashMap<>();
    private final Map<UUID, TimedPlayer> pendingSelfArrowTotems = new HashMap<>();
    private final Map<UUID, DroppedArmour> droppedArmour = new HashMap<>();
    private final Map<UUID, EnumMap<EquipmentSlot, PiglinPickup>> piglinPickups = new HashMap<>();
    private final Map<UUID, EnumMap<EquipmentSlot, UUID>> piglinArmourOwners = new HashMap<>();
    private final Map<UUID, UUID> leashedBeeOwners = new HashMap<>();
    private final Map<BlockKey, UUID> pressurePlateOwners = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> pressurePlatesByPlayer = new HashMap<>();
    private final Map<BlockKey, TimedPlayer> pressurePlateTriggers = new HashMap<>();
    private final Map<UUID, Long> playerGenerations = new HashMap<>();
    private long detectorGeneration;
    private int lastPruneTick = Integer.MIN_VALUE;

    public BingoCardTwoListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySheared(PlayerShearEntityEvent event) {
        if (event.getEntity() instanceof Bogged
                && tracking.test(event.getPlayer(), BingoTask.SHEAR_BOGGED)) {
            completion.accept(event.getPlayer(), BingoTask.SHEAR_BOGGED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunched(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)
                || (!tracking.test(player, BingoTask.RING_BELL_PROJECTILE)
                        && !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)
                        && !tracking.test(player, BingoTask.SELF_ARROW_TOTEM))) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);
        putBounded(
                projectileShots,
                projectile.getUniqueId(),
                new ProjectileShot(player.getUniqueId(), projectile.getLocation().clone(), tick));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Block block = event.getHitBlock();
        if (block == null || block.getType() != Material.BELL) return;
        ProjectileShot shot = projectileShots.remove(event.getEntity().getUniqueId());
        if (shot == null
                || !sameWorld(shot.origin(), block.getLocation())
                || shot.origin().distanceSquared(block.getLocation().toCenterLocation()) < TEN_BLOCKS_SQUARED) {
            return;
        }
        BellAttemptKey key = new BellAttemptKey(BlockKey.from(block), shot.playerId());
        int tick = Bukkit.getCurrentTick();
        putBounded(bellAttempts, key, tick);
        Bukkit.getScheduler().runTask(plugin, () -> bellAttempts.remove(key, tick));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBellRung(BellRingEvent event) {
        int tick = Bukkit.getCurrentTick();
        if (event.getEntity() instanceof Player player) {
            BellAttemptKey key = new BellAttemptKey(BlockKey.from(event.getBlock()), player.getUniqueId());
            Integer attemptTick = bellAttempts.remove(key);
            if (attemptTick != null
                    && isFresh(attemptTick, tick, 1)
                    && tracking.test(player, BingoTask.RING_BELL_PROJECTILE)) {
                completion.accept(player, BingoTask.RING_BELL_PROJECTILE);
            }
            return;
        }

        if (event.getEntity() != null) return;
        for (BlockFace face : HORIZONTAL_FACES) {
            Block plate = event.getBlock().getRelative(face);
            BlockKey plateKey = BlockKey.from(plate);
            TimedPlayer trigger = pressurePlateTriggers.remove(plateKey);
            if (trigger == null
                    || trigger.tick() != tick
                    || !Tag.PRESSURE_PLATES.isTagged(plate.getType())
                    || !isPowered(plate)) {
                continue;
            }
            Player player = Bukkit.getPlayer(trigger.playerId());
            if (player != null && tracking.test(player, BingoTask.CREEPER_RINGS_BELL)) {
                completion.accept(player, BingoTask.CREEPER_RINGS_BELL);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTargetHit(TargetHitEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileShot shot = projectileShots.remove(projectile.getUniqueId());
        Block target = event.getHitBlock();
        if (shot == null
                || target == null
                || !(projectile instanceof AbstractArrow)
                || projectile instanceof Trident
                || event.getSignalStrength() <= 0
                || !sameWorld(shot.origin(), target.getLocation())
                || shot.origin().distanceSquared(target.getLocation().toCenterLocation()) < TEN_BLOCKS_SQUARED) {
            return;
        }
        Player player = Bukkit.getPlayer(shot.playerId());
        if (player == null || !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)) return;

        BlockKey targetKey = BlockKey.from(target);
        Set<DoorCandidate> doors = new LinkedHashSet<>();
        for (BlockFace face : FACES) {
            Block adjacent = canonicalDoor(target.getRelative(face));
            if (adjacent != null
                    && adjacent.getBlockData() instanceof Door door
                    && !door.isOpen()
                    && !door.isPowered()) {
                doors.add(new DoorCandidate(BlockKey.from(adjacent), adjacent.getType()));
            }
        }
        if (doors.isEmpty()) return;
        AttemptToken token = attemptToken(shot.playerId());
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmTargetDoor(shot.playerId(), token, targetKey, doors));
    }

    private void confirmTargetDoor(
            UUID playerId, AttemptToken token, BlockKey targetKey, Set<DoorCandidate> doors) {
        Player player = Bukkit.getPlayer(playerId);
        Block target = blockAt(targetKey);
        if (player == null
                || !isCurrent(playerId, token)
                || target == null
                || target.getType() != Material.TARGET
                || !isPowered(target)
                || !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)) {
            return;
        }
        for (DoorCandidate candidate : doors) {
            Block block = blockAt(candidate.block());
            if (block != null
                    && block.getType() == candidate.material()
                    && block.getBlockData() instanceof Door door
                    && door.isOpen()
                    && door.isPowered()) {
                completion.accept(player, BingoTask.TARGET_OPENS_DOOR);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        Block block = event.getBlockPlaced();
        BlockKey key = BlockKey.from(block);
        Player player = event.getPlayer();
        removeOwnedBlock(key, berryBushOwners, berryBushesByPlayer);
        // Paper follows a successful flint/fire-charge BlockIgniteEvent with a captured
        // BlockPlaceEvent. Keep only the same player's newly recorded fire attribution.
        if (!shouldRetainFireOwnerAfterPlaceEvent(
                Tag.FIRE.isTagged(block.getType()),
                player.getUniqueId(),
                fireOwners.get(key))) {
            removeOwnedBlock(key, fireOwners, firesByPlayer);
        }
        removeOwnedBlock(key, pressurePlateOwners, pressurePlatesByPlayer);
        pressurePlateTriggers.remove(key);
        if (block.getType() == Material.SWEET_BERRY_BUSH
                && tracking.test(player, BingoTask.BERRY_BUSH_KILL)) {
            setOwnedBlock(
                    key,
                    player.getUniqueId(),
                    berryBushOwners,
                    berryBushesByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER);
        }
        if (Tag.PRESSURE_PLATES.isTagged(block.getType())
                && tracking.test(player, BingoTask.CREEPER_RINGS_BELL)) {
            setOwnedBlock(
                    key,
                    player.getUniqueId(),
                    pressurePlateOwners,
                    pressurePlatesByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER);
        }

        if (block.getType() == Material.WET_SPONGE
                && event.getItemInHand().getType() == Material.WET_SPONGE
                && block.getWorld().getEnvironment() == World.Environment.NETHER
                && tracking.test(player, BingoTask.DRY_SPONGE_NETHER)) {
            UUID playerId = player.getUniqueId();
            AttemptToken token = attemptToken(playerId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Block current = blockAt(key);
                Player currentPlayer = Bukkit.getPlayer(playerId);
                if (current != null
                        && current.getType() == Material.SPONGE
                        && currentPlayer != null
                        && isCurrent(playerId, token)
                        && tracking.test(currentPlayer, BingoTask.DRY_SPONGE_NETHER)) {
                    completion.accept(currentPlayer, BingoTask.DRY_SPONGE_NETHER);
                }
            });
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFaded(BlockFadeEvent event) {
        removeOwnedBlock(BlockKey.from(event.getBlock()), fireOwners, firesByPlayer);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBerryBushDamage(EntityDamageByBlockEvent event) {
        Block damager = event.getDamager();
        if (!(event.getEntity() instanceof Enemy)
                || damager == null
                || !DamageType.SWEET_BERRY_BUSH.equals(event.getDamageSource().getDamageType())
                || event.getFinalDamage() <= 0.0) {
            return;
        }
        berryDamage.remove(event.getEntity().getUniqueId());
        UUID owner = berryBushOwners.get(BlockKey.from(damager));
        Player ownerPlayer = owner == null ? null : Bukkit.getPlayer(owner);
        if (ownerPlayer != null && tracking.test(ownerPlayer, BingoTask.BERRY_BUSH_KILL)) {
            putBounded(
                    berryDamage,
                    event.getEntity().getUniqueId(),
                    new TimedPlayer(owner, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        TimedPlayer attempt = berryDamage.remove(event.getEntity().getUniqueId());
        if (attempt == null
                || !isFresh(attempt.tick(), Bukkit.getCurrentTick(), SHORT_CORRELATION_TICKS)
                || !DamageType.SWEET_BERRY_BUSH.equals(event.getDamageSource().getDamageType())) {
            return;
        }
        Player owner = Bukkit.getPlayer(attempt.playerId());
        if (owner != null && tracking.test(owner, BingoTask.BERRY_BUSH_KILL)) {
            completion.accept(owner, BingoTask.BERRY_BUSH_KILL);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJukeboxInteracted(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !event.getAction().isRightClick()
                || !(event.getClickedBlock() != null
                        && event.getClickedBlock().getState() instanceof Jukebox jukebox)
                || jukebox.hasRecord()) {
            return;
        }
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (item == null
                || !item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE)
                || !tracking.test(player, BingoTask.FIVE_PARROTS_DANCE)) {
            return;
        }
        BlockKey key = BlockKey.from(event.getClickedBlock());
        UUID playerId = player.getUniqueId();
        AttemptToken token = attemptToken(playerId);
        ItemStack inserted = singleItem(item);
        Bukkit.getScheduler().runTask(
                plugin, () -> confirmParrotsDancing(playerId, token, key, inserted));
    }

    private void confirmParrotsDancing(
            UUID playerId, AttemptToken token, BlockKey key, ItemStack inserted) {
        Player player = Bukkit.getPlayer(playerId);
        Block block = blockAt(key);
        if (player == null
                || block == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FIVE_PARROTS_DANCE)
                || !(block.getState() instanceof Jukebox jukebox)
                || !jukebox.isPlaying()
                || !jukebox.getRecord().isSimilar(inserted)) {
            return;
        }
        Location centre = block.getLocation().toCenterLocation();
        Set<Parrot.Variant> variants = EnumSet.noneOf(Parrot.Variant.class);
        block.getWorld().getNearbyEntities(
                        org.bukkit.util.BoundingBox.of(block).expand(3.0)).stream()
                .filter(Parrot.class::isInstance)
                .map(Parrot.class::cast)
                .filter(parrot -> parrot.getLocation().distanceSquared(centre) < PARROT_DANCE_DISTANCE_SQUARED)
                .map(Parrot::getVariant)
                .forEach(variants::add);
        if (variants.size() == Parrot.Variant.values().length) {
            completion.accept(player, BingoTask.FIVE_PARROTS_DANCE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockIgnited(BlockIgniteEvent event) {
        BlockKey key = BlockKey.from(event.getBlock());
        removeOwnedBlock(key, fireOwners, firesByPlayer);
        if ((event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                        && event.getCause() != BlockIgniteEvent.IgniteCause.FIREBALL)
                || !(event.getIgnitingEntity() instanceof Player player)
                || !tracking.test(player, BingoTask.DETONATE_TNT_MINECART)) {
            return;
        }
        setOwnedBlock(
                key,
                player.getUniqueId(),
                fireOwners,
                firesByPlayer,
                MAX_OWNED_BLOCKS_PER_PLAYER);
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block current = blockAt(key);
            if ((current == null || !Tag.FIRE.isTagged(current.getType()))
                    && playerId.equals(fireOwners.get(key))) {
                removeOwnedBlock(key, fireOwners, firesByPlayer);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityInsideBlock(EntityInsideBlockEvent event) {
        Block block = event.getBlock();
        BlockKey key = BlockKey.from(block);
        int tick = Bukkit.getCurrentTick();
        if (event.getEntity() instanceof ExplosiveMinecart minecart
                && !minecart.isIgnited()
                && Tag.FIRE.isTagged(block.getType())) {
            UUID owner = fireOwners.get(key);
            Player player = owner == null ? null : Bukkit.getPlayer(owner);
            if (player != null && tracking.test(player, BingoTask.DETONATE_TNT_MINECART)) {
                putBounded(
                        minecartContacts,
                        minecart.getUniqueId(),
                        new MinecartContact(owner, key, tick));
            }
        }
        if (event.getEntity() instanceof Creeper
                && Tag.PRESSURE_PLATES.isTagged(block.getType())
                && !isPowered(block)) {
            UUID owner = pressurePlateOwners.get(key);
            if (owner != null) {
                TimedPlayer trigger = new TimedPlayer(owner, tick);
                putBounded(pressurePlateTriggers, key, trigger);
                Bukkit.getScheduler().runTask(
                        plugin, () -> pressurePlateTriggers.remove(key, trigger));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecartDestroyed(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof ExplosiveMinecart minecart)
                || !DamageType.IN_FIRE.equals(event.getDamageSource().getDamageType())) {
            return;
        }
        MinecartContact contact = minecartContacts.remove(minecart.getUniqueId());
        if (contact == null
                || contact.tick() != Bukkit.getCurrentTick()
                || !contact.playerId().equals(fireOwners.get(contact.fire()))) {
            return;
        }
        UUID minecartId = minecart.getUniqueId();
        PrimedMinecart primed = new PrimedMinecart(
                contact.playerId(), Bukkit.getCurrentTick(), 20 * 60);
        putBounded(primedMinecarts, minecartId, primed);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (primedMinecarts.get(minecartId) != primed) return;
            Entity current = Bukkit.getEntity(minecartId);
            if (!(current instanceof ExplosiveMinecart currentMinecart) || !currentMinecart.isIgnited()) {
                primedMinecarts.remove(minecartId, primed);
                return;
            }
            int maximumAge = Math.min(Math.max(currentMinecart.getFuseTicks() + 5, 45), 20 * 60);
            PrimedMinecart confirmed = new PrimedMinecart(
                    primed.playerId(), Bukkit.getCurrentTick(), maximumAge);
            if (!primedMinecarts.replace(minecartId, primed, confirmed)) return;
            Bukkit.getScheduler().runTaskLater(
                    plugin, () -> primedMinecarts.remove(minecartId, confirmed), maximumAge);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMinecartExplosionPrimed(ExplosionPrimeEvent event) {
        if (!(event.getEntity() instanceof ExplosiveMinecart)) return;
        UUID minecartId = event.getEntity().getUniqueId();
        PrimedMinecart primed = primedMinecarts.get(minecartId);
        if (primed == null) return;
        if (event.isCancelled()) primedMinecarts.remove(minecartId, primed);
        Bukkit.getScheduler().runTask(
                plugin, () -> primedMinecarts.remove(minecartId, primed));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerged(ItemMergeEvent event) {
        ScuteProvenance provenance = turtleScutes.get(event.getEntity().getUniqueId());
        ScuteProvenance target = turtleScutes.get(event.getTarget().getUniqueId());
        if (provenance != null && (target == null || target.isConsumed())) {
            putBounded(turtleScutes, event.getTarget().getUniqueId(), provenance);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDroppedItem(EntityDropItemEvent event) {
        Item item = event.getItemDrop();
        int tick = Bukkit.getCurrentTick();
        if (!event.isCancelled()
                && event.getEntity() instanceof Turtle turtle
                && turtle.isAdult()
                && turtle.getAge() == 0
                && item.getItemStack().getType() == Material.TURTLE_SCUTE) {
            putBounded(turtleScutes, item.getUniqueId(), new ScuteProvenance(tick));
        }

        if (event.getEntity() instanceof Pig pig
                && item.getItemStack().getType() == Material.SADDLE) {
            PigShearAttempt attempt = pigShearAttempts.get(pig.getUniqueId());
            if (attempt != null && isFresh(attempt.tick(), tick, 1)) {
                PigShearAttempt confirmed = new PigShearAttempt(attempt.playerId(), attempt.tick(), true);
                if (!pigShearAttempts.replace(pig.getUniqueId(), attempt, confirmed)) return;
                UUID pigId = pig.getUniqueId();
                Bukkit.getScheduler().runTask(plugin, () -> confirmPigUnsaddled(pigId, confirmed));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickedUp(EntityPickupItemEvent event) {
        Item item = event.getItem();
        ScuteProvenance scute = turtleScutes.remove(item.getUniqueId());
        if (scute != null && scute.consume() && event.getEntity() instanceof Player player) {
            if (tracking.test(player, BingoTask.COLLECT_TURTLE_SCUTE)) {
                completion.accept(player, BingoTask.COLLECT_TURTLE_SCUTE);
            }
        }

        if (event.getEntity() instanceof Piglin piglin) {
            DroppedArmour armour = droppedArmour.remove(item.getUniqueId());
            if (armour == null) return;
            EnumMap<EquipmentSlot, PiglinPickup> pickups = piglinPickupsFor(piglin.getUniqueId());
            pickups.put(
                    armour.slot(),
                    new PiglinPickup(
                            armour.playerId(), armour.item(), Bukkit.getCurrentTick(), false, -1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickedUp(InventoryPickupItemEvent event) {
        ScuteProvenance scute = turtleScutes.remove(event.getItem().getUniqueId());
        if (scute != null) scute.consume();
        droppedArmour.remove(event.getItem().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShelfInteracted(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !event.getAction().isRightClick()
                || event.useInteractedBlock() == Event.Result.DENY
                || event.getClickedBlock() == null
                || !(event.getClickedBlock().getBlockData() instanceof Shelf shelf)
                || !shelf.isPowered()
                || event.getBlockFace() != shelf.getFacing()) {
            return;
        }
        Player player = event.getPlayer();
        if (!tracking.test(player, BingoTask.SHELF_HOTBAR_SWAP)) return;
        List<BlockKey> shelves = connectedShelves(event.getClickedBlock(), shelf.getFacing());
        if (shelves.size() != 3) return;
        ItemStack[] previousShelves = shelfContents(shelves, shelf.getFacing());
        if (previousShelves == null) return;
        ItemStack[] previousHotbar = hotbarContents(player);
        UUID playerId = player.getUniqueId();
        ShelfAttempt attempt = new ShelfAttempt(
                Bukkit.getCurrentTick(), List.copyOf(shelves), shelf.getFacing(), previousShelves, previousHotbar);
        shelfAttempts.put(playerId, attempt);
        Bukkit.getScheduler().runTask(plugin, () -> confirmShelfSwap(playerId, attempt));
    }

    private void confirmShelfSwap(UUID playerId, ShelfAttempt attempt) {
        if (shelfAttempts.get(playerId) != attempt) return;
        shelfAttempts.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        ItemStack[] shelves = shelfContents(attempt.shelves(), attempt.facing());
        ItemStack[] hotbar = player == null ? null : hotbarContents(player);
        if (player == null
                || shelves == null
                || hotbar == null
                || !tracking.test(player, BingoTask.SHELF_HOTBAR_SWAP)
                || !Arrays.equals(hotbar, attempt.previousShelves())
                || !Arrays.equals(shelves, attempt.previousHotbar())
                || Arrays.equals(hotbar, attempt.previousHotbar())) {
            return;
        }
        completion.accept(player, BingoTask.SHELF_HOTBAR_SWAP);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPigInteracted(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Pig pig)
                || !pig.hasSaddle()
                || event.getPlayer().getInventory().getItem(event.getHand()).getType() != Material.SHEARS
                || !tracking.test(event.getPlayer(), BingoTask.REMOVE_PIG_SADDLE)) {
            return;
        }
        UUID pigId = pig.getUniqueId();
        PigShearAttempt attempt = new PigShearAttempt(
                event.getPlayer().getUniqueId(), Bukkit.getCurrentTick(), false);
        putBounded(pigShearAttempts, pigId, attempt);
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> pigShearAttempts.remove(pigId, attempt), SHORT_CORRELATION_TICKS + 1L);
    }

    private void confirmPigUnsaddled(UUID pigId, PigShearAttempt attempt) {
        if (!attempt.dropObserved() || !pigShearAttempts.remove(pigId, attempt)) return;
        Entity entity = Bukkit.getEntity(pigId);
        Player player = Bukkit.getPlayer(attempt.playerId());
        if (entity instanceof Pig pig
                && pig.isValid()
                && !pig.isDead()
                && !pig.hasSaddle()
                && player != null
                && tracking.test(player, BingoTask.REMOVE_PIG_SADDLE)) {
            completion.accept(player, BingoTask.REMOVE_PIG_SADDLE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTrade(PlayerTradeEvent event) {
        if (!(event.getMerchant() instanceof Villager villager)
                || villager.getProfession() != Villager.Profession.CARTOGRAPHER) {
            return;
        }
        MerchantRecipe trade = event.getTrade();
        ItemStack result = trade.getResult();
        io.papermc.paper.datacomponent.item.MapDecorations decorations =
                result.getData(DataComponentTypes.MAP_DECORATIONS);
        if (result.getType() == Material.FILLED_MAP
                && decorations != null
                && !decorations.decorations().isEmpty()
                && trade.getIngredients().stream().anyMatch(item -> item.getType() == Material.COMPASS)
                && tracking.test(event.getPlayer(), BingoTask.EXPLORER_MAP_TRADE)) {
            completion.accept(event.getPlayer(), BingoTask.EXPLORER_MAP_TRADE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageByEntityEvent event) {
        int tick = Bukkit.getCurrentTick();
        if (event.getDamager() instanceof AbstractArrow arrow
                && !(arrow instanceof Trident)
                && event.getEntity() instanceof Player player) {
            ProjectileShot shot = projectileShots.get(arrow.getUniqueId());
            if (shot != null
                    && shot.playerId().equals(player.getUniqueId())
                    && event.getFinalDamage() >= player.getHealth()
                    && tracking.test(player, BingoTask.SELF_ARROW_TOTEM)) {
                TimedPlayer attempt = new TimedPlayer(shot.playerId(), tick);
                putBounded(pendingSelfArrowTotems, player.getUniqueId(), attempt);
                Bukkit.getScheduler().runTask(
                        plugin, () -> pendingSelfArrowTotems.remove(player.getUniqueId(), attempt));
            }
        }

        if (event.getDamager() instanceof Bee bee
                && event.getEntity() instanceof Mob
                && DamageType.STING.equals(event.getDamageSource().getDamageType())
                && bee.isLeashed()) {
            UUID ownerId = leashedBeeOwners.get(bee.getUniqueId());
            if (ownerId != null) {
                UUID beeId = bee.getUniqueId();
                AttemptToken token = attemptToken(ownerId);
                Bukkit.getScheduler().runTask(
                        plugin, () -> confirmBeeStung(beeId, ownerId, token));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityResurrected(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        TimedPlayer attempt = pendingSelfArrowTotems.remove(player.getUniqueId());
        if (attempt != null
                && !event.isCancelled()
                && event.getHand() != null
                && attempt.tick() == Bukkit.getCurrentTick()
                && tracking.test(player, BingoTask.SELF_ARROW_TOTEM)) {
            completion.accept(player, BingoTask.SELF_ARROW_TOTEM);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmourDropped(PlayerDropItemEvent event) {
        EquipmentSlot slot = goldenArmourSlot(event.getItemDrop().getItemStack().getType());
        if (slot == null || !tracking.test(event.getPlayer(), BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)) return;
        int tick = Bukkit.getCurrentTick();
        pruneTransientStateIfDue(tick);
        putBounded(
                droppedArmour,
                event.getItemDrop().getUniqueId(),
                new DroppedArmour(
                        event.getPlayer().getUniqueId(),
                        slot,
                        singleItem(event.getItemDrop().getItemStack()),
                        tick));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Piglin piglin)) return;
        UUID piglinId = piglin.getUniqueId();
        EnumMap<EquipmentSlot, PiglinPickup> pickups = piglinPickups.get(piglinId);
        EntityEquipmentChangedEvent.EquipmentChange offhand =
                event.getEquipmentChanges().get(EquipmentSlot.OFF_HAND);
        if (pickups != null && offhand != null) {
            int tick = Bukkit.getCurrentTick();
            for (Map.Entry<EquipmentSlot, PiglinPickup> entry : pickups.entrySet()) {
                PiglinPickup pickup = entry.getValue();
                boolean observed = pickup.observedInOffhand()
                        || offhand.newItem().isSimilar(pickup.item());
                int releasedTick = pickup.releasedTick();
                if (observed
                        && transitionedAwayFromTrackedItem(
                                pickup.item(),
                                offhand.oldItem(),
                                offhand.newItem(),
                                ItemStack::isSimilar)) {
                    releasedTick = tick;
                }
                if (observed != pickup.observedInOffhand() || releasedTick != pickup.releasedTick()) {
                    entry.setValue(new PiglinPickup(
                            pickup.playerId(), pickup.item(), pickup.tick(), observed, releasedTick));
                }
            }
        }
        for (Map.Entry<EquipmentSlot, EntityEquipmentChangedEvent.EquipmentChange> change
                : event.getEquipmentChanges().entrySet()) {
            EquipmentSlot slot = change.getKey();
            if (!GOLD_ARMOUR_SLOTS.contains(slot)) continue;
            tryRecordPiglinArmour(piglin, slot, change.getValue().newItem());
        }
    }

    private void tryRecordPiglinArmour(Piglin piglin, EquipmentSlot slot, ItemStack equipped) {
        EnumMap<EquipmentSlot, PiglinPickup> pickups = piglinPickups.get(piglin.getUniqueId());
        PiglinPickup pickup = pickups == null ? null : pickups.get(slot);
        EnumMap<EquipmentSlot, UUID> owners = piglinArmourOwnersFor(piglin.getUniqueId());
        int tick = Bukkit.getCurrentTick();
        if (pickup != null
                && canAttributePiglinArmour(
                        isFresh(pickup.tick(), tick, PIGLIN_RETENTION_TICKS),
                        pickup.observedInOffhand(),
                        pickup.releasedTick(),
                        tick,
                        goldenArmourSlot(equipped.getType()) == slot,
                        equipped.isSimilar(pickup.item()))) {
            owners.put(slot, pickup.playerId());
            pickups.remove(slot);
            if (pickups.isEmpty()) piglinPickups.remove(piglin.getUniqueId());
            Player player = Bukkit.getPlayer(pickup.playerId());
            if (player != null
                    && hasFullGoldenArmourFrom(piglin, owners, pickup.playerId())
                    && tracking.test(player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)) {
                piglinPickups.remove(piglin.getUniqueId());
                piglinArmourOwners.remove(piglin.getUniqueId());
                completion.accept(player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR);
            }
            return;
        }
        if (pickup != null
                && !isFresh(pickup.tick(), tick, PIGLIN_RETENTION_TICKS)) {
            pickups.remove(slot);
            if (pickups.isEmpty()) piglinPickups.remove(piglin.getUniqueId());
        }
        owners.remove(slot);
        if (owners.isEmpty()) piglinArmourOwners.remove(piglin.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityLeashed(PlayerLeashEntityEvent event) {
        if (event.getEntity() instanceof Bee
                && tracking.test(event.getPlayer(), BingoTask.LEASHED_BEE_STING)) {
            putBounded(
                    leashedBeeOwners,
                    event.getEntity().getUniqueId(),
                    event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityUnleashed(PlayerUnleashEntityEvent event) {
        leashedBeeOwners.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityUnleashed(EntityUnleashEvent event) {
        leashedBeeOwners.remove(event.getEntity().getUniqueId());
    }

    private void confirmBeeStung(UUID beeId, UUID ownerId, AttemptToken token) {
        Entity entity = Bukkit.getEntity(beeId);
        Player owner = Bukkit.getPlayer(ownerId);
        if (entity instanceof Bee bee
                && bee.hasStung()
                && owner != null
                && isCurrent(ownerId, token)
                && tracking.test(owner, BingoTask.LEASHED_BEE_STING)) {
            completion.accept(owner, BingoTask.LEASHED_BEE_STING);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStatueMined(BlockDropItemEvent event) {
        if (Tag.COPPER_GOLEM_STATUES.isTagged(event.getBlockState().getType())
                && event.getItems().stream()
                        .map(Item::getItemStack)
                        .map(ItemStack::getType)
                        .anyMatch(Tag.ITEMS_COPPER_GOLEM_STATUES::isTagged)
                && tracking.test(event.getPlayer(), BingoTask.MINE_COPPER_GOLEM_STATUE)) {
            completion.accept(event.getPlayer(), BingoTask.MINE_COPPER_GOLEM_STATUE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (event.getCause() == EntityRemoveEvent.Cause.EXPLODE
                && event.getEntity() instanceof ExplosiveMinecart) {
            PrimedMinecart primed = primedMinecarts.remove(id);
            Player owner = primed == null ? null : Bukkit.getPlayer(primed.playerId());
            if (owner != null
                    && isFresh(primed.tick(), Bukkit.getCurrentTick(), primed.maximumAge())
                    && tracking.test(owner, BingoTask.DETONATE_TNT_MINECART)) {
                completion.accept(owner, BingoTask.DETONATE_TNT_MINECART);
            }
        } else {
            primedMinecarts.remove(id);
        }
        projectileShots.remove(id);
        berryDamage.remove(id);
        minecartContacts.remove(id);
        turtleScutes.remove(id);
        pigShearAttempts.remove(id);
        droppedArmour.remove(id);
        piglinPickups.remove(id);
        piglinArmourOwners.remove(id);
        leashedBeeOwners.remove(id);
    }

    @Override
    public void resetPlayer(UUID playerId) {
        playerGenerations.merge(playerId, 1L, Long::sum);
        projectileShots.values().removeIf(value -> value.playerId().equals(playerId));
        bellAttempts.keySet().removeIf(value -> value.playerId().equals(playerId));
        removeOwnedBlocks(playerId, berryBushOwners, berryBushesByPlayer);
        berryDamage.values().removeIf(value -> value.playerId().equals(playerId));
        removeOwnedBlocks(playerId, fireOwners, firesByPlayer);
        minecartContacts.values().removeIf(value -> value.playerId().equals(playerId));
        primedMinecarts.values().removeIf(value -> value.playerId().equals(playerId));
        shelfAttempts.remove(playerId);
        pigShearAttempts.values().removeIf(value -> value.playerId().equals(playerId));
        pendingSelfArrowTotems.remove(playerId);
        droppedArmour.values().removeIf(value -> value.playerId().equals(playerId));
        piglinPickups.values().forEach(map -> map.values().removeIf(value -> value.playerId().equals(playerId)));
        piglinPickups.values().removeIf(Map::isEmpty);
        piglinArmourOwners.values().forEach(map -> map.values().removeIf(playerId::equals));
        piglinArmourOwners.values().removeIf(Map::isEmpty);
        leashedBeeOwners.values().removeIf(playerId::equals);
        removeOwnedBlocks(playerId, pressurePlateOwners, pressurePlatesByPlayer);
        pressurePlateTriggers.values().removeIf(value -> value.playerId().equals(playerId));
    }

    @Override
    public void clear() {
        detectorGeneration++;
        playerGenerations.clear();
        lastPruneTick = Integer.MIN_VALUE;
        projectileShots.clear();
        bellAttempts.clear();
        berryBushOwners.clear();
        berryBushesByPlayer.clear();
        berryDamage.clear();
        fireOwners.clear();
        firesByPlayer.clear();
        minecartContacts.clear();
        primedMinecarts.clear();
        turtleScutes.clear();
        shelfAttempts.clear();
        pigShearAttempts.clear();
        pendingSelfArrowTotems.clear();
        droppedArmour.clear();
        piglinPickups.clear();
        piglinArmourOwners.clear();
        leashedBeeOwners.clear();
        pressurePlateOwners.clear();
        pressurePlatesByPlayer.clear();
        pressurePlateTriggers.clear();
    }

    private List<BlockKey> connectedShelves(Block origin, BlockFace facing) {
        BlockFace left = shelfLeftOf(facing);
        if (left == null || !isMatchingShelf(origin, facing)) return List.of();
        List<Block> connected = new ArrayList<>(3);
        connected.add(origin);

        Block cursor = origin;
        for (int distance = 0; distance < 2; distance++) {
            Block candidate = cursor.getRelative(left);
            if (!isMatchingShelf(candidate, facing)) break;
            Shelf shelf = (Shelf) candidate.getBlockData();
            if (shelf.getSideChain() != SideChaining.ChainPart.CENTER
                    && shelf.getSideChain() != SideChaining.ChainPart.LEFT) {
                break;
            }
            connected.add(0, candidate);
            cursor = candidate;
            if (shelf.getSideChain() == SideChaining.ChainPart.LEFT) break;
        }

        cursor = origin;
        for (int distance = 0; distance < 2; distance++) {
            Block candidate = cursor.getRelative(left.getOppositeFace());
            if (!isMatchingShelf(candidate, facing)) break;
            Shelf shelf = (Shelf) candidate.getBlockData();
            if (shelf.getSideChain() != SideChaining.ChainPart.CENTER
                    && shelf.getSideChain() != SideChaining.ChainPart.RIGHT) {
                break;
            }
            connected.add(candidate);
            cursor = candidate;
            if (shelf.getSideChain() == SideChaining.ChainPart.RIGHT) break;
        }

        if (connected.size() != 3) return List.of();
        SideChaining.ChainPart[] expected = {
            SideChaining.ChainPart.LEFT,
            SideChaining.ChainPart.CENTER,
            SideChaining.ChainPart.RIGHT
        };
        for (int index = 0; index < connected.size(); index++) {
            Shelf shelf = (Shelf) connected.get(index).getBlockData();
            if (shelf.getSideChain() != expected[index]) return List.of();
        }
        return connected.stream().map(BlockKey::from).toList();
    }

    private ItemStack[] shelfContents(List<BlockKey> keys, BlockFace facing) {
        List<ItemStack> items = new ArrayList<>(9);
        SideChaining.ChainPart[] expectedParts = {
            SideChaining.ChainPart.LEFT,
            SideChaining.ChainPart.CENTER,
            SideChaining.ChainPart.RIGHT
        };
        for (int blockIndex = 0; blockIndex < keys.size(); blockIndex++) {
            BlockKey key = keys.get(blockIndex);
            Block block = blockAt(key);
            if (block == null
                    || !(block.getBlockData() instanceof Shelf data)
                    || !data.isPowered()
                    || data.getFacing() != facing
                    || data.getSideChain() != expectedParts[blockIndex]
                    || !(block.getState() instanceof org.bukkit.block.Shelf shelf)) {
                return null;
            }
            ItemStack[] contents = shelf.getInventory().getStorageContents();
            if (contents.length != 3) return null;
            for (ItemStack item : contents) items.add(copyOrNull(item));
        }
        return items.toArray(ItemStack[]::new);
    }

    private static ItemStack[] hotbarContents(Player player) {
        ItemStack[] items = new ItemStack[9];
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = copyOrNull(player.getInventory().getItem(slot));
        }
        return items;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    static <T> boolean transitionedAwayFromTrackedItem(
            T tracked, T previous, T current, BiPredicate<T, T> similarity) {
        return similarity.test(previous, tracked) && !similarity.test(current, tracked);
    }

    static boolean canAttributePiglinArmour(
            boolean fresh,
            boolean observedInOffhand,
            int releasedTick,
            int currentTick,
            boolean equippedSlotMatches,
            boolean equippedItemMatches) {
        return fresh
                && observedInOffhand
                && releasedTick >= 0
                && releasedTick == currentTick
                && equippedSlotMatches
                && equippedItemMatches;
    }

    static boolean shouldRetainFireOwnerAfterPlaceEvent(
            boolean placedBlockIsFire, UUID placingPlayerId, UUID recordedFireOwner) {
        return placedBlockIsFire && placingPlayerId.equals(recordedFireOwner);
    }

    private static ItemStack copyOrNull(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    private static BlockFace shelfLeftOf(BlockFace facing) {
        return switch (facing) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> null;
        };
    }

    private static boolean isMatchingShelf(Block block, BlockFace facing) {
        return block.getBlockData() instanceof Shelf shelf
                && shelf.isPowered()
                && shelf.getFacing() == facing
                && shelf.getSideChain() != SideChaining.ChainPart.UNCONNECTED;
    }

    private static boolean isPowered(Block block) {
        if (block.getBlockData() instanceof Powerable powerable) return powerable.isPowered();
        return block.getBlockData() instanceof AnaloguePowerable analogue && analogue.getPower() > 0;
    }

    private static boolean hasFullGoldenArmourFrom(
            Piglin piglin, Map<EquipmentSlot, UUID> owners, UUID playerId) {
        if (!allArmourSlotsOwnedBy(owners, playerId)) return false;
        for (EquipmentSlot slot : GOLD_ARMOUR_SLOTS) {
            if (goldenArmourSlot(piglin.getEquipment().getItem(slot).getType()) != slot) {
                return false;
            }
        }
        return true;
    }

    static boolean allArmourSlotsOwnedBy(Map<EquipmentSlot, UUID> owners, UUID playerId) {
        for (EquipmentSlot slot : GOLD_ARMOUR_SLOTS) {
            if (!playerId.equals(owners.get(slot))) return false;
        }
        return true;
    }

    private EnumMap<EquipmentSlot, PiglinPickup> piglinPickupsFor(UUID piglinId) {
        if (!piglinPickups.containsKey(piglinId) && piglinPickups.size() >= MAX_TRANSIENT_ENTRIES) {
            UUID oldest = piglinPickups.keySet().iterator().next();
            piglinPickups.remove(oldest);
            piglinArmourOwners.remove(oldest);
        }
        return piglinPickups.computeIfAbsent(
                piglinId, ignored -> new EnumMap<>(EquipmentSlot.class));
    }

    private EnumMap<EquipmentSlot, UUID> piglinArmourOwnersFor(UUID piglinId) {
        if (!piglinArmourOwners.containsKey(piglinId)
                && piglinArmourOwners.size() >= MAX_TRANSIENT_ENTRIES) {
            UUID oldest = piglinArmourOwners.keySet().iterator().next();
            piglinArmourOwners.remove(oldest);
            piglinPickups.remove(oldest);
        }
        return piglinArmourOwners.computeIfAbsent(
                piglinId, ignored -> new EnumMap<>(EquipmentSlot.class));
    }

    private static EquipmentSlot goldenArmourSlot(Material material) {
        return switch (material) {
            case GOLDEN_HELMET -> EquipmentSlot.HEAD;
            case GOLDEN_CHESTPLATE -> EquipmentSlot.CHEST;
            case GOLDEN_LEGGINGS -> EquipmentSlot.LEGS;
            case GOLDEN_BOOTS -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private void removeTrackedBlock(BlockKey key) {
        removeOwnedBlock(key, berryBushOwners, berryBushesByPlayer);
        removeOwnedBlock(key, fireOwners, firesByPlayer);
        removeOwnedBlock(key, pressurePlateOwners, pressurePlatesByPlayer);
        pressurePlateTriggers.remove(key);
    }

    private void removePistonBlocks(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            removeTrackedBlock(BlockKey.from(block));
            removeTrackedBlock(BlockKey.from(block.getRelative(direction)));
        }
    }

    private void pruneTransientState(int tick) {
        projectileShots.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > PROJECTILE_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
        bellAttempts.entrySet().removeIf(entry -> !isFresh(entry.getValue(), tick, SHORT_CORRELATION_TICKS));
        berryDamage.values().removeIf(value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS));
        minecartContacts.values().removeIf(value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS));
        turtleScutes.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
        pigShearAttempts.values().removeIf(value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS));
        pendingSelfArrowTotems.values().removeIf(value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS));
        droppedArmour.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
        piglinPickups.values().forEach(map ->
                map.values().removeIf(value -> tick - value.tick() > PIGLIN_RETENTION_TICKS));
        piglinPickups.values().removeIf(Map::isEmpty);
        pressurePlateTriggers.values().removeIf(value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS));
    }

    private void pruneTransientStateIfDue(int tick) {
        if (lastPruneTick != Integer.MIN_VALUE) {
            int age = tick - lastPruneTick;
            if (age >= 0 && age < 20) return;
        }
        lastPruneTick = tick;
        pruneTransientState(tick);
    }

    private Block blockAt(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
    }

    private static Block canonicalDoor(Block block) {
        if (!Tag.DOORS.isTagged(block.getType()) || !(block.getBlockData() instanceof Door door)) return null;
        return door.getHalf() == Bisected.Half.TOP ? block.getRelative(BlockFace.DOWN) : block;
    }

    private static void setOwnedBlock(
            BlockKey key,
            UUID playerId,
            Map<BlockKey, UUID> owners,
            Map<UUID, Set<BlockKey>> blocksByPlayer,
            int maximum) {
        UUID previous = owners.put(key, playerId);
        if (previous != null && !previous.equals(playerId)) {
            removeFromOwnerIndex(blocksByPlayer, previous, key);
        }
        Set<BlockKey> blocks = blocksByPlayer.computeIfAbsent(
                playerId, ignored -> new LinkedHashSet<>());
        blocks.add(key);
        while (blocks.size() > maximum) {
            BlockKey oldest = blocks.iterator().next();
            blocks.remove(oldest);
            owners.remove(oldest, playerId);
        }
    }

    private static UUID removeOwnedBlock(
            BlockKey key, Map<BlockKey, UUID> owners, Map<UUID, Set<BlockKey>> blocksByPlayer) {
        UUID owner = owners.remove(key);
        if (owner != null) removeFromOwnerIndex(blocksByPlayer, owner, key);
        return owner;
    }

    private static void removeOwnedBlocks(
            UUID playerId,
            Map<BlockKey, UUID> owners,
            Map<UUID, Set<BlockKey>> blocksByPlayer) {
        Set<BlockKey> blocks = blocksByPlayer.remove(playerId);
        if (blocks == null) return;
        for (BlockKey block : blocks) owners.remove(block, playerId);
    }

    private static void removeFromOwnerIndex(
            Map<UUID, Set<BlockKey>> blocksByPlayer, UUID playerId, BlockKey key) {
        Set<BlockKey> blocks = blocksByPlayer.get(playerId);
        if (blocks == null) return;
        blocks.remove(key);
        if (blocks.isEmpty()) blocksByPlayer.remove(playerId);
    }

    private static <K, V> void putBounded(Map<K, V> map, K key, V value) {
        if (!map.containsKey(key) && map.size() >= MAX_TRANSIENT_ENTRIES) {
            map.remove(map.keySet().iterator().next());
        }
        map.put(key, value);
    }

    private static boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
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

    private static ItemStack singleItem(ItemStack stack) {
        ItemStack copy = stack.clone();
        copy.setAmount(1);
        return copy;
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record ProjectileShot(UUID playerId, Location origin, int tick) {}

    private record BellAttemptKey(BlockKey bell, UUID playerId) {}

    private record DoorCandidate(BlockKey block, Material material) {}

    private record AttemptToken(long detectorGeneration, long playerGeneration) {}

    private record TimedPlayer(UUID playerId, int tick) {}

    private record MinecartContact(UUID playerId, BlockKey fire, int tick) {}

    private record PrimedMinecart(UUID playerId, int tick, int maximumAge) {}

    private record PigShearAttempt(UUID playerId, int tick, boolean dropObserved) {}

    private static final class ScuteProvenance {
        private final int tick;
        private boolean consumed;

        private ScuteProvenance(int tick) {
            this.tick = tick;
        }

        private int tick() {
            return tick;
        }

        private boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }

        private boolean isConsumed() {
            return consumed;
        }
    }

    private record ShelfAttempt(
            int tick,
            List<BlockKey> shelves,
            BlockFace facing,
            ItemStack[] previousShelves,
            ItemStack[] previousHotbar) {}

    private record DroppedArmour(UUID playerId, EquipmentSlot slot, ItemStack item, int tick) {}

    private record PiglinPickup(
            UUID playerId, ItemStack item, int tick, boolean observedInOffhand, int releasedTick) {}
}
