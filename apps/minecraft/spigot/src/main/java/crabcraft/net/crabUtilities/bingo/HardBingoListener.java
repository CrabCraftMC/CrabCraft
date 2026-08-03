package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.entity.EntityFertilizeEggEvent;
import io.papermc.paper.event.player.PlayerItemCooldownEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mule;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sniffer;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

/** Event-driven detectors for the harder Bingo #1 card. */
public final class HardBingoListener implements Listener {
    private static final int MAX_OWNED_BLOCKS_PER_PLAYER = 2_048;
    private static final int MAX_ORE_BLOCKS_PER_PLAYER = 512;
    private static final int SHORT_CORRELATION_TICKS = 3;
    private static final int ITEM_RETENTION_TICKS = 20 * 60 * 10;
    private static final int HORN_COMPLETION_TICKS = 20 * 10;
    private static final int SCAFFOLDING_HEIGHT = 64;
    private static final double CAMPFIRE_DISTANCE_SQUARED = 100.0;
    private static final BlockFace[] FACES = {
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
    private final BiConsumer<Player, HornProgress> hornProgress;
    private final Map<UUID, Set<String>> hornsByPlayer = new HashMap<>();
    private final Map<UUID, PendingHorn> pendingHorns = new HashMap<>();
    private final Map<BlockKey, OrePlacement> oreByBlock = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> oresByPlayer = new HashMap<>();
    private final Map<BlockKey, UUID> scaffoldingOwnerByBlock = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> scaffoldingByPlayer = new HashMap<>();
    private final Map<BlockKey, UUID> anvilOwnerByBlock = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> anvilsByPlayer = new HashMap<>();
    private final Map<UUID, UUID> fallingAnvilOwners = new HashMap<>();
    private final Map<UUID, TimedPlayer> anvilDamageByVictim = new HashMap<>();
    private final Map<UUID, UUID> boatOwners = new HashMap<>();
    private final Map<UUID, ProjectileShot> projectileShots = new HashMap<>();
    private final List<PendingSnifferEgg> pendingSnifferEggs = new ArrayList<>();
    private final Map<UUID, TimedPlayer> snifferEggOwners = new HashMap<>();
    private final Map<UUID, DroppedAxe> droppedAxes = new HashMap<>();

    public HardBingoListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion) {
        this(plugin, tracking, completion, (player, progress) -> {});
    }

    public HardBingoListener(
            JavaPlugin plugin,
            BiPredicate<Player, BingoTask> tracking,
            BiConsumer<Player, BingoTask> completion,
            BiConsumer<Player, HornProgress> hornProgress) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.completion = completion;
        this.hornProgress = hornProgress;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrown(StructureGrowEvent event) {
        Player player = event.getPlayer();
        if (player != null
                && event.isFromBonemeal()
                && event.getWorld().getEnvironment() == World.Environment.NETHER
                && tracking.test(player, BingoTask.GROW_TREE_IN_NETHER)) {
            completion.accept(player, BingoTask.GROW_TREE_IN_NETHER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null
                || item.getType() != Material.GOAT_HORN
                || player.hasCooldown(item)
                || !tracking.test(player, BingoTask.PLAY_FIVE_GOAT_HORNS)
                || !(item.getItemMeta() instanceof MusicInstrumentMeta meta)
                || meta.getInstrument() == null) {
            return;
        }

        String instrument = RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.INSTRUMENT)
                .getKey(meta.getInstrument())
                .asString();
        pendingHorns.put(player.getUniqueId(), new PendingHorn(instrument, Bukkit.getCurrentTick()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemCooldown(PlayerItemCooldownEvent event) {
        if (event.getType() != Material.GOAT_HORN || event.getCooldown() <= 0) return;
        Player player = event.getPlayer();
        PendingHorn pending = pendingHorns.remove(player.getUniqueId());
        if (pending == null
                || !isFresh(pending.tick(), Bukkit.getCurrentTick(), HORN_COMPLETION_TICKS)
                || !tracking.test(player, BingoTask.PLAY_FIVE_GOAT_HORNS)) {
            return;
        }
        Set<String> horns = hornsByPlayer.computeIfAbsent(
                player.getUniqueId(), ignored -> new HashSet<>());
        if (!horns.add(pending.instrument())) return;
        hornProgress.accept(player, new HornProgress(pending.instrument(), horns.size()));
        if (horns.size() >= 5) {
            completion.accept(player, BingoTask.PLAY_FIVE_GOAT_HORNS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        BlockKey key = BlockKey.from(block);
        removeTrackedBlock(key);

        OreFamily family = OreFamily.from(block.getType());
        if (family != null && tracking.test(player, BingoTask.CONNECT_ALL_ORE_TYPES)) {
            setOre(key, new OrePlacement(player.getUniqueId(), family));
            if (hasAllOreFamilies(player.getUniqueId(), key)) {
                completion.accept(player, BingoTask.CONNECT_ALL_ORE_TYPES);
            }
        }
        if (block.getType() == Material.SCAFFOLDING
                && tracking.test(player, BingoTask.COLLAPSE_SCAFFOLDING_TOWER)) {
            setOwnedBlock(
                    key,
                    player.getUniqueId(),
                    scaffoldingOwnerByBlock,
                    scaffoldingByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER);
        }
        if (isAnvil(block.getType())
                && tracking.test(player, BingoTask.KILL_HOSTILE_WITH_ANVIL)) {
            setOwnedBlock(
                    key,
                    player.getUniqueId(),
                    anvilOwnerByBlock,
                    anvilsByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockKey key = BlockKey.from(block);
        Player player = event.getPlayer();
        if (block.getType() == Material.SCAFFOLDING
                && tracking.test(player, BingoTask.COLLAPSE_SCAFFOLDING_TOWER)
                && hasOwnedScaffoldingTower(player.getUniqueId(), block)) {
            completion.accept(player, BingoTask.COLLAPSE_SCAFFOLDING_TOWER);
        }
        removeTrackedBlock(key);
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
    public void onResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player) {
            completion.accept(player, BingoTask.ACTIVATE_TOTEM);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBred(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) return;

        if (event.getEntity() instanceof Mule
                && ((event.getMother() instanceof Horse && event.getFather() instanceof Donkey)
                        || (event.getMother() instanceof Donkey && event.getFather() instanceof Horse))) {
            completion.accept(player, BingoTask.BREED_MULE);
        }

        if (event.getEntity() instanceof Fox fox
                && tracking.test(player, BingoTask.BREED_TRUSTING_FOX)) {
            UUID foxId = fox.getUniqueId();
            UUID playerId = player.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Entity current = Bukkit.getEntity(foxId);
                Player currentPlayer = Bukkit.getPlayer(playerId);
                if (current instanceof Fox currentFox
                        && currentPlayer != null
                        && (isTrustedBy(currentFox.getFirstTrustedPlayer(), playerId)
                                || isTrustedBy(currentFox.getSecondTrustedPlayer(), playerId))) {
                    completion.accept(currentPlayer, BingoTask.BREED_TRUSTING_FOX);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlaced(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null
                && event.getEntity() instanceof Boat
                && tracking.test(player, BingoTask.TWO_CREEPERS_ONE_BOAT)) {
            boatOwners.put(event.getEntity().getUniqueId(), player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEntered(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Boat boat) || !(event.getEntered() instanceof Creeper)) return;
        UUID ownerId = boatOwners.get(boat.getUniqueId());
        if (ownerId == null) return;
        UUID boatId = boat.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity current = Bukkit.getEntity(boatId);
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner != null
                    && current instanceof Boat currentBoat
                    && currentBoat.getPassengers().stream().filter(Creeper.class::isInstance).count() >= 2) {
                completion.accept(owner, BingoTask.TWO_CREEPERS_ONE_BOAT);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunched(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player player
                && tracking.test(player, BingoTask.IGNITE_CAMPFIRE_FROM_DISTANCE)) {
            projectileShots.put(
                    projectile.getUniqueId(),
                    new ProjectileShot(player.getUniqueId(), projectile.getLocation().clone(), Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileShot shot = projectileShots.remove(projectile.getUniqueId());
        Block block = event.getHitBlock();
        if (shot == null
                || block == null
                || !isCampfire(block.getType())
                || projectile.getFireTicks() <= 0
                || !sameWorld(shot.origin(), block.getLocation())
                || shot.origin().distanceSquared(block.getLocation().toCenterLocation())
                        < CAMPFIRE_DISTANCE_SQUARED) {
            return;
        }
        BlockKey key = BlockKey.from(block);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Block current = blockAt(key);
            Player player = Bukkit.getPlayer(shot.playerId());
            if (player != null
                    && current != null
                    && current.getBlockData() instanceof Lightable lightable
                    && lightable.isLit()) {
                completion.accept(player, BingoTask.IGNITE_CAMPFIRE_FROM_DISTANCE);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSnifferFertilised(EntityFertilizeEggEvent event) {
        Player breeder = event.getBreeder();
        if (breeder != null
                && event.getEntity() instanceof Sniffer
                && event.getMother() instanceof Sniffer
                && event.getFather() instanceof Sniffer
                && tracking.test(breeder, BingoTask.BREED_SNIFFERS_COLLECT_EGG)) {
            pruneTransientState(Bukkit.getCurrentTick());
            pendingSnifferEggs.add(new PendingSnifferEgg(
                    breeder.getUniqueId(), event.getEntity().getLocation().clone(), Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawned(ItemSpawnEvent event) {
        if (event.getEntity().getItemStack().getType() != Material.SNIFFER_EGG) return;
        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        Set<UUID> matchingPlayers = new HashSet<>();
        for (PendingSnifferEgg pending : pendingSnifferEggs) {
            if (isFresh(pending.tick(), tick, SHORT_CORRELATION_TICKS)
                    && sameWorld(pending.location(), event.getLocation())
                    && pending.location().distanceSquared(event.getLocation()) <= 16.0) {
                matchingPlayers.add(pending.playerId());
            }
        }
        if (matchingPlayers.size() == 1) {
            snifferEggOwners.put(
                    event.getEntity().getUniqueId(),
                    new TimedPlayer(matchingPlayers.iterator().next(), tick));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickedUp(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (event.getEntity() instanceof Player player) {
            TimedPlayer eggOwner = snifferEggOwners.remove(item.getUniqueId());
            if (eggOwner != null && eggOwner.playerId().equals(player.getUniqueId())) {
                completion.accept(player, BingoTask.BREED_SNIFFERS_COLLECT_EGG);
            }
        }

        if (event.getEntity() instanceof PiglinBrute brute) {
            DroppedAxe axe = droppedAxes.get(item.getUniqueId());
            if (axe == null) return;
            UUID bruteId = brute.getUniqueId();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Entity current = Bukkit.getEntity(bruteId);
                Player player = Bukkit.getPlayer(axe.playerId());
                if (current instanceof PiglinBrute currentBrute
                        && player != null
                        && currentBrute.getEquipment().getItemInMainHand().isSimilar(axe.item())) {
                    droppedAxes.remove(item.getUniqueId());
                    completion.accept(player, BingoTask.EQUIP_PIGLIN_BRUTE_AXE);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onZombieVillagerCured(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.CURED
                || !(event.getEntity() instanceof ZombieVillager zombie)
                || zombie.getConversionPlayer() == null) {
            return;
        }
        Player player = Bukkit.getPlayer(zombie.getConversionPlayer().getUniqueId());
        if (player != null) {
            completion.accept(player, BingoTask.CURE_ZOMBIE_VILLAGER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getCause() == EntityPotionEffectEvent.Cause.AXOLOTL
                && event.getModifiedType().equals(PotionEffectType.REGENERATION)) {
            completion.accept(player, BingoTask.GAIN_AXOLOTL_REGENERATION);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDropped(PlayerDropItemEvent event) {
        ItemStack stack = event.getItemDrop().getItemStack();
        if (stack.getType() == Material.GOLDEN_AXE
                && !stack.getEnchantments().isEmpty()
                && tracking.test(event.getPlayer(), BingoTask.EQUIP_PIGLIN_BRUTE_AXE)) {
            pruneTransientState(Bukkit.getCurrentTick());
            droppedAxes.put(
                    event.getItemDrop().getUniqueId(),
                    new DroppedAxe(event.getPlayer().getUniqueId(), singleItem(stack), Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAllayInteracted(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Allay allay)
                || event.getPlayer().getInventory().getItemInMainHand().getType() != Material.AMETHYST_SHARD
                || !allay.isDancing()
                || !allay.canDuplicate()
                || !tracking.test(event.getPlayer(), BingoTask.DUPLICATE_ALLAY)) {
            return;
        }

        Set<UUID> previousAllays = nearbyAllayIds(allay);
        UUID allayId = allay.getUniqueId();
        UUID playerId = event.getPlayer().getUniqueId();
        Location location = allay.getLocation().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity original = Bukkit.getEntity(allayId);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !(original instanceof Allay currentAllay) || currentAllay.canDuplicate()) {
                return;
            }
            boolean newAllayExists = currentAllay.getWorld().getNearbyEntities(location, 4, 4, 4).stream()
                    .filter(Allay.class::isInstance)
                    .map(Entity::getUniqueId)
                    .anyMatch(id -> !previousAllays.contains(id));
            if (newAllayExists) {
                completion.accept(player, BingoTask.DUPLICATE_ALLAY);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Enemy)
                || !(event.getDamager() instanceof FallingBlock falling)
                || !isAnvil(falling.getBlockData().getMaterial())) {
            return;
        }
        UUID owner = fallingAnvilOwners.get(falling.getUniqueId());
        if (owner != null) {
            anvilDamageByVictim.put(
                    event.getEntity().getUniqueId(), new TimedPlayer(owner, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)) return;
        int tick = Bukkit.getCurrentTick();
        TimedPlayer anvilAttempt = anvilDamageByVictim.remove(event.getEntity().getUniqueId());
        if (anvilAttempt != null && isFresh(anvilAttempt.tick(), tick, SHORT_CORRELATION_TICKS)) {
            Player owner = Bukkit.getPlayer(anvilAttempt.playerId());
            if (owner != null) {
                completion.accept(owner, BingoTask.KILL_HOSTILE_WITH_ANVIL);
            }
        }

        Player killer = event.getEntity().getKiller();
        if (killer != null && killer.getVehicle() instanceof Camel) {
            completion.accept(killer, BingoTask.KILL_HOSTILE_FROM_CAMEL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockSpawned(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)
                || !isAnvil(falling.getBlockData().getMaterial())
                || falling.getOrigin() == null) {
            return;
        }
        UUID owner = removeOwnedBlock(
                BlockKey.from(falling.getOrigin().getBlock()), anvilOwnerByBlock, anvilsByPlayer);
        if (owner != null) {
            fallingAnvilOwners.put(falling.getUniqueId(), owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockChanged(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)
                || !isAnvil(falling.getBlockData().getMaterial())) {
            return;
        }
        UUID fallingId = falling.getUniqueId();
        UUID owner = fallingAnvilOwners.get(fallingId);
        if (event.getTo().isAir()) {
            if (owner == null) {
                owner = removeOwnedBlock(
                        BlockKey.from(event.getBlock()), anvilOwnerByBlock, anvilsByPlayer);
                if (owner != null) fallingAnvilOwners.put(fallingId, owner);
            }
            return;
        }
        fallingAnvilOwners.remove(fallingId);
        if (owner != null && isAnvil(event.getTo())) {
            setOwnedBlock(
                    BlockKey.from(event.getBlock()),
                    owner,
                    anvilOwnerByBlock,
                    anvilsByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveEvent event) {
        UUID id = event.getEntity().getUniqueId();
        anvilDamageByVictim.remove(id);
        boatOwners.remove(id);
        projectileShots.remove(id);
        fallingAnvilOwners.remove(id);
        snifferEggOwners.remove(id);
        droppedAxes.remove(id);
    }

    public void resetPlayer(UUID playerId) {
        hornsByPlayer.remove(playerId);
        pendingHorns.remove(playerId);
        removeOreBlocks(playerId);
        removeOwnedBlocks(playerId, scaffoldingOwnerByBlock, scaffoldingByPlayer);
        removeOwnedBlocks(playerId, anvilOwnerByBlock, anvilsByPlayer);
        fallingAnvilOwners.values().removeIf(playerId::equals);
        anvilDamageByVictim.values().removeIf(value -> value.playerId().equals(playerId));
        boatOwners.values().removeIf(playerId::equals);
        projectileShots.values().removeIf(value -> value.playerId().equals(playerId));
        pendingSnifferEggs.removeIf(value -> value.playerId().equals(playerId));
        snifferEggOwners.values().removeIf(value -> value.playerId().equals(playerId));
        droppedAxes.values().removeIf(value -> value.playerId().equals(playerId));
    }

    public void clear() {
        hornsByPlayer.clear();
        pendingHorns.clear();
        oreByBlock.clear();
        oresByPlayer.clear();
        scaffoldingOwnerByBlock.clear();
        scaffoldingByPlayer.clear();
        anvilOwnerByBlock.clear();
        anvilsByPlayer.clear();
        fallingAnvilOwners.clear();
        anvilDamageByVictim.clear();
        boatOwners.clear();
        projectileShots.clear();
        pendingSnifferEggs.clear();
        snifferEggOwners.clear();
        droppedAxes.clear();
    }

    private boolean hasAllOreFamilies(UUID playerId, BlockKey anchor) {
        OrePlacement anchorPlacement = oreByBlock.get(anchor);
        if (anchorPlacement == null || !anchorPlacement.playerId().equals(playerId)) return false;
        Set<BlockKey> visited = new HashSet<>();
        Set<OreFamily> families = new HashSet<>();
        ArrayDeque<BlockKey> queue = new ArrayDeque<>();
        queue.add(anchor);
        while (!queue.isEmpty()) {
            BlockKey current = queue.removeFirst();
            if (!visited.add(current)) continue;
            OrePlacement placement = oreByBlock.get(current);
            if (placement == null || !placement.playerId().equals(playerId)) continue;
            families.add(placement.family());
            if (families.size() == OreFamily.values().length) return true;
            for (BlockFace face : FACES) queue.add(current.relative(face));
        }
        return false;
    }

    private boolean hasOwnedScaffoldingTower(UUID playerId, Block base) {
        for (int offset = 0; offset < SCAFFOLDING_HEIGHT; offset++) {
            Block current = base.getRelative(BlockFace.UP, offset);
            if (current.getType() != Material.SCAFFOLDING
                    || !playerId.equals(scaffoldingOwnerByBlock.get(BlockKey.from(current)))) {
                return false;
            }
        }
        return true;
    }

    private void setOre(BlockKey key, OrePlacement placement) {
        OrePlacement previous = oreByBlock.put(key, placement);
        if (previous != null && !previous.playerId().equals(placement.playerId())) {
            removeFromOwnerIndex(oresByPlayer, previous.playerId(), key);
        }
        Set<BlockKey> blocks = oresByPlayer.computeIfAbsent(
                placement.playerId(), ignored -> new LinkedHashSet<>());
        blocks.add(key);
        while (blocks.size() > MAX_ORE_BLOCKS_PER_PLAYER) {
            BlockKey oldest = blocks.iterator().next();
            blocks.remove(oldest);
            OrePlacement current = oreByBlock.get(oldest);
            if (current != null && current.playerId().equals(placement.playerId())) {
                oreByBlock.remove(oldest);
            }
        }
    }

    private void removeOre(BlockKey key) {
        OrePlacement placement = oreByBlock.remove(key);
        if (placement != null) removeFromOwnerIndex(oresByPlayer, placement.playerId(), key);
    }

    private void removeOreBlocks(UUID playerId) {
        Set<BlockKey> blocks = oresByPlayer.remove(playerId);
        if (blocks == null) return;
        for (BlockKey block : blocks) {
            OrePlacement placement = oreByBlock.get(block);
            if (placement != null && placement.playerId().equals(playerId)) oreByBlock.remove(block);
        }
    }

    private void removeTrackedBlock(BlockKey key) {
        removeOre(key);
        removeOwnedBlock(key, scaffoldingOwnerByBlock, scaffoldingByPlayer);
        removeOwnedBlock(key, anvilOwnerByBlock, anvilsByPlayer);
    }

    private void removePistonBlocks(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            removeTrackedBlock(BlockKey.from(block));
            removeTrackedBlock(BlockKey.from(block.getRelative(direction)));
        }
    }

    private void pruneTransientState(int tick) {
        pendingSnifferEggs.removeIf(pending -> !isFresh(pending.tick(), tick, SHORT_CORRELATION_TICKS));
        snifferEggOwners.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
        droppedAxes.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
        projectileShots.entrySet().removeIf(entry ->
                tick - entry.getValue().tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.getKey()) == null);
        anvilDamageByVictim.values().removeIf(attempt ->
                !isFresh(attempt.tick(), tick, SHORT_CORRELATION_TICKS));
    }

    private Block blockAt(BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
    }

    private static Set<UUID> nearbyAllayIds(Allay allay) {
        Set<UUID> ids = new HashSet<>();
        ids.add(allay.getUniqueId());
        allay.getNearbyEntities(4, 4, 4).stream()
                .filter(Allay.class::isInstance)
                .map(Entity::getUniqueId)
                .forEach(ids::add);
        return ids;
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

    private static boolean isAnvil(Material material) {
        return material == Material.ANVIL
                || material == Material.CHIPPED_ANVIL
                || material == Material.DAMAGED_ANVIL;
    }

    private static boolean isCampfire(Material material) {
        return material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE;
    }

    private static boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private static boolean isTrustedBy(org.bukkit.entity.AnimalTamer tamer, UUID playerId) {
        return tamer != null && playerId.equals(tamer.getUniqueId());
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

    private enum OreFamily {
        COAL,
        IRON,
        COPPER,
        GOLD,
        REDSTONE,
        LAPIS,
        DIAMOND,
        EMERALD,
        NETHER_QUARTZ,
        NETHER_GOLD,
        ANCIENT_DEBRIS;

        static OreFamily from(Material material) {
            return switch (material) {
                case COAL_ORE, DEEPSLATE_COAL_ORE -> COAL;
                case IRON_ORE, DEEPSLATE_IRON_ORE -> IRON;
                case COPPER_ORE, DEEPSLATE_COPPER_ORE -> COPPER;
                case GOLD_ORE, DEEPSLATE_GOLD_ORE -> GOLD;
                case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> REDSTONE;
                case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> LAPIS;
                case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> DIAMOND;
                case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> EMERALD;
                case NETHER_QUARTZ_ORE -> NETHER_QUARTZ;
                case NETHER_GOLD_ORE -> NETHER_GOLD;
                case ANCIENT_DEBRIS -> ANCIENT_DEBRIS;
                default -> null;
            };
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        BlockKey relative(BlockFace face) {
            return new BlockKey(worldId, x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }
    }

    private record OrePlacement(UUID playerId, OreFamily family) {}

    private record PendingHorn(String instrument, int tick) {}

    public record HornProgress(String instrument, int uniqueCount) {}

    private record TimedPlayer(UUID playerId, int tick) {}

    private record ProjectileShot(UUID playerId, Location origin, int tick) {}

    private record PendingSnifferEgg(UUID playerId, Location location, int tick) {}

    private record DroppedAxe(UUID playerId, ItemStack item, int tick) {}
}
