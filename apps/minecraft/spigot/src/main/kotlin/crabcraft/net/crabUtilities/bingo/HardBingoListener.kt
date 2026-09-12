package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.entity.EntityFertilizeEggEvent
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Lightable
import org.bukkit.entity.Allay
import org.bukkit.entity.Boat
import org.bukkit.entity.Camel
import org.bukkit.entity.Creeper
import org.bukkit.entity.Donkey
import org.bukkit.entity.Enemy
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Fox
import org.bukkit.entity.Horse
import org.bukkit.entity.Mule
import org.bukkit.entity.PiglinBrute
import org.bukkit.entity.Player
import org.bukkit.entity.Sniffer
import org.bukkit.entity.ZombieVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType

/** Event-driven detectors for the harder Bingo #1 card. */
class HardBingoListener @JvmOverloads constructor(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val hornProgress: BiConsumer<Player, HornProgress> = BiConsumer { _, _ -> },
) : BingoDetector {
    private val hornsByPlayer = HashMap<UUID, MutableSet<String>>()
    private val oreByBlock = HashMap<BlockKey, OrePlacement>()
    private val oresByPlayer = HashMap<UUID, MutableSet<BlockKey>>()
    private val scaffoldingOwnerByBlock = HashMap<BlockKey, UUID>()
    private val scaffoldingByPlayer = HashMap<UUID, MutableSet<BlockKey>>()
    private val anvilOwnerByBlock = HashMap<BlockKey, UUID>()
    private val anvilsByPlayer = HashMap<UUID, MutableSet<BlockKey>>()
    private val fallingAnvilOwners = HashMap<UUID, UUID>()
    private val anvilDamageByVictim = HashMap<UUID, TimedPlayer>()
    private val boatOwners = HashMap<UUID, UUID>()
    private val projectileShots = HashMap<UUID, ProjectileShot>()
    private val pendingSnifferEggs = ArrayList<PendingSnifferEgg>()
    private val snifferEggOwners = HashMap<UUID, TimedPlayer>()
    private val droppedAxes = HashMap<UUID, DroppedAxe>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrown(event: StructureGrowEvent) {
        val player = event.player
        if (player != null && event.isFromBonemeal && event.world.environment == World.Environment.NETHER
            && tracking.test(player, BingoTask.GROW_TREE_IN_NETHER)) {
            completion.accept(player, BingoTask.GROW_TREE_IN_NETHER)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onHornInteract(event: PlayerInteractEvent) {
        if (!event.action.isRightClick || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return
        val player = event.player
        if (!tracking.test(player, BingoTask.PLAY_FIVE_GOAT_HORNS)) return
        val item = event.item
        val instrument = instrumentKey(item)
        if (instrument == null || item == null || player.getCooldown(item) > 0) return
        val usedHorn = item.clone()
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmHornPlayed(player.uniqueId, instrument, usedHorn) })
    }

    private fun confirmHornPlayed(playerId: UUID, instrument: String, usedHorn: ItemStack) {
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !tracking.test(player, BingoTask.PLAY_FIVE_GOAT_HORNS)
            || player.getCooldown(usedHorn) <= 0) return
        recordHorn(player, instrument)
    }

    private fun recordHorn(player: Player, instrument: String) {
        val horns = hornsByPlayer.computeIfAbsent(player.uniqueId) { HashSet() }
        if (!horns.add(instrument)) return
        hornProgress.accept(player, HornProgress(instrument, horns.size))
        if (horns.size >= 5) completion.accept(player, BingoTask.PLAY_FIVE_GOAT_HORNS)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        if (!event.canBuild()) return
        val player = event.player
        val block = event.blockPlaced
        val key = BlockKey.from(block)
        removeTrackedBlock(key)
        val family = OreFamily.from(block.type)
        if (family != null && tracking.test(player, BingoTask.CONNECT_ALL_ORE_TYPES)) {
            setOre(key, OrePlacement(player.uniqueId, family))
            if (hasAllOreFamilies(player.uniqueId, key)) completion.accept(player, BingoTask.CONNECT_ALL_ORE_TYPES)
        }
        if (block.type == Material.SCAFFOLDING && tracking.test(player, BingoTask.COLLAPSE_SCAFFOLDING_TOWER)) {
            setOwnedBlock(key, player.uniqueId, scaffoldingOwnerByBlock, scaffoldingByPlayer, MAX_OWNED_BLOCKS_PER_PLAYER)
        }
        if (isAnvil(block.type) && tracking.test(player, BingoTask.KILL_HOSTILE_WITH_ANVIL)) {
            setOwnedBlock(key, player.uniqueId, anvilOwnerByBlock, anvilsByPlayer, MAX_OWNED_BLOCKS_PER_PLAYER)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBroken(event: BlockBreakEvent) {
        val block = event.block
        val key = BlockKey.from(block)
        val player = event.player
        if (block.type == Material.SCAFFOLDING && tracking.test(player, BingoTask.COLLAPSE_SCAFFOLDING_TOWER)
            && hasOwnedScaffoldingTower(player.uniqueId, block)) {
            completion.accept(player, BingoTask.COLLAPSE_SCAFFOLDING_TOWER)
        }
        removeTrackedBlock(key)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().forEach { removeTrackedBlock(BlockKey.from(it)) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().forEach { removeTrackedBlock(BlockKey.from(it)) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        removePistonBlocks(event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        removePistonBlocks(event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onResurrect(event: EntityResurrectEvent) {
        val player = event.entity
        if (player is Player) completion.accept(player, BingoTask.ACTIVATE_TOTEM)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityBred(event: EntityBreedEvent) {
        val player = event.breeder as? Player ?: return
        if (event.entity is Mule
            && ((event.mother is Horse && event.father is Donkey) || (event.mother is Donkey && event.father is Horse))) {
            completion.accept(player, BingoTask.BREED_MULE)
        }
        val fox = event.entity
        if (fox is Fox && tracking.test(player, BingoTask.BREED_TRUSTING_FOX)) {
            val foxId = fox.uniqueId
            val playerId = player.uniqueId
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val current = Bukkit.getEntity(foxId)
                val currentPlayer = Bukkit.getPlayer(playerId)
                if (current is Fox && currentPlayer != null
                    && (isTrustedBy(current.firstTrustedPlayer, playerId) || isTrustedBy(current.secondTrustedPlayer, playerId))) {
                    completion.accept(currentPlayer, BingoTask.BREED_TRUSTING_FOX)
                }
            })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityPlaced(event: EntityPlaceEvent) {
        val player = event.player
        if (player != null && event.entity is Boat && tracking.test(player, BingoTask.TWO_CREEPERS_ONE_BOAT)) {
            boatOwners[event.entity.uniqueId] = player.uniqueId
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVehicleEntered(event: VehicleEnterEvent) {
        val boat = event.vehicle as? Boat ?: return
        if (event.entered !is Creeper) return
        val ownerId = boatOwners[boat.uniqueId] ?: return
        val boatId = boat.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val current = Bukkit.getEntity(boatId)
            val owner = Bukkit.getPlayer(ownerId)
            if (owner != null && current is Boat && current.passengers.count { it is Creeper } >= 2) {
                completion.accept(owner, BingoTask.TWO_CREEPERS_ONE_BOAT)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunched(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val player = projectile.shooter
        if (player is Player && tracking.test(player, BingoTask.IGNITE_CAMPFIRE_FROM_DISTANCE)) {
            projectileShots[projectile.uniqueId] = ProjectileShot(player.uniqueId, projectile.location.clone(), Bukkit.getCurrentTick())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        val shot = projectileShots.remove(projectile.uniqueId)
        val block = event.hitBlock
        if (shot == null || block == null || !isCampfire(block.type) || projectile.fireTicks <= 0
            || !sameWorld(shot.origin, block.location)
            || shot.origin.distanceSquared(block.location.toCenterLocation()) < CAMPFIRE_DISTANCE_SQUARED) return
        val key = BlockKey.from(block)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val current = blockAt(key)
            val player = Bukkit.getPlayer(shot.playerId)
            val lightable = current?.blockData
            if (player != null && lightable is Lightable && lightable.isLit) {
                completion.accept(player, BingoTask.IGNITE_CAMPFIRE_FROM_DISTANCE)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSnifferFertilised(event: EntityFertilizeEggEvent) {
        val breeder = event.breeder
        if (breeder != null && event.entity is Sniffer && event.mother is Sniffer && event.father is Sniffer
            && tracking.test(breeder, BingoTask.BREED_SNIFFERS_COLLECT_EGG)) {
            pruneTransientState(Bukkit.getCurrentTick())
            pendingSnifferEggs.add(PendingSnifferEgg(breeder.uniqueId, event.entity.location.clone(), Bukkit.getCurrentTick()))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemSpawned(event: ItemSpawnEvent) {
        if (event.entity.itemStack.type != Material.SNIFFER_EGG) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientState(tick)
        val matchingPlayers = HashSet<UUID>()
        for (pending in pendingSnifferEggs) {
            if (isFresh(pending.tick, tick, SHORT_CORRELATION_TICKS) && sameWorld(pending.location, event.location)
                && pending.location.distanceSquared(event.location) <= 16.0) {
                matchingPlayers.add(pending.playerId)
            }
        }
        if (matchingPlayers.size == 1) snifferEggOwners[event.entity.uniqueId] = TimedPlayer(matchingPlayers.iterator().next(), tick)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemPickedUp(event: EntityPickupItemEvent) {
        val item = event.item
        val entity = event.entity
        if (entity is Player) {
            val eggOwner = snifferEggOwners.remove(item.uniqueId)
            if (eggOwner != null && eggOwner.playerId == entity.uniqueId) {
                completion.accept(entity, BingoTask.BREED_SNIFFERS_COLLECT_EGG)
            }
        }
        if (entity is PiglinBrute) {
            val axe = droppedAxes[item.uniqueId] ?: return
            val bruteId = entity.uniqueId
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val current = Bukkit.getEntity(bruteId)
                val player = Bukkit.getPlayer(axe.playerId)
                if (current is PiglinBrute && player != null && current.equipment.itemInMainHand.isSimilar(axe.item)) {
                    droppedAxes.remove(item.uniqueId)
                    completion.accept(player, BingoTask.EQUIP_PIGLIN_BRUTE_AXE)
                }
            })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onZombieVillagerCured(event: EntityTransformEvent) {
        if (event.transformReason != EntityTransformEvent.TransformReason.CURED) return
        val zombie = event.entity as? ZombieVillager ?: return
        val conversionPlayer = zombie.conversionPlayer ?: return
        val player = Bukkit.getPlayer(conversionPlayer.uniqueId)
        if (player != null) completion.accept(player, BingoTask.CURE_ZOMBIE_VILLAGER)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPotionEffect(event: EntityPotionEffectEvent) {
        val player = event.entity
        if (player is Player && event.cause == EntityPotionEffectEvent.Cause.AXOLOTL
            && event.modifiedType == PotionEffectType.REGENERATION) {
            completion.accept(player, BingoTask.GAIN_AXOLOTL_REGENERATION)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemDropped(event: PlayerDropItemEvent) {
        val stack = event.itemDrop.itemStack
        if (stack.type == Material.GOLDEN_AXE && stack.enchantments.isNotEmpty()
            && tracking.test(event.player, BingoTask.EQUIP_PIGLIN_BRUTE_AXE)) {
            pruneTransientState(Bukkit.getCurrentTick())
            droppedAxes[event.itemDrop.uniqueId] = DroppedAxe(event.player.uniqueId, singleItem(stack), Bukkit.getCurrentTick())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAllayInteracted(event: PlayerInteractEntityEvent) {
        val allay = event.rightClicked as? Allay ?: return
        if (event.hand != EquipmentSlot.HAND || event.player.inventory.itemInMainHand.type != Material.AMETHYST_SHARD
            || !allay.isDancing || !allay.canDuplicate() || !tracking.test(event.player, BingoTask.DUPLICATE_ALLAY)) return
        val previousAllays = nearbyAllayIds(allay)
        val allayId = allay.uniqueId
        val playerId = event.player.uniqueId
        val location = allay.location.clone()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val original = Bukkit.getEntity(allayId)
            val player = Bukkit.getPlayer(playerId)
            if (player == null || original !is Allay || original.canDuplicate()) return@Runnable
            val newAllayExists = original.world.getNearbyEntities(location, 4.0, 4.0, 4.0)
                .filterIsInstance<Allay>().any { !previousAllays.contains(it.uniqueId) }
            if (newAllayExists) completion.accept(player, BingoTask.DUPLICATE_ALLAY)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageByEntityEvent) {
        val falling = event.damager as? FallingBlock ?: return
        if (event.entity !is Enemy || !isAnvil(falling.blockData.material)) return
        val owner = fallingAnvilOwners[falling.uniqueId]
        if (owner != null) anvilDamageByVictim[event.entity.uniqueId] = TimedPlayer(owner, Bukkit.getCurrentTick())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity !is Enemy) return
        val tick = Bukkit.getCurrentTick()
        val anvilAttempt = anvilDamageByVictim.remove(event.entity.uniqueId)
        if (anvilAttempt != null && isFresh(anvilAttempt.tick, tick, SHORT_CORRELATION_TICKS)) {
            val owner = Bukkit.getPlayer(anvilAttempt.playerId)
            if (owner != null) completion.accept(owner, BingoTask.KILL_HOSTILE_WITH_ANVIL)
        }
        val killer = event.entity.killer
        if (killer != null && killer.vehicle is Camel) completion.accept(killer, BingoTask.KILL_HOSTILE_FROM_CAMEL)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFallingBlockSpawned(event: EntitySpawnEvent) {
        val falling = event.entity as? FallingBlock ?: return
        if (!isAnvil(falling.blockData.material)) return
        val origin = falling.origin ?: return
        val owner = removeOwnedBlock(BlockKey.from(origin.block), anvilOwnerByBlock, anvilsByPlayer)
        if (owner != null) fallingAnvilOwners[falling.uniqueId] = owner
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFallingBlockChanged(event: EntityChangeBlockEvent) {
        val falling = event.entity as? FallingBlock ?: return
        if (!isAnvil(falling.blockData.material)) return
        val fallingId = falling.uniqueId
        var owner = fallingAnvilOwners[fallingId]
        if (event.to.isAir) {
            if (owner == null) {
                owner = removeOwnedBlock(BlockKey.from(event.block), anvilOwnerByBlock, anvilsByPlayer)
                if (owner != null) fallingAnvilOwners[fallingId] = owner
            }
            return
        }
        fallingAnvilOwners.remove(fallingId)
        if (owner != null && isAnvil(event.to)) {
            setOwnedBlock(BlockKey.from(event.block), owner, anvilOwnerByBlock, anvilsByPlayer, MAX_OWNED_BLOCKS_PER_PLAYER)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val id = event.entity.uniqueId
        anvilDamageByVictim.remove(id)
        boatOwners.remove(id)
        projectileShots.remove(id)
        fallingAnvilOwners.remove(id)
        snifferEggOwners.remove(id)
        droppedAxes.remove(id)
    }

    override fun resetPlayer(playerId: UUID) {
        hornsByPlayer.remove(playerId)
        removeOreBlocks(playerId)
        removeOwnedBlocks(playerId, scaffoldingOwnerByBlock, scaffoldingByPlayer)
        removeOwnedBlocks(playerId, anvilOwnerByBlock, anvilsByPlayer)
        fallingAnvilOwners.values.removeIf(playerId::equals)
        anvilDamageByVictim.values.removeIf { it.playerId == playerId }
        boatOwners.values.removeIf(playerId::equals)
        projectileShots.values.removeIf { it.playerId == playerId }
        pendingSnifferEggs.removeIf { it.playerId == playerId }
        snifferEggOwners.values.removeIf { it.playerId == playerId }
        droppedAxes.values.removeIf { it.playerId == playerId }
    }

    override fun clear() {
        hornsByPlayer.clear()
        oreByBlock.clear()
        oresByPlayer.clear()
        scaffoldingOwnerByBlock.clear()
        scaffoldingByPlayer.clear()
        anvilOwnerByBlock.clear()
        anvilsByPlayer.clear()
        fallingAnvilOwners.clear()
        anvilDamageByVictim.clear()
        boatOwners.clear()
        projectileShots.clear()
        pendingSnifferEggs.clear()
        snifferEggOwners.clear()
        droppedAxes.clear()
    }

    private fun hasAllOreFamilies(playerId: UUID, anchor: BlockKey): Boolean {
        val anchorPlacement = oreByBlock[anchor]
        if (anchorPlacement == null || anchorPlacement.playerId != playerId) return false
        val visited = HashSet<BlockKey>()
        val families = HashSet<OreFamily>()
        val queue = ArrayDeque<BlockKey>()
        queue.add(anchor)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val placement = oreByBlock[current]
            if (placement == null || placement.playerId != playerId) continue
            families.add(placement.family)
            if (families.size == OreFamily.entries.size) return true
            for (face in FACES) queue.add(current.relative(face))
        }
        return false
    }

    private fun hasOwnedScaffoldingTower(playerId: UUID, base: Block): Boolean {
        for (offset in 0 until SCAFFOLDING_HEIGHT) {
            val current = base.getRelative(BlockFace.UP, offset)
            if (current.type != Material.SCAFFOLDING || playerId != scaffoldingOwnerByBlock[BlockKey.from(current)]) return false
        }
        return true
    }

    private fun setOre(key: BlockKey, placement: OrePlacement) {
        val previous = oreByBlock.put(key, placement)
        if (previous != null && previous.playerId != placement.playerId) removeFromOwnerIndex(oresByPlayer, previous.playerId, key)
        val blocks = oresByPlayer.computeIfAbsent(placement.playerId) { LinkedHashSet() }
        blocks.add(key)
        while (blocks.size > MAX_ORE_BLOCKS_PER_PLAYER) {
            val oldest = blocks.iterator().next()
            blocks.remove(oldest)
            val current = oreByBlock[oldest]
            if (current != null && current.playerId == placement.playerId) oreByBlock.remove(oldest)
        }
    }

    private fun removeOre(key: BlockKey) {
        val placement = oreByBlock.remove(key)
        if (placement != null) removeFromOwnerIndex(oresByPlayer, placement.playerId, key)
    }

    private fun removeOreBlocks(playerId: UUID) {
        val blocks = oresByPlayer.remove(playerId) ?: return
        for (block in blocks) {
            val placement = oreByBlock[block]
            if (placement != null && placement.playerId == playerId) oreByBlock.remove(block)
        }
    }

    private fun removeTrackedBlock(key: BlockKey) {
        removeOre(key)
        removeOwnedBlock(key, scaffoldingOwnerByBlock, scaffoldingByPlayer)
        removeOwnedBlock(key, anvilOwnerByBlock, anvilsByPlayer)
    }

    private fun removePistonBlocks(blocks: List<Block>, direction: BlockFace) {
        for (block in blocks) {
            removeTrackedBlock(BlockKey.from(block))
            removeTrackedBlock(BlockKey.from(block.getRelative(direction)))
        }
    }

    private fun pruneTransientState(tick: Int) {
        pendingSnifferEggs.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
        snifferEggOwners.entries.removeIf { tick - it.value.tick > ITEM_RETENTION_TICKS || Bukkit.getEntity(it.key) == null }
        droppedAxes.entries.removeIf { tick - it.value.tick > ITEM_RETENTION_TICKS || Bukkit.getEntity(it.key) == null }
        projectileShots.entries.removeIf { tick - it.value.tick > ITEM_RETENTION_TICKS || Bukkit.getEntity(it.key) == null }
        anvilDamageByVictim.values.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
    }

    private fun blockAt(key: BlockKey): Block? = Bukkit.getWorld(key.worldId)?.getBlockAt(key.x, key.y, key.z)

    private enum class OreFamily {
        COAL, IRON, COPPER, GOLD, REDSTONE, LAPIS, DIAMOND, EMERALD, NETHER_QUARTZ, NETHER_GOLD, ANCIENT_DEBRIS;

        companion object {
            fun from(material: Material): OreFamily? = when (material) {
                Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE -> COAL
                Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE -> IRON
                Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE -> COPPER
                Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE -> GOLD
                Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE -> REDSTONE
                Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE -> LAPIS
                Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> DIAMOND
                Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> EMERALD
                Material.NETHER_QUARTZ_ORE -> NETHER_QUARTZ
                Material.NETHER_GOLD_ORE -> NETHER_GOLD
                Material.ANCIENT_DEBRIS -> ANCIENT_DEBRIS
                else -> null
            }
        }
    }

    private data class BlockKey(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
        fun relative(face: BlockFace): BlockKey = BlockKey(worldId, x + face.modX, y + face.modY, z + face.modZ)
        companion object {
            fun from(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
        }
    }

    private data class OrePlacement(val playerId: UUID, val family: OreFamily)
    data class HornProgress(private val instrument: String, private val uniqueCount: Int) {
        fun instrument(): String = instrument
        fun uniqueCount(): Int = uniqueCount
    }
    private data class TimedPlayer(val playerId: UUID, val tick: Int)
    private data class ProjectileShot(val playerId: UUID, val origin: Location, val tick: Int)
    private data class PendingSnifferEgg(val playerId: UUID, val location: Location, val tick: Int)
    private data class DroppedAxe(val playerId: UUID, val item: ItemStack, val tick: Int)

    companion object {
        private const val MAX_OWNED_BLOCKS_PER_PLAYER = 2_048
        private const val MAX_ORE_BLOCKS_PER_PLAYER = 512
        private const val SHORT_CORRELATION_TICKS = 3
        private const val ITEM_RETENTION_TICKS = 20 * 60 * 10
        private const val SCAFFOLDING_HEIGHT = 64
        private const val CAMPFIRE_DISTANCE_SQUARED = 100.0
        private val FACES = arrayOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

        private fun instrumentKey(item: ItemStack?): String? {
            if (item == null || item.type != Material.GOAT_HORN) return null
            val instrument = item.getData(DataComponentTypes.INSTRUMENT) ?: return null
            val key = RegistryAccess.registryAccess().getRegistry(RegistryKey.INSTRUMENT).getKey(instrument)
            return serialiseInstrumentKey(key)
        }

        @JvmStatic
        fun serialiseInstrumentKey(key: NamespacedKey?): String? = key?.asString()

        private fun nearbyAllayIds(allay: Allay): Set<UUID> {
            val ids = HashSet<UUID>()
            ids.add(allay.uniqueId)
            allay.getNearbyEntities(4.0, 4.0, 4.0).filterIsInstance<Allay>().map { it.uniqueId }.forEach(ids::add)
            return ids
        }

        private fun setOwnedBlock(key: BlockKey, playerId: UUID, owners: MutableMap<BlockKey, UUID>,
                                  blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>, maximum: Int) {
            val previous = owners.put(key, playerId)
            if (previous != null && previous != playerId) removeFromOwnerIndex(blocksByPlayer, previous, key)
            val blocks = blocksByPlayer.computeIfAbsent(playerId) { LinkedHashSet() }
            blocks.add(key)
            while (blocks.size > maximum) {
                val oldest = blocks.iterator().next()
                blocks.remove(oldest)
                owners.remove(oldest, playerId)
            }
        }

        private fun removeOwnedBlock(key: BlockKey, owners: MutableMap<BlockKey, UUID>,
                                     blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>): UUID? {
            val owner = owners.remove(key)
            if (owner != null) removeFromOwnerIndex(blocksByPlayer, owner, key)
            return owner
        }

        private fun removeOwnedBlocks(playerId: UUID, owners: MutableMap<BlockKey, UUID>,
                                      blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>) {
            val blocks = blocksByPlayer.remove(playerId) ?: return
            for (block in blocks) owners.remove(block, playerId)
        }

        private fun removeFromOwnerIndex(blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>, playerId: UUID, key: BlockKey) {
            val blocks = blocksByPlayer[playerId] ?: return
            blocks.remove(key)
            if (blocks.isEmpty()) blocksByPlayer.remove(playerId)
        }

        private fun isAnvil(material: Material): Boolean =
            material == Material.ANVIL || material == Material.CHIPPED_ANVIL || material == Material.DAMAGED_ANVIL

        private fun isCampfire(material: Material): Boolean = material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE

        private fun sameWorld(first: Location, second: Location): Boolean = first.world != null && first.world == second.world

        private fun isTrustedBy(tamer: org.bukkit.entity.AnimalTamer?, playerId: UUID): Boolean = tamer != null && playerId == tamer.uniqueId

        private fun isFresh(earlier: Int, current: Int, maximumAge: Int): Boolean {
            val age = current - earlier
            return age >= 0 && age <= maximumAge
        }

        private fun singleItem(stack: ItemStack): ItemStack {
            val copy = stack.clone()
            copy.amount = 1
            return copy
        }
    }
}
