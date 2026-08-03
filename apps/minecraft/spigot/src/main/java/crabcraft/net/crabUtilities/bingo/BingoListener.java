package crabcraft.net.crabUtilities.bingo;

import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.CopperBulb;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

final class BingoListener implements Listener {
    private static final int MAX_CORRELATION_AGE_TICKS = 2;
    private static final int DROPPED_ITEM_RETENTION_TICKS = 20 * 60 * 5;
    private static final int DRIPLEAF_HEIGHT = 10;
    private static final int MAX_DRIPLEAF_BLOCKS_PER_PLAYER = 256;
    private static final int MAX_SAND_BLOCKS_PER_PLAYER = 2_048;
    private static final BlockFace[] CARDINAL_FACES = {
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST
    };

    private final JavaPlugin plugin;
    private final BingoManager manager;
    private final Map<UUID, WolfArmourAttempt> wolfArmourAttempts = new HashMap<>();
    private final Map<UUID, GoatMilkAttempt> goatMilkAttempts = new HashMap<>();
    private final Map<UUID, CopperBulbAttempt> copperBulbAttempts = new HashMap<>();
    private final Map<UUID, DripleafFertilizeAttempt> dripleafFertilizeAttempts = new HashMap<>();
    private final Map<UUID, RavagerAttempt> ravagerAttempts = new HashMap<>();
    private final Map<UUID, PumpkinCarveAttempt> pumpkinCarveAttempts = new HashMap<>();
    private final Map<UUID, DroppedHeadItem> droppedHeadItems = new HashMap<>();
    private final Map<UUID, HatPickupAttempt> hatPickupAttempts = new HashMap<>();
    private final Map<BlockKey, PendingLecternTurn> pendingLecternTurns = new HashMap<>();
    private final Map<BlockKey, UUID> dripleafOwnerByBlock = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> dripleafBlocksByPlayer = new HashMap<>();
    private final Map<BlockKey, SandBlockOwnership> sandOwnershipByBlock = new HashMap<>();
    private final Map<UUID, Set<BlockKey>> sandBlocksByPlayer = new HashMap<>();
    private final Map<UUID, UUID> fallingSandOwners = new HashMap<>();
    private final Map<UUID, SandDamageAttempt> sandDamageAttempts = new HashMap<>();

    BingoListener(JavaPlugin plugin, BingoManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearTransientState(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRabbitLeashed(PlayerLeashEntityEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        if (event.getEntity() instanceof Rabbit && event.getLeashHolder().equals(event.getPlayer())) {
            manager.complete(event.getPlayer(), BingoTask.LEASH_RABBIT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Mob mob) {
            Player killer = mob.getKiller();
            EntityEquipment equipment = mob.getEquipment();
            if (killer != null
                    && hasType(equipment.getHelmet(), Material.IRON_HELMET)
                    && hasType(equipment.getChestplate(), Material.IRON_CHESTPLATE)
                    && hasType(equipment.getLeggings(), Material.IRON_LEGGINGS)
                    && hasType(equipment.getBoots(), Material.IRON_BOOTS)) {
                manager.complete(killer, BingoTask.KILL_FULL_IRON_MOB);
            }
        }

        if (!(entity instanceof Enemy)
                || entity.getLastDamageCause() == null
                || !isSuffocationDamage(entity.getLastDamageCause())) {
            sandDamageAttempts.remove(entity.getUniqueId());
            return;
        }

        SandDamageAttempt attempt = sandDamageAttempts.remove(entity.getUniqueId());
        if (attempt == null || !isFresh(attempt.tick(), Bukkit.getCurrentTick())) {
            return;
        }
        Player player = plugin.getServer().getPlayer(attempt.playerId());
        if (player != null) {
            manager.complete(player, BingoTask.SUFFOCATE_HOSTILE_WITH_SAND);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Enemy enemy) || !isSuffocationDamage(event)) {
            return;
        }

        UUID owner = findSingleFallenSandOwner(enemy);
        if (owner == null) {
            sandDamageAttempts.remove(enemy.getUniqueId());
        } else {
            sandDamageAttempts.put(
                    enemy.getUniqueId(), new SandDamageAttempt(owner, Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        if (!event.canBuild()) {
            return;
        }

        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        BlockKey blockKey = BlockKey.from(block);
        removeDripleafOwner(blockKey);
        removeSandOwnership(blockKey);

        if (manager.isTracking(player, BingoTask.LIGHT_COPPER_BULB)
                && event.getItemInHand().getType() == Material.REDSTONE_TORCH) {
            recordCopperBulbAttempt(player, block, tick);
        }

        if (manager.isTracking(player, BingoTask.BUILD_DRIPLEAF_COLUMN)
                && block.getType() == Material.BIG_DRIPLEAF) {
            setDripleafOwner(blockKey, player.getUniqueId());
            verifyDripleafNextTick(player.getUniqueId(), blockKey);
        }

        if (manager.isTracking(player, BingoTask.SUFFOCATE_HOSTILE_WITH_SAND)
                && block.getType() == Material.SAND) {
            setSandOwnership(blockKey, new SandBlockOwnership(player.getUniqueId(), false));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFertilised(BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player == null || !manager.isEligible(player)) {
            return;
        }

        Material sourceType = event.getBlock().getType();
        if (manager.isTracking(player, BingoTask.GROW_HUGE_MUSHROOM)
                && (sourceType == Material.RED_MUSHROOM || sourceType == Material.BROWN_MUSHROOM)) {
            Material expectedCap = sourceType == Material.RED_MUSHROOM
                    ? Material.RED_MUSHROOM_BLOCK
                    : Material.BROWN_MUSHROOM_BLOCK;
            if (event.getBlocks().stream().map(BlockState::getType).anyMatch(expectedCap::equals)) {
                manager.complete(player, BingoTask.GROW_HUGE_MUSHROOM);
            }
        }

        if (!manager.isTracking(player, BingoTask.BUILD_DRIPLEAF_COLUMN)
                || !isDripleaf(sourceType)) {
            return;
        }

        BlockKey source = BlockKey.from(event.getBlock());
        Set<BlockKey> proposedBlocks = new HashSet<>();
        proposedBlocks.add(source);
        for (BlockState state : event.getBlocks()) {
            if (isDripleaf(state.getType())) {
                proposedBlocks.add(BlockKey.from(state.getBlock()));
            }
        }
        recordDripleafFertilizeAttempt(player.getUniqueId(), source, proposedBlocks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBroken(BlockBreakEvent event) {
        Block block = event.getBlock();
        removeTrackedBlock(BlockKey.from(block));
        if (!manager.isEligible(event.getPlayer())) return;
        if (block.getType() == Material.CACTUS_FLOWER) {
            manager.complete(event.getPlayer(), BingoTask.HARVEST_CACTUS_FLOWER);
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
        event.getBlocks().forEach(block -> removeTrackedBlock(BlockKey.from(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(block -> removeTrackedBlock(BlockKey.from(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAxolotlBucketed(PlayerBucketEntityEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        if (event.getEntity() instanceof Axolotl
                && event.getOriginalBucket().getType() == Material.WATER_BUCKET
                && event.getEntityBucket().getType() == Material.AXOLOTL_BUCKET) {
            manager.complete(event.getPlayer(), BingoTask.BUCKET_AXOLOTL);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityInteraction(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!manager.isEligible(player)) return;
        ItemStack heldItem = player.getInventory().getItem(event.getHand());
        if (event.getRightClicked() instanceof Wolf wolf
                && heldItem.getType() == Material.WOLF_ARMOR
                && wolf.isAdult()
                && wolf.isTamed()
                && player.getUniqueId().equals(wolf.getOwnerUniqueId())
                && wolf.getEquipment().getItem(EquipmentSlot.BODY).getType().isAir()) {
            verifyWolfArmourNextTick(player, wolf);
        }

        if (event.getRightClicked() instanceof Goat goat
                && heldItem.getType() == Material.BUCKET
                && goat.isAdult()) {
            goatMilkAttempts.put(player.getUniqueId(), new GoatMilkAttempt(
                    goat.getUniqueId(), event.getHand(), Bukkit.getCurrentTick()));
        }

    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFilled(PlayerBucketFillEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        if (event.getItemStack().getType() != Material.MILK_BUCKET) {
            return;
        }

        GoatMilkAttempt attempt = goatMilkAttempts.remove(event.getPlayer().getUniqueId());
        if (attempt == null
                || attempt.hand() != event.getHand()
                || !isFresh(attempt.tick(), Bukkit.getCurrentTick())) {
            return;
        }

        Entity entity = plugin.getServer().getEntity(attempt.goatId());
        if (entity instanceof Goat goat
                && goat.getWorld().equals(event.getPlayer().getWorld())
                && goat.getLocation().distanceSquared(event.getPlayer().getLocation()) <= 16.0) {
            manager.complete(event.getPlayer(), BingoTask.MILK_GOAT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsumed(PlayerItemConsumeEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        if (event.getItem().getType() == Material.SUSPICIOUS_STEW) {
            manager.complete(event.getPlayer(), BingoTask.EAT_SUSPICIOUS_STEW);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRavagerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Ravager ravager)
                || !(event.getEntity() instanceof Player player)
                || !player.isBlocking()
                || player.getActiveItem().getType() != Material.SHIELD
                || ravager.getStunnedTicks() != 0) {
            return;
        }
        if (!manager.isEligible(player)) return;
        scheduleRavagerVerification(player, ravager);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeadwearDropped(PlayerDropItemEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        Item item = event.getItemDrop();
        Material type = item.getItemStack().getType();
        if (!manager.isTracking(event.getPlayer(), BingoTask.GIVE_MOB_HAT)
                || !isEligibleHeadwear(type)) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        droppedHeadItems.put(
                item.getUniqueId(),
                new DroppedHeadItem(
                        event.getPlayer().getUniqueId(), singleItem(item.getItemStack()), tick));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerged(ItemMergeEvent event) {
        DroppedHeadItem source = droppedHeadItems.remove(event.getEntity().getUniqueId());
        DroppedHeadItem target = droppedHeadItems.remove(event.getTarget().getUniqueId());
        DroppedHeadItem retained = source == null ? target : source;
        boolean unambiguous = source == null || target == null || source.equalsIgnoringTick(target);
        if (retained != null && unambiguous) {
            droppedHeadItems.put(event.getTarget().getUniqueId(),
                    new DroppedHeadItem(
                            retained.playerId(), retained.item(), Bukkit.getCurrentTick()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickedUp(EntityPickupItemEvent event) {
        DroppedHeadItem dropped = droppedHeadItems.remove(event.getItem().getUniqueId());
        if (dropped == null || !(event.getEntity() instanceof Mob mob)) {
            return;
        }

        HatPickupAttempt attempt = new HatPickupAttempt(
                dropped.playerId(), dropped.item(), Bukkit.getCurrentTick());
        UUID mobId = mob.getUniqueId();
        hatPickupAttempts.put(mobId, attempt);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!hatPickupAttempts.remove(mobId, attempt)) {
                return;
            }
            Entity currentEntity = plugin.getServer().getEntity(mobId);
            Player player = plugin.getServer().getPlayer(attempt.playerId());
            if (currentEntity instanceof Mob currentMob
                    && player != null
                    && currentMob.getEquipment().getHelmet() != null
                    && currentMob.getEquipment().getHelmet().isSimilar(attempt.item())) {
                manager.complete(player, BingoTask.GIVE_MOB_HAT);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawned(ItemDespawnEvent event) {
        droppedHeadItems.remove(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onParrotTamed(EntityTameEvent event) {
        if (event.getEntity() instanceof Parrot
                && event.getOwner() instanceof Player player
                && manager.isEligible(player)) {
            manager.complete(player, BingoTask.TAME_PARROT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPumpkinSheared(PlayerShearBlockEvent event) {
        if (!manager.isEligible(event.getPlayer())) return;
        if (event.getBlock().getType() == Material.PUMPKIN
                && event.getItem().getType() == Material.SHEARS) {
            PumpkinCarveAttempt attempt = new PumpkinCarveAttempt(
                    event.getPlayer().getUniqueId(),
                    BlockKey.from(event.getBlock()),
                    Bukkit.getCurrentTick());
            pumpkinCarveAttempts.put(event.getPlayer().getUniqueId(), attempt);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!pumpkinCarveAttempts.remove(attempt.playerId(), attempt)) {
                    return;
                }
                Block pumpkin = blockAt(attempt.block());
                Player player = plugin.getServer().getPlayer(attempt.playerId());
                if (pumpkin != null
                        && pumpkin.getType() == Material.CARVED_PUMPKIN
                        && player != null) {
                    manager.complete(player, BingoTask.CARVE_PUMPKIN);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLecternPageChanged(PlayerLecternPageChangeEvent event) {
        if (!manager.isTracking(event.getPlayer(), BingoTask.LECTERN_IGNITE_TNT)) return;
        if (event.getOldPage() == event.getNewPage()) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        pruneTransientState(tick);
        BlockKey lectern = BlockKey.from(event.getLectern().getBlock());
        PendingLecternTurn previous = pendingLecternTurns.get(lectern);
        UUID playerId = event.getPlayer().getUniqueId();
        if (previous != null && previous.tick() == tick && !playerId.equals(previous.playerId())) {
            pendingLecternTurns.put(lectern, new PendingLecternTurn(null, tick));
        } else {
            pendingLecternTurns.put(lectern, new PendingLecternTurn(playerId, tick));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrimed(TNTPrimeEvent event) {
        if (event.getCause() != TNTPrimeEvent.PrimeCause.REDSTONE) {
            return;
        }

        int tick = Bukkit.getCurrentTick();
        Set<UUID> matchingPlayers = new HashSet<>();
        for (BlockFace face : CARDINAL_FACES) {
            Block adjacentBlock = event.getBlock().getRelative(face);
            if (!(adjacentBlock.getBlockData() instanceof org.bukkit.block.data.type.Lectern lectern)
                    || !lectern.isPowered()) {
                continue;
            }
            BlockKey adjacent = BlockKey.from(adjacentBlock);
            PendingLecternTurn turn = pendingLecternTurns.remove(adjacent);
            if (turn != null && turn.playerId() != null && isFresh(turn.tick(), tick)) {
                matchingPlayers.add(turn.playerId());
            }
        }

        if (matchingPlayers.size() != 1) {
            return;
        }
        Player player = plugin.getServer().getPlayer(matchingPlayers.iterator().next());
        if (player != null) {
            manager.complete(player, BingoTask.LECTERN_IGNITE_TNT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockSpawned(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)
                || falling.getBlockData().getMaterial() != Material.SAND) {
            return;
        }

        if (falling.getOrigin() == null) {
            return;
        }
        BlockKey source = BlockKey.from(falling.getOrigin().getBlock());
        SandBlockOwnership ownership = removeSandOwnership(source);
        if (ownership != null) {
            fallingSandOwners.put(falling.getUniqueId(), ownership.playerId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallingBlockChangedBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)
                || falling.getBlockData().getMaterial() != Material.SAND) {
            return;
        }

        UUID fallingId = falling.getUniqueId();
        UUID owner = fallingSandOwners.get(fallingId);
        if (event.getTo().isAir()) {
            if (owner == null) {
                SandBlockOwnership ownership = removeSandOwnership(BlockKey.from(event.getBlock()));
                if (ownership != null) {
                    fallingSandOwners.put(fallingId, ownership.playerId());
                }
            }
            return;
        }

        fallingSandOwners.remove(fallingId);
        if (owner != null && event.getTo() == Material.SAND) {
            setSandOwnership(
                    BlockKey.from(event.getBlock()), new SandBlockOwnership(owner, true));
        }
    }

    void resetPlayer(UUID playerId) {
        clearTransientState(playerId);
        removeOwnedBlocks(playerId, dripleafBlocksByPlayer, dripleafOwnerByBlock);

        Set<BlockKey> sandBlocks = sandBlocksByPlayer.remove(playerId);
        if (sandBlocks != null) {
            for (BlockKey block : sandBlocks) {
                SandBlockOwnership ownership = sandOwnershipByBlock.get(block);
                if (ownership != null && playerId.equals(ownership.playerId())) {
                    sandOwnershipByBlock.remove(block);
                }
            }
        }
        fallingSandOwners.values().removeIf(playerId::equals);
    }

    void clear() {
        wolfArmourAttempts.clear();
        goatMilkAttempts.clear();
        copperBulbAttempts.clear();
        dripleafFertilizeAttempts.clear();
        ravagerAttempts.clear();
        pumpkinCarveAttempts.clear();
        droppedHeadItems.clear();
        hatPickupAttempts.clear();
        pendingLecternTurns.clear();
        dripleafOwnerByBlock.clear();
        dripleafBlocksByPlayer.clear();
        sandOwnershipByBlock.clear();
        sandBlocksByPlayer.clear();
        fallingSandOwners.clear();
        sandDamageAttempts.clear();
    }

    private void recordCopperBulbAttempt(Player player, Block torch, int tick) {
        Set<BlockKey> unlitBulbs = new HashSet<>();
        for (BlockFace face : CARDINAL_FACES) {
            Block candidate = torch.getRelative(face);
            if (candidate.getBlockData() instanceof CopperBulb bulb
                    && !bulb.isLit()
                    && !bulb.isPowered()) {
                unlitBulbs.add(BlockKey.from(candidate));
            }
        }
        if (unlitBulbs.isEmpty()) {
            return;
        }

        CopperBulbAttempt attempt = new CopperBulbAttempt(
                player.getUniqueId(), BlockKey.from(torch), Set.copyOf(unlitBulbs), tick);
        copperBulbAttempts.put(player.getUniqueId(), attempt);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!copperBulbAttempts.remove(attempt.playerId(), attempt)) {
                return;
            }
            Block currentTorch = blockAt(attempt.torch());
            if (currentTorch == null || !isRedstoneTorch(currentTorch.getType())) {
                return;
            }
            for (BlockKey bulbKey : attempt.bulbs()) {
                Block currentBulb = blockAt(bulbKey);
                if (currentBulb != null
                        && currentBulb.getBlockData() instanceof CopperBulb bulb
                        && bulb.isLit()) {
                    Player currentPlayer = plugin.getServer().getPlayer(attempt.playerId());
                    if (currentPlayer != null) {
                        manager.complete(currentPlayer, BingoTask.LIGHT_COPPER_BULB);
                    }
                    return;
                }
            }
        });
    }

    private void verifyDripleafNextTick(UUID playerId, BlockKey anchor) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            World world = plugin.getServer().getWorld(anchor.worldId());
            Player player = plugin.getServer().getPlayer(playerId);
            if (world == null || player == null) {
                return;
            }
            boolean complete = DripleafColumnDetector.hasOwnedRunThrough(
                    anchor.y(),
                    DRIPLEAF_HEIGHT,
                    y -> y >= world.getMinHeight()
                            && y < world.getMaxHeight()
                            && isOwnedDripleaf(world, anchor.x(), y, anchor.z(), playerId));
            if (complete) {
                manager.complete(player, BingoTask.BUILD_DRIPLEAF_COLUMN);
            }
        });
    }

    private void recordDripleafFertilizeAttempt(
            UUID playerId, BlockKey anchor, Set<BlockKey> proposedBlocks) {
        DripleafFertilizeAttempt attempt = new DripleafFertilizeAttempt(
                playerId, anchor, Set.copyOf(proposedBlocks), Bukkit.getCurrentTick());
        dripleafFertilizeAttempts.put(playerId, attempt);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!dripleafFertilizeAttempts.remove(playerId, attempt)) {
                return;
            }
            for (BlockKey key : attempt.blocks()) {
                Block block = blockAt(key);
                if (block != null && isDripleaf(block.getType())) {
                    setDripleafOwner(key, playerId);
                }
            }
            verifyDripleafNextTick(playerId, attempt.anchor());
        });
    }

    private boolean isOwnedDripleaf(World world, int x, int y, int z, UUID playerId) {
        BlockKey key = new BlockKey(world.getUID(), x, y, z);
        return playerId.equals(dripleafOwnerByBlock.get(key))
                && isDripleaf(world.getBlockAt(x, y, z).getType());
    }

    private void scheduleRavagerVerification(Player player, Ravager ravager) {
        RavagerAttempt attempt = new RavagerAttempt(player.getUniqueId(), Bukkit.getCurrentTick());
        UUID ravagerId = ravager.getUniqueId();
        ravagerAttempts.put(ravagerId, attempt);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!ravagerAttempts.remove(ravagerId, attempt)) {
                return;
            }
            Entity currentEntity = plugin.getServer().getEntity(ravagerId);
            Player currentPlayer = plugin.getServer().getPlayer(attempt.playerId());
            if (currentEntity instanceof Ravager currentRavager
                    && currentPlayer != null
                    && currentRavager.getStunnedTicks() > 0) {
                manager.complete(currentPlayer, BingoTask.STUN_RAVAGER);
            }
        });
    }

    private void verifyWolfArmourNextTick(Player player, Wolf wolf) {
        WolfArmourAttempt attempt = new WolfArmourAttempt(player.getUniqueId());
        UUID wolfId = wolf.getUniqueId();
        wolfArmourAttempts.put(wolfId, attempt);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!wolfArmourAttempts.remove(wolfId, attempt)) {
                return;
            }
            Entity currentEntity = plugin.getServer().getEntity(wolfId);
            Player currentPlayer = plugin.getServer().getPlayer(attempt.playerId());
            if (currentPlayer != null
                    && currentEntity instanceof Wolf currentWolf
                    && currentWolf.isTamed()
                    && attempt.playerId().equals(currentWolf.getOwnerUniqueId())
                    && currentWolf.getEquipment().getItem(EquipmentSlot.BODY).getType()
                            == Material.WOLF_ARMOR) {
                manager.complete(currentPlayer, BingoTask.ARMOUR_WOLF);
            }
        });
    }

    private UUID findSingleFallenSandOwner(LivingEntity entity) {
        BoundingBox box = entity.getBoundingBox();
        int minX = floor(box.getMinX());
        int maxX = floor(box.getMaxX() - 1.0e-7);
        int minY = floor(box.getMinY());
        int maxY = floor(box.getMaxY() - 1.0e-7);
        int minZ = floor(box.getMinZ());
        int maxZ = floor(box.getMaxZ() - 1.0e-7);
        Set<UUID> owners = new HashSet<>();
        UUID worldId = entity.getWorld().getUID();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockKey block = new BlockKey(worldId, x, y, z);
                    SandBlockOwnership ownership = sandOwnershipByBlock.get(block);
                    if (ownership != null
                            && entity.getWorld().getBlockAt(x, y, z).getType() != Material.SAND) {
                        removeSandOwnership(block);
                        continue;
                    }
                    if (ownership != null && ownership.fell()) {
                        owners.add(ownership.playerId());
                    }
                }
            }
        }
        return owners.size() == 1 ? owners.iterator().next() : null;
    }

    private void setDripleafOwner(BlockKey block, UUID playerId) {
        UUID previousOwner = dripleafOwnerByBlock.put(block, playerId);
        if (previousOwner != null && !previousOwner.equals(playerId)) {
            removeFromOwnerIndex(dripleafBlocksByPlayer, previousOwner, block);
        }
        Set<BlockKey> blocks = dripleafBlocksByPlayer.computeIfAbsent(
                playerId, ignored -> new LinkedHashSet<>());
        blocks.add(block);
        evictOldest(blocks, MAX_DRIPLEAF_BLOCKS_PER_PLAYER, dripleafOwnerByBlock);
    }

    private void removeDripleafOwner(BlockKey block) {
        UUID owner = dripleafOwnerByBlock.remove(block);
        if (owner != null) {
            removeFromOwnerIndex(dripleafBlocksByPlayer, owner, block);
        }
    }

    private void setSandOwnership(BlockKey block, SandBlockOwnership ownership) {
        SandBlockOwnership previous = sandOwnershipByBlock.put(block, ownership);
        if (previous != null && !previous.playerId().equals(ownership.playerId())) {
            removeFromOwnerIndex(sandBlocksByPlayer, previous.playerId(), block);
        }
        Set<BlockKey> blocks = sandBlocksByPlayer.computeIfAbsent(
                ownership.playerId(), ignored -> new LinkedHashSet<>());
        blocks.add(block);
        while (blocks.size() > MAX_SAND_BLOCKS_PER_PLAYER) {
            BlockKey oldest = blocks.iterator().next();
            blocks.remove(oldest);
            SandBlockOwnership current = sandOwnershipByBlock.get(oldest);
            if (current != null && current.playerId().equals(ownership.playerId())) {
                sandOwnershipByBlock.remove(oldest);
            }
        }
    }

    private SandBlockOwnership removeSandOwnership(BlockKey block) {
        SandBlockOwnership ownership = sandOwnershipByBlock.remove(block);
        if (ownership != null) {
            removeFromOwnerIndex(sandBlocksByPlayer, ownership.playerId(), block);
        }
        return ownership;
    }

    private void removeTrackedBlock(BlockKey block) {
        removeDripleafOwner(block);
        removeSandOwnership(block);
    }

    private void clearTransientState(UUID playerId) {
        goatMilkAttempts.remove(playerId);
        copperBulbAttempts.remove(playerId);
        dripleafFertilizeAttempts.remove(playerId);
        pumpkinCarveAttempts.remove(playerId);
        wolfArmourAttempts.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        ravagerAttempts.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        droppedHeadItems.values().removeIf(item -> item.playerId().equals(playerId));
        hatPickupAttempts.values().removeIf(attempt -> attempt.playerId().equals(playerId));
        pendingLecternTurns.values().removeIf(turn -> playerId.equals(turn.playerId()));
        sandDamageAttempts.values().removeIf(attempt -> attempt.playerId().equals(playerId));
    }

    private void pruneTransientState(int currentTick) {
        goatMilkAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        copperBulbAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        dripleafFertilizeAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        ravagerAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        pumpkinCarveAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        hatPickupAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        pendingLecternTurns.values().removeIf(turn -> !isFresh(turn.tick(), currentTick));
        sandDamageAttempts.values().removeIf(attempt -> !isFresh(attempt.tick(), currentTick));
        droppedHeadItems.entrySet().removeIf(entry ->
                currentTick - entry.getValue().tick() > DROPPED_ITEM_RETENTION_TICKS
                        || plugin.getServer().getEntity(entry.getKey()) == null);
        fallingSandOwners.keySet().removeIf(entityId -> plugin.getServer().getEntity(entityId) == null);
    }

    private Block blockAt(BlockKey key) {
        World world = plugin.getServer().getWorld(key.worldId());
        return world == null ? null : world.getBlockAt(key.x(), key.y(), key.z());
    }

    private static void removeOwnedBlocks(
            UUID playerId,
            Map<UUID, Set<BlockKey>> blocksByPlayer,
            Map<BlockKey, UUID> ownerByBlock) {
        Set<BlockKey> blocks = blocksByPlayer.remove(playerId);
        if (blocks != null) {
            for (BlockKey block : blocks) {
                ownerByBlock.remove(block, playerId);
            }
        }
    }

    private static void evictOldest(
            Set<BlockKey> blocks, int maximum, Map<BlockKey, UUID> ownerByBlock) {
        while (blocks.size() > maximum) {
            BlockKey oldest = blocks.iterator().next();
            blocks.remove(oldest);
            ownerByBlock.remove(oldest);
        }
    }

    private static void removeFromOwnerIndex(
            Map<UUID, Set<BlockKey>> blocksByPlayer, UUID playerId, BlockKey block) {
        Set<BlockKey> blocks = blocksByPlayer.get(playerId);
        if (blocks == null) {
            return;
        }
        blocks.remove(block);
        if (blocks.isEmpty()) {
            blocksByPlayer.remove(playerId);
        }
    }

    private static boolean isDripleaf(Material material) {
        return material == Material.BIG_DRIPLEAF || material == Material.BIG_DRIPLEAF_STEM;
    }

    private static boolean isEligibleHeadwear(Material material) {
        return material == Material.CARVED_PUMPKIN || Tag.ITEMS_HEAD_ARMOR.isTagged(material);
    }

    private static boolean isRedstoneTorch(Material material) {
        return material == Material.REDSTONE_TORCH || material == Material.REDSTONE_WALL_TORCH;
    }

    private static boolean isSuffocationDamage(EntityDamageEvent event) {
        return event.getDamageSource().getDamageType().equals(DamageType.IN_WALL);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static boolean isFresh(int earlierTick, int currentTick) {
        int age = currentTick - earlierTick;
        return age >= 0 && age <= MAX_CORRELATION_AGE_TICKS;
    }

    private static boolean hasType(ItemStack item, Material material) {
        return item != null && item.getType() == material;
    }

    private static ItemStack singleItem(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }

    private record WolfArmourAttempt(UUID playerId) {}

    private record GoatMilkAttempt(UUID goatId, EquipmentSlot hand, int tick) {}

    private record CopperBulbAttempt(
            UUID playerId, BlockKey torch, Set<BlockKey> bulbs, int tick) {}

    private record DripleafFertilizeAttempt(
            UUID playerId, BlockKey anchor, Set<BlockKey> blocks, int tick) {}

    private record RavagerAttempt(UUID playerId, int tick) {}

    private record PumpkinCarveAttempt(UUID playerId, BlockKey block, int tick) {}

    private record DroppedHeadItem(UUID playerId, ItemStack item, int tick) {
        boolean equalsIgnoringTick(DroppedHeadItem other) {
            return other != null && playerId.equals(other.playerId) && item.isSimilar(other.item);
        }
    }

    private record HatPickupAttempt(UUID playerId, ItemStack item, int tick) {}

    private record PendingLecternTurn(UUID playerId, int tick) {}

    private record SandBlockOwnership(UUID playerId, boolean fell) {}

    private record SandDamageAttempt(UUID playerId, int tick) {}

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }
}
