package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.block.BlockBreakBlockEvent
import io.papermc.paper.event.block.TargetHitEvent
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import io.papermc.paper.event.entity.EntityInsideBlockEvent
import io.papermc.paper.event.player.PlayerTradeEvent
import java.util.ArrayList
import java.util.Arrays
import java.util.EnumMap
import java.util.EnumSet
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Jukebox
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.SideChaining
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Shelf
import org.bukkit.damage.DamageType
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Bee
import org.bukkit.entity.Bogged
import org.bukkit.entity.Creeper
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Mob
import org.bukkit.entity.Parrot
import org.bukkit.entity.Pig
import org.bukkit.entity.Piglin
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Trident
import org.bukkit.entity.Turtle
import org.bukkit.entity.Villager
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BellRingEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.ExplosionPrimeEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for Bingo #2. */
class BingoCardTwoListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>
) : BingoDetector {

    private val projectileShots = HashMap<UUID, ProjectileShot>()
    private val bellAttempts = HashMap<BellAttemptKey, Int>()
    private val berryBushOwners = HashMap<BlockKey, UUID>()
    private val berryBushesByPlayer = HashMap<UUID, MutableSet<BlockKey>>()
    private val berryDamage = HashMap<UUID, TimedPlayer>()
    private val fireOwners = HashMap<BlockKey, UUID>()
    private val firesByPlayer = HashMap<UUID, MutableSet<BlockKey>>()
    private val minecartContacts = HashMap<UUID, MinecartContact>()
    private val primedMinecarts = HashMap<UUID, PrimedMinecart>()
    private val turtleScutes = HashMap<UUID, ScuteProvenance>()
    private val shelfAttempts = HashMap<UUID, ShelfAttempt>()
    private val pigShearAttempts = HashMap<UUID, PigShearAttempt>()
    private val pendingSelfArrowTotems = HashMap<UUID, TimedPlayer>()
    private val droppedArmour = HashMap<UUID, DroppedArmour>()
    private val piglinPickups = HashMap<UUID, EnumMap<EquipmentSlot, PiglinPickup>>()
    private val piglinArmourOwners = HashMap<UUID, EnumMap<EquipmentSlot, UUID>>()
    private val leashedBeeOwners = HashMap<UUID, UUID>()
    private val pressurePlateOwners = HashMap<BlockKey, UUID>()
    private val pressurePlatesByPlayer = HashMap<UUID, MutableSet<BlockKey>>()
    private val pressurePlateTriggers = HashMap<BlockKey, TimedPlayer>()
    private val playerGenerations = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var lastPruneTick = Int.MIN_VALUE

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntitySheared(event: PlayerShearEntityEvent) {
        if (event.getEntity() is Bogged
                && tracking.test(event.getPlayer(), BingoTask.SHEAR_BOGGED)) {
            completion.accept(event.getPlayer(), BingoTask.SHEAR_BOGGED)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunched(event: org.bukkit.event.entity.ProjectileLaunchEvent) {
        val projectile = event.getEntity()
        val player = projectile.getShooter() as? Player
        if (player == null
                || (!tracking.test(player, BingoTask.RING_BELL_PROJECTILE)
                        && !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)
                        && !tracking.test(player, BingoTask.SELF_ARROW_TOTEM))) {
            return
        }
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        putBounded(
                projectileShots,
                projectile.getUniqueId(),
                ProjectileShot(player.getUniqueId(), projectile.getLocation().clone(), tick))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileHit(event: org.bukkit.event.entity.ProjectileHitEvent) {
        val block = event.getHitBlock()
        if (block == null || block.getType() != Material.BELL) return
        val shot = projectileShots.remove(event.getEntity().getUniqueId())
        if (shot == null
                || !sameWorld(shot.origin(), block.getLocation())
                || shot.origin().distanceSquared(block.getLocation().toCenterLocation()) < TEN_BLOCKS_SQUARED) {
            return
        }
        val key = BellAttemptKey(BlockKey.from(block), shot.playerId())
        val tick = Bukkit.getCurrentTick()
        putBounded(bellAttempts, key, tick)
        Bukkit.getScheduler().runTask(plugin, Runnable { bellAttempts.remove(key, tick) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBellRung(event: BellRingEvent) {
        val tick = Bukkit.getCurrentTick()
        val ringer = event.getEntity()
        if (ringer is Player) {
            val key = BellAttemptKey(BlockKey.from(event.getBlock()), ringer.getUniqueId())
            val attemptTick = bellAttempts.remove(key)
            if (attemptTick != null
                    && isFresh(attemptTick, tick, 1)
                    && tracking.test(ringer, BingoTask.RING_BELL_PROJECTILE)) {
                completion.accept(ringer, BingoTask.RING_BELL_PROJECTILE)
            }
            return
        }

        if (event.getEntity() != null) return
        for (face in HORIZONTAL_FACES) {
            val plate = event.getBlock().getRelative(face)
            val plateKey = BlockKey.from(plate)
            val trigger = pressurePlateTriggers.remove(plateKey)
            if (trigger == null
                    || trigger.tick() != tick
                    || !Tag.PRESSURE_PLATES.isTagged(plate.getType())
                    || !isPowered(plate)) {
                continue
            }
            val player = Bukkit.getPlayer(trigger.playerId())
            if (player != null && tracking.test(player, BingoTask.CREEPER_RINGS_BELL)) {
                completion.accept(player, BingoTask.CREEPER_RINGS_BELL)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetHit(event: TargetHitEvent) {
        val projectile = event.getEntity()
        val shot = projectileShots.remove(projectile.getUniqueId())
        val target = event.getHitBlock()
        if (shot == null
                || target == null
                || !(projectile is AbstractArrow)
                || projectile is Trident
                || event.getSignalStrength() <= 0
                || !sameWorld(shot.origin(), target.getLocation())
                || shot.origin().distanceSquared(target.getLocation().toCenterLocation()) < TEN_BLOCKS_SQUARED) {
            return
        }
        val player = Bukkit.getPlayer(shot.playerId())
        if (player == null || !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)) return

        val targetKey = BlockKey.from(target)
        val doors = LinkedHashSet<DoorCandidate>()
        for (face in FACES) {
            val adjacent = canonicalDoor(target.getRelative(face))
            val door = adjacent?.getBlockData() as? Door
            if (adjacent != null
                    && door != null
                    && !door.isOpen()
                    && !door.isPowered()) {
                doors.add(DoorCandidate(BlockKey.from(adjacent), adjacent.getType()))
            }
        }
        if (doors.isEmpty()) return
        val token = attemptToken(shot.playerId())
        Bukkit.getScheduler().runTask(
                plugin, Runnable { confirmTargetDoor(shot.playerId(), token, targetKey, doors) })
    }

    private fun confirmTargetDoor(playerId: UUID, token: AttemptToken, targetKey: BlockKey, doors: MutableSet<DoorCandidate>) {
        val player = Bukkit.getPlayer(playerId)
        val target = blockAt(targetKey)
        if (player == null
                || !isCurrent(playerId, token)
                || target == null
                || target.getType() != Material.TARGET
                || !isPowered(target)
                || !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)) {
            return
        }
        for (candidate in doors) {
            val block = blockAt(candidate.block())
            val door = block?.getBlockData() as? Door
            if (block != null
                    && block.getType() == candidate.material()
                    && door != null
                    && door.isOpen()
                    && door.isPowered()) {
                completion.accept(player, BingoTask.TARGET_OPENS_DOOR)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        if (!event.canBuild()) return
        val block = event.getBlockPlaced()
        val key = BlockKey.from(block)
        val player = event.getPlayer()
        removeOwnedBlock(key, berryBushOwners, berryBushesByPlayer)
        // Paper follows a successful flint/fire-charge BlockIgniteEvent with a captured
        // BlockPlaceEvent. Keep only the same player's newly recorded fire attribution.
        if (!shouldRetainFireOwnerAfterPlaceEvent(
                Tag.FIRE.isTagged(block.getType()),
                player.getUniqueId(),
                fireOwners.get(key))) {
            removeOwnedBlock(key, fireOwners, firesByPlayer)
        }
        removeOwnedBlock(key, pressurePlateOwners, pressurePlatesByPlayer)
        pressurePlateTriggers.remove(key)
        if (block.getType() == Material.SWEET_BERRY_BUSH
                && tracking.test(player, BingoTask.BERRY_BUSH_KILL)) {
            setOwnedBlock(
                    key,
                    player.getUniqueId(),
                    berryBushOwners,
                    berryBushesByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER)
        }
        if (Tag.PRESSURE_PLATES.isTagged(block.getType())
                && tracking.test(player, BingoTask.CREEPER_RINGS_BELL)) {
            setOwnedBlock(
                    key,
                    player.getUniqueId(),
                    pressurePlateOwners,
                    pressurePlatesByPlayer,
                    MAX_OWNED_BLOCKS_PER_PLAYER)
        }

        if (block.getType() == Material.WET_SPONGE
                && event.getItemInHand().getType() == Material.WET_SPONGE
                && block.getWorld().getEnvironment() == World.Environment.NETHER
                && tracking.test(player, BingoTask.DRY_SPONGE_NETHER)) {
            val playerId = player.getUniqueId()
            val token = attemptToken(playerId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val current = blockAt(key)
                val currentPlayer = Bukkit.getPlayer(playerId)
                if (current != null
                        && current.getType() == Material.SPONGE
                        && currentPlayer != null
                        && isCurrent(playerId, token)
                        && tracking.test(currentPlayer, BingoTask.DRY_SPONGE_NETHER)) {
                    completion.accept(currentPlayer, BingoTask.DRY_SPONGE_NETHER)
                }
            })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBroken(event: BlockBreakEvent) {
        removeTrackedBlock(BlockKey.from(event.getBlock()))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockBrokenByBlock(event: BlockBreakBlockEvent) {
        removeTrackedBlock(BlockKey.from(event.getBlock()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) {
        event.blockList().forEach({ block -> removeTrackedBlock(BlockKey.from(block)) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) {
        event.blockList().forEach({ block -> removeTrackedBlock(BlockKey.from(block)) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        removePistonBlocks(event.getBlocks(), event.getDirection())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        removePistonBlocks(event.getBlocks(), event.getDirection())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFaded(event: BlockFadeEvent) {
        removeOwnedBlock(BlockKey.from(event.getBlock()), fireOwners, firesByPlayer)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBerryBushDamage(event: EntityDamageByBlockEvent) {
        val damager = event.getDamager()
        if (!(event.getEntity() is Enemy)
                || damager == null
                || !DamageType.SWEET_BERRY_BUSH.equals(event.getDamageSource().getDamageType())
                || event.getFinalDamage() <= 0.0) {
            return
        }
        berryDamage.remove(event.getEntity().getUniqueId())
        val owner = berryBushOwners.get(BlockKey.from(damager))
        val ownerPlayer = if (owner == null) null else Bukkit.getPlayer(owner)
        if (ownerPlayer != null && tracking.test(ownerPlayer, BingoTask.BERRY_BUSH_KILL)) {
            putBounded(
                    berryDamage,
                    event.getEntity().getUniqueId(),
                    TimedPlayer(owner!!, Bukkit.getCurrentTick()))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!(event.getEntity() is Enemy)) return
        val attempt = berryDamage.remove(event.getEntity().getUniqueId())
        if (attempt == null
                || !isFresh(attempt.tick(), Bukkit.getCurrentTick(), SHORT_CORRELATION_TICKS)
                || !DamageType.SWEET_BERRY_BUSH.equals(event.getDamageSource().getDamageType())) {
            return
        }
        val owner = Bukkit.getPlayer(attempt.playerId())
        if (owner != null && tracking.test(owner, BingoTask.BERRY_BUSH_KILL)) {
            completion.accept(owner, BingoTask.BERRY_BUSH_KILL)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJukeboxInteracted(event: PlayerInteractEvent) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) return
        val clickedBlock = event.getClickedBlock() ?: return
        val jukebox = clickedBlock.getState() as? Jukebox ?: return
        if (jukebox.hasRecord()) return
        val item = event.getItem()
        val player = event.getPlayer()
        if (item == null
                || !item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE)
                || !tracking.test(player, BingoTask.FIVE_PARROTS_DANCE)) {
            return
        }
        val key = BlockKey.from(clickedBlock)
        val playerId = player.getUniqueId()
        val token = attemptToken(playerId)
        val inserted = singleItem(item)
        Bukkit.getScheduler().runTask(
                plugin, Runnable { confirmParrotsDancing(playerId, token, key, inserted) })
    }

    private fun confirmParrotsDancing(playerId: UUID, token: AttemptToken, key: BlockKey, inserted: ItemStack) {
        val player = Bukkit.getPlayer(playerId)
        val block = blockAt(key)
        if (player == null
                || block == null
                || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.FIVE_PARROTS_DANCE)) {
            return
        }
        val jukebox = block.getState() as? Jukebox ?: return
        if (!jukebox.isPlaying() || !jukebox.getRecord().isSimilar(inserted)) return
        val centre = block.getLocation().toCenterLocation()
        val variants = EnumSet.noneOf(Parrot.Variant::class.java)
        block.getWorld().getNearbyEntities(
                        org.bukkit.util.BoundingBox.of(block).expand(3.0)).stream()
                .filter(Parrot::class.java::isInstance)
                .map(Parrot::class.java::cast)
                .filter({ parrot -> parrot.getLocation().distanceSquared(centre) < PARROT_DANCE_DISTANCE_SQUARED })
                .map(Parrot::getVariant)
                .forEach(variants::add)
        if (variants.size == Parrot.Variant.entries.size) {
            completion.accept(player, BingoTask.FIVE_PARROTS_DANCE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockIgnited(event: BlockIgniteEvent) {
        val player = event.getIgnitingEntity() as? Player
        val key = BlockKey.from(event.getBlock())
        removeOwnedBlock(key, fireOwners, firesByPlayer)
        if ((event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL
                        && event.getCause() != BlockIgniteEvent.IgniteCause.FIREBALL)
                || player == null
                || !tracking.test(player, BingoTask.DETONATE_TNT_MINECART)) {
            return
        }
        setOwnedBlock(
                key,
                player.getUniqueId(),
                fireOwners,
                firesByPlayer,
                MAX_OWNED_BLOCKS_PER_PLAYER)
        val playerId = player.getUniqueId()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val current = blockAt(key)
            if ((current == null || !Tag.FIRE.isTagged(current.getType()))
                    && playerId.equals(fireOwners.get(key))) {
                removeOwnedBlock(key, fireOwners, firesByPlayer)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityInsideBlock(event: EntityInsideBlockEvent) {
        val block = event.getBlock()
        val key = BlockKey.from(block)
        val tick = Bukkit.getCurrentTick()
        val minecart = event.getEntity() as? ExplosiveMinecart
        if (minecart != null
                && !minecart.isIgnited()
                && Tag.FIRE.isTagged(block.getType())) {
            val owner = fireOwners.get(key)
            val player = if (owner == null) null else Bukkit.getPlayer(owner)
            if (player != null && tracking.test(player, BingoTask.DETONATE_TNT_MINECART)) {
                putBounded(
                        minecartContacts,
                        minecart.getUniqueId(),
                        MinecartContact(owner!!, key, tick))
            }
        }
        if (event.getEntity() is Creeper
                && Tag.PRESSURE_PLATES.isTagged(block.getType())
                && !isPowered(block)) {
            val owner = pressurePlateOwners.get(key)
            if (owner != null) {
                val trigger = TimedPlayer(owner, tick)
                putBounded(pressurePlateTriggers, key, trigger)
                Bukkit.getScheduler().runTask(
                        plugin, Runnable { pressurePlateTriggers.remove(key, trigger) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMinecartDestroyed(event: VehicleDestroyEvent) {
        val minecart = event.getVehicle() as? ExplosiveMinecart
        if (minecart == null
                || !DamageType.IN_FIRE.equals(event.getDamageSource().getDamageType())) {
            return
        }
        val contact = minecartContacts.remove(minecart.getUniqueId())
        if (contact == null
                || contact.tick() != Bukkit.getCurrentTick()
                || !contact.playerId().equals(fireOwners.get(contact.fire()))) {
            return
        }
        val minecartId = minecart.getUniqueId()
        val primed = PrimedMinecart(
                contact.playerId(), Bukkit.getCurrentTick(), 20 * 60)
        putBounded(primedMinecarts, minecartId, primed)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (primedMinecarts.get(minecartId) !== primed) return@Runnable
            val currentMinecart = Bukkit.getEntity(minecartId) as? ExplosiveMinecart
            if (currentMinecart == null || !currentMinecart.isIgnited()) {
                primedMinecarts.remove(minecartId, primed)
                return@Runnable
            }
            val maximumAge = Math.min(Math.max(currentMinecart.getFuseTicks() + 5, 45), 20 * 60)
            val confirmed = PrimedMinecart(
                    primed.playerId(), Bukkit.getCurrentTick(), maximumAge)
            if (!primedMinecarts.replace(minecartId, primed, confirmed)) return@Runnable
            Bukkit.getScheduler().runTaskLater(
                    plugin, Runnable { primedMinecarts.remove(minecartId, confirmed) }, maximumAge.toLong())
        })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMinecartExplosionPrimed(event: ExplosionPrimeEvent) {
        if (!(event.getEntity() is ExplosiveMinecart)) return
        val minecartId = event.getEntity().getUniqueId()
        val primed = primedMinecarts.get(minecartId)
        if (primed == null) return
        if (event.isCancelled()) primedMinecarts.remove(minecartId, primed)
        Bukkit.getScheduler().runTask(
                plugin, Runnable { primedMinecarts.remove(minecartId, primed) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemMerged(event: ItemMergeEvent) {
        val provenance = turtleScutes.get(event.getEntity().getUniqueId())
        val target = turtleScutes.get(event.getTarget().getUniqueId())
        if (provenance != null && (target == null || target.isConsumed())) {
            putBounded(turtleScutes, event.getTarget().getUniqueId(), provenance)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDroppedItem(event: EntityDropItemEvent) {
        val item = event.getItemDrop()
        val tick = Bukkit.getCurrentTick()
        val turtle = event.getEntity() as? Turtle
        if (!event.isCancelled()
                && turtle != null
                && turtle.isAdult()
                && turtle.getAge() == 0
                && item.getItemStack().getType() == Material.TURTLE_SCUTE) {
            putBounded(turtleScutes, item.getUniqueId(), ScuteProvenance(tick))
        }

        val pig = event.getEntity() as? Pig
        if (pig != null
                && item.getItemStack().getType() == Material.SADDLE) {
            val attempt = pigShearAttempts.get(pig.getUniqueId())
            if (attempt != null && isFresh(attempt.tick(), tick, 1)) {
                val confirmed = PigShearAttempt(attempt.playerId(), attempt.tick(), true)
                if (!pigShearAttempts.replace(pig.getUniqueId(), attempt, confirmed)) return
                val pigId = pig.getUniqueId()
                Bukkit.getScheduler().runTask(plugin, Runnable { confirmPigUnsaddled(pigId, confirmed) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemPickedUp(event: EntityPickupItemEvent) {
        val item = event.getItem()
        val scute = turtleScutes.remove(item.getUniqueId())
        val player = event.getEntity() as? Player
        if (scute != null && scute.consume() && player != null) {
            if (tracking.test(player, BingoTask.COLLECT_TURTLE_SCUTE)) {
                completion.accept(player, BingoTask.COLLECT_TURTLE_SCUTE)
            }
        }

        val piglin = event.getEntity() as? Piglin
        if (piglin != null) {
            val armour = droppedArmour.remove(item.getUniqueId())
            if (armour == null) return
            val pickups = piglinPickupsFor(piglin.getUniqueId())
            pickups.put(
                    armour.slot(),
                    PiglinPickup(
                            armour.playerId(), armour.item(), Bukkit.getCurrentTick(), false, -1))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryPickedUp(event: InventoryPickupItemEvent) {
        val scute = turtleScutes.remove(event.getItem().getUniqueId())
        if (scute != null) scute.consume()
        droppedArmour.remove(event.getItem().getUniqueId())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShelfInteracted(event: PlayerInteractEvent) {
        if (event.getHand() != EquipmentSlot.HAND
                || !event.getAction().isRightClick()
                || event.useInteractedBlock() == Event.Result.DENY) {
            return
        }
        val clickedBlock = event.getClickedBlock() ?: return
        val shelf = clickedBlock.getBlockData() as? Shelf ?: return
        if (!shelf.isPowered() || event.getBlockFace() != shelf.getFacing()) return
        val player = event.getPlayer()
        if (!tracking.test(player, BingoTask.SHELF_HOTBAR_SWAP)) return
        val shelves = connectedShelves(clickedBlock, shelf.getFacing())
        if (shelves.size != 3) return
        val previousShelves = shelfContents(shelves, shelf.getFacing())
        if (previousShelves == null) return
        val previousHotbar = hotbarContents(player)
        val playerId = player.getUniqueId()
        val attempt = ShelfAttempt(
                Bukkit.getCurrentTick(), java.util.List.copyOf(shelves), shelf.getFacing(), previousShelves, previousHotbar)
        shelfAttempts.put(playerId, attempt)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmShelfSwap(playerId, attempt) })
    }

    private fun confirmShelfSwap(playerId: UUID, attempt: ShelfAttempt) {
        if (shelfAttempts.get(playerId) !== attempt) return
        shelfAttempts.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        val shelves = shelfContents(attempt.shelves(), attempt.facing())
        val hotbar = if (player == null) null else hotbarContents(player)
        if (player == null
                || shelves == null
                || hotbar == null
                || !tracking.test(player, BingoTask.SHELF_HOTBAR_SWAP)
                || !Arrays.equals(hotbar, attempt.previousShelves())
                || !Arrays.equals(shelves, attempt.previousHotbar())
                || Arrays.equals(hotbar, attempt.previousHotbar())) {
            return
        }
        completion.accept(player, BingoTask.SHELF_HOTBAR_SWAP)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPigInteracted(event: PlayerInteractEntityEvent) {
        val pig = event.getRightClicked() as? Pig
        if (pig == null
                || !pig.hasSaddle()
                || event.getPlayer().getInventory().getItem(event.getHand()).getType() != Material.SHEARS
                || !tracking.test(event.getPlayer(), BingoTask.REMOVE_PIG_SADDLE)) {
            return
        }
        val pigId = pig.getUniqueId()
        val attempt = PigShearAttempt(
                event.getPlayer().getUniqueId(), Bukkit.getCurrentTick(), false)
        putBounded(pigShearAttempts, pigId, attempt)
        Bukkit.getScheduler().runTaskLater(
                plugin, Runnable { pigShearAttempts.remove(pigId, attempt) }, SHORT_CORRELATION_TICKS + 1L)
    }

    private fun confirmPigUnsaddled(pigId: UUID, attempt: PigShearAttempt) {
        if (!attempt.dropObserved() || !pigShearAttempts.remove(pigId, attempt)) return
        val pig = Bukkit.getEntity(pigId) as? Pig
        val player = Bukkit.getPlayer(attempt.playerId())
        if (pig != null
                && pig.isValid()
                && !pig.isDead()
                && !pig.hasSaddle()
                && player != null
                && tracking.test(player, BingoTask.REMOVE_PIG_SADDLE)) {
            completion.accept(player, BingoTask.REMOVE_PIG_SADDLE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTrade(event: PlayerTradeEvent) {
        val villager = event.getMerchant() as? Villager
        if (villager == null
                || villager.getProfession() != Villager.Profession.CARTOGRAPHER) {
            return
        }
        val trade = event.getTrade()
        val result = trade.getResult()
        val decorations = result.getData(DataComponentTypes.MAP_DECORATIONS)
        if (result.getType() == Material.FILLED_MAP
                && decorations != null
                && !decorations.decorations().isEmpty()
                && trade.getIngredients().stream().anyMatch({ item -> item.getType() == Material.COMPASS })
                && tracking.test(event.getPlayer(), BingoTask.EXPLORER_MAP_TRADE)) {
            completion.accept(event.getPlayer(), BingoTask.EXPLORER_MAP_TRADE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageByEntityEvent) {
        val tick = Bukkit.getCurrentTick()
        val arrow = event.getDamager() as? AbstractArrow
        val player = event.getEntity() as? Player
        if (arrow != null
                && arrow !is Trident
                && player != null) {
            val shot = projectileShots.get(arrow.getUniqueId())
            if (shot != null
                    && shot.playerId().equals(player.getUniqueId())
                    && event.getFinalDamage() >= player.getHealth()
                    && tracking.test(player, BingoTask.SELF_ARROW_TOTEM)) {
                val attempt = TimedPlayer(shot.playerId(), tick)
                putBounded(pendingSelfArrowTotems, player.getUniqueId(), attempt)
                Bukkit.getScheduler().runTask(
                        plugin, Runnable { pendingSelfArrowTotems.remove(player.getUniqueId(), attempt) })
            }
        }

        val bee = event.getDamager() as? Bee
        if (bee != null
                && event.getEntity() is Mob
                && DamageType.STING.equals(event.getDamageSource().getDamageType())
                && bee.isLeashed()) {
            val ownerId = leashedBeeOwners.get(bee.getUniqueId())
            if (ownerId != null) {
                val beeId = bee.getUniqueId()
                val token = attemptToken(ownerId)
                Bukkit.getScheduler().runTask(
                        plugin, Runnable { confirmBeeStung(beeId, ownerId, token) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityResurrected(event: EntityResurrectEvent) {
        val player = event.getEntity() as? Player ?: return
        val attempt = pendingSelfArrowTotems.remove(player.getUniqueId())
        if (attempt != null
                && !event.isCancelled()
                && event.getHand() != null
                && attempt.tick() == Bukkit.getCurrentTick()
                && tracking.test(player, BingoTask.SELF_ARROW_TOTEM)) {
            completion.accept(player, BingoTask.SELF_ARROW_TOTEM)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArmourDropped(event: PlayerDropItemEvent) {
        val slot = goldenArmourSlot(event.getItemDrop().getItemStack().getType())
        if (slot == null || !tracking.test(event.getPlayer(), BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        putBounded(
                droppedArmour,
                event.getItemDrop().getUniqueId(),
                DroppedArmour(
                        event.getPlayer().getUniqueId(),
                        slot,
                        singleItem(event.getItemDrop().getItemStack()),
                        tick))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEquipmentChanged(event: EntityEquipmentChangedEvent) {
        val piglin = event.getEntity() as? Piglin ?: return
        val piglinId = piglin.getUniqueId()
        val pickups = piglinPickups.get(piglinId)
        val offhand = event.getEquipmentChanges().get(EquipmentSlot.OFF_HAND)
        if (pickups != null && offhand != null) {
            val tick = Bukkit.getCurrentTick()
            for (entry in pickups.entries) {
                val pickup = entry.value
                val observed = pickup.observedInOffhand()
                        || offhand.newItem().isSimilar(pickup.item())
                var releasedTick = pickup.releasedTick()
                if (observed
                        && transitionedAwayFromTrackedItem(
                                pickup.item(),
                                offhand.oldItem(),
                                offhand.newItem(),
                                ItemStack::isSimilar)) {
                    releasedTick = tick
                }
                if (observed != pickup.observedInOffhand() || releasedTick != pickup.releasedTick()) {
                    entry.setValue(PiglinPickup(
                            pickup.playerId(), pickup.item(), pickup.tick(), observed, releasedTick))
                }
            }
        }
        for (change in event.getEquipmentChanges().entries) {
            val slot = change.key
            if (!GOLD_ARMOUR_SLOTS.contains(slot)) continue
            tryRecordPiglinArmour(piglin, slot, change.value.newItem())
        }
    }

    private fun tryRecordPiglinArmour(piglin: Piglin, slot: EquipmentSlot, equipped: ItemStack) {
        val pickups = piglinPickups.get(piglin.getUniqueId())
        val pickup = pickups?.get(slot)
        val owners = piglinArmourOwnersFor(piglin.getUniqueId())
        val tick = Bukkit.getCurrentTick()
        if (pickup != null
                && canAttributePiglinArmour(
                        isFresh(pickup.tick(), tick, PIGLIN_RETENTION_TICKS),
                        pickup.observedInOffhand(),
                        pickup.releasedTick(),
                        tick,
                        goldenArmourSlot(equipped.getType()) == slot,
                        equipped.isSimilar(pickup.item()))) {
            owners.put(slot, pickup.playerId())
            pickups!!.remove(slot)
            if (pickups.isEmpty()) piglinPickups.remove(piglin.getUniqueId())
            val player = Bukkit.getPlayer(pickup.playerId())
            if (player != null
                    && hasFullGoldenArmourFrom(piglin, owners, pickup.playerId())
                    && tracking.test(player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)) {
                piglinPickups.remove(piglin.getUniqueId())
                piglinArmourOwners.remove(piglin.getUniqueId())
                completion.accept(player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)
            }
            return
        }
        if (pickup != null
                && !isFresh(pickup.tick(), tick, PIGLIN_RETENTION_TICKS)) {
            pickups!!.remove(slot)
            if (pickups.isEmpty()) piglinPickups.remove(piglin.getUniqueId())
        }
        owners.remove(slot)
        if (owners.isEmpty()) piglinArmourOwners.remove(piglin.getUniqueId())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityLeashed(event: PlayerLeashEntityEvent) {
        if (event.getEntity() is Bee
                && tracking.test(event.getPlayer(), BingoTask.LEASHED_BEE_STING)) {
            putBounded(
                    leashedBeeOwners,
                    event.getEntity().getUniqueId(),
                    event.getPlayer().getUniqueId())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityUnleashed(event: PlayerUnleashEntityEvent) {
        leashedBeeOwners.remove(event.getEntity().getUniqueId())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityUnleashed(event: EntityUnleashEvent) {
        leashedBeeOwners.remove(event.getEntity().getUniqueId())
    }

    private fun confirmBeeStung(beeId: UUID, ownerId: UUID, token: AttemptToken) {
        val bee = Bukkit.getEntity(beeId) as? Bee
        val owner = Bukkit.getPlayer(ownerId)
        if (bee != null
                && bee.hasStung()
                && owner != null
                && isCurrent(ownerId, token)
                && tracking.test(owner, BingoTask.LEASHED_BEE_STING)) {
            completion.accept(owner, BingoTask.LEASHED_BEE_STING)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStatueMined(event: BlockDropItemEvent) {
        if (Tag.COPPER_GOLEM_STATUES.isTagged(event.getBlockState().getType())
                && event.getItems().stream()
                        .map(Item::getItemStack)
                        .map(ItemStack::getType)
                        .anyMatch(Tag.ITEMS_COPPER_GOLEM_STATUES::isTagged)
                && tracking.test(event.getPlayer(), BingoTask.MINE_COPPER_GOLEM_STATUE)) {
            completion.accept(event.getPlayer(), BingoTask.MINE_COPPER_GOLEM_STATUE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val id = event.getEntity().getUniqueId()
        if (event.getCause() == EntityRemoveEvent.Cause.EXPLODE
                && event.getEntity() is ExplosiveMinecart) {
            val primed = primedMinecarts.remove(id)
            val owner = if (primed == null) null else Bukkit.getPlayer(primed.playerId())
            if (owner != null
                    && isFresh(primed!!.tick(), Bukkit.getCurrentTick(), primed.maximumAge())
                    && tracking.test(owner, BingoTask.DETONATE_TNT_MINECART)) {
                completion.accept(owner, BingoTask.DETONATE_TNT_MINECART)
            }
        } else {
            primedMinecarts.remove(id)
        }
        projectileShots.remove(id)
        berryDamage.remove(id)
        minecartContacts.remove(id)
        turtleScutes.remove(id)
        pigShearAttempts.remove(id)
        droppedArmour.remove(id)
        piglinPickups.remove(id)
        piglinArmourOwners.remove(id)
        leashedBeeOwners.remove(id)
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, { a, b -> a + b })
        projectileShots.values.removeIf({ value -> value.playerId().equals(playerId) })
        bellAttempts.keys.removeIf({ value -> value.playerId().equals(playerId) })
        removeOwnedBlocks(playerId, berryBushOwners, berryBushesByPlayer)
        berryDamage.values.removeIf({ value -> value.playerId().equals(playerId) })
        removeOwnedBlocks(playerId, fireOwners, firesByPlayer)
        minecartContacts.values.removeIf({ value -> value.playerId().equals(playerId) })
        primedMinecarts.values.removeIf({ value -> value.playerId().equals(playerId) })
        shelfAttempts.remove(playerId)
        pigShearAttempts.values.removeIf({ value -> value.playerId().equals(playerId) })
        pendingSelfArrowTotems.remove(playerId)
        droppedArmour.values.removeIf({ value -> value.playerId().equals(playerId) })
        piglinPickups.values.forEach({ map -> map.values.removeIf({ value -> value.playerId().equals(playerId) }) })
        piglinPickups.values.removeIf({ it.isEmpty() })
        piglinArmourOwners.values.forEach({ map -> map.values.removeIf(playerId::equals) })
        piglinArmourOwners.values.removeIf({ it.isEmpty() })
        leashedBeeOwners.values.removeIf(playerId::equals)
        removeOwnedBlocks(playerId, pressurePlateOwners, pressurePlatesByPlayer)
        pressurePlateTriggers.values.removeIf({ value -> value.playerId().equals(playerId) })
    }

    override fun clear() {
        detectorGeneration++
        playerGenerations.clear()
        lastPruneTick = Int.MIN_VALUE
        projectileShots.clear()
        bellAttempts.clear()
        berryBushOwners.clear()
        berryBushesByPlayer.clear()
        berryDamage.clear()
        fireOwners.clear()
        firesByPlayer.clear()
        minecartContacts.clear()
        primedMinecarts.clear()
        turtleScutes.clear()
        shelfAttempts.clear()
        pigShearAttempts.clear()
        pendingSelfArrowTotems.clear()
        droppedArmour.clear()
        piglinPickups.clear()
        piglinArmourOwners.clear()
        leashedBeeOwners.clear()
        pressurePlateOwners.clear()
        pressurePlatesByPlayer.clear()
        pressurePlateTriggers.clear()
    }

    private fun connectedShelves(origin: Block, facing: BlockFace): List<BlockKey> {
        val left = shelfLeftOf(facing)
        if (left == null || !isMatchingShelf(origin, facing)) return emptyList()
        val connected = ArrayList<Block>(3)
        connected.add(origin)

        var cursor = origin
        for (distance in 0 until 2) {
            val candidate = cursor.getRelative(left)
            if (!isMatchingShelf(candidate, facing)) break
            val shelf = (candidate.getBlockData() as Shelf)
            if (shelf.getSideChain() != SideChaining.ChainPart.CENTER
                    && shelf.getSideChain() != SideChaining.ChainPart.LEFT) {
                break
            }
            connected.add(0, candidate)
            cursor = candidate
            if (shelf.getSideChain() == SideChaining.ChainPart.LEFT) break
        }

        cursor = origin
        for (distance in 0 until 2) {
            val candidate = cursor.getRelative(left.getOppositeFace())
            if (!isMatchingShelf(candidate, facing)) break
            val shelf = (candidate.getBlockData() as Shelf)
            if (shelf.getSideChain() != SideChaining.ChainPart.CENTER
                    && shelf.getSideChain() != SideChaining.ChainPart.RIGHT) {
                break
            }
            connected.add(candidate)
            cursor = candidate
            if (shelf.getSideChain() == SideChaining.ChainPart.RIGHT) break
        }

        if (connected.size != 3) return emptyList()
        val expected = arrayOf(
            SideChaining.ChainPart.LEFT,
            SideChaining.ChainPart.CENTER,
            SideChaining.ChainPart.RIGHT
        )
        for (index in 0 until connected.size) {
            val shelf = (connected.get(index).getBlockData() as Shelf)
            if (shelf.getSideChain() != expected[index]) return emptyList()
        }
        return connected.stream().map(BlockKey::from).toList()
    }

    private fun shelfContents(keys: List<BlockKey>, facing: BlockFace): Array<ItemStack?>? {
        val items = ArrayList<ItemStack?>(9)
        val expectedParts = arrayOf(
            SideChaining.ChainPart.LEFT,
            SideChaining.ChainPart.CENTER,
            SideChaining.ChainPart.RIGHT
        )
        for (blockIndex in 0 until keys.size) {
            val key = keys.get(blockIndex)
            val block = blockAt(key) ?: return null
            val data = block.getBlockData() as? Shelf ?: return null
            if (!data.isPowered()
                    || data.getFacing() != facing
                    || data.getSideChain() != expectedParts[blockIndex]) {
                return null
            }
            val shelf = block.getState() as? org.bukkit.block.Shelf ?: return null
            val contents = shelf.getInventory().getStorageContents()
            if (contents.size != 3) return null
            for (item in contents) items.add(copyOrNull(item))
        }
        return items.toTypedArray()
    }

    private fun piglinPickupsFor(piglinId: UUID): EnumMap<EquipmentSlot, PiglinPickup> {
        if (!piglinPickups.containsKey(piglinId) && piglinPickups.size >= MAX_TRANSIENT_ENTRIES) {
            val oldest = piglinPickups.keys.iterator().next()
            piglinPickups.remove(oldest)
            piglinArmourOwners.remove(oldest)
        }
        return piglinPickups.computeIfAbsent(
                piglinId, { ignored -> EnumMap(EquipmentSlot::class.java) })
    }

    private fun piglinArmourOwnersFor(piglinId: UUID): EnumMap<EquipmentSlot, UUID> {
        if (!piglinArmourOwners.containsKey(piglinId)
                && piglinArmourOwners.size >= MAX_TRANSIENT_ENTRIES) {
            val oldest = piglinArmourOwners.keys.iterator().next()
            piglinArmourOwners.remove(oldest)
            piglinPickups.remove(oldest)
        }
        return piglinArmourOwners.computeIfAbsent(
                piglinId, { ignored -> EnumMap(EquipmentSlot::class.java) })
    }

    private fun removeTrackedBlock(key: BlockKey) {
        removeOwnedBlock(key, berryBushOwners, berryBushesByPlayer)
        removeOwnedBlock(key, fireOwners, firesByPlayer)
        removeOwnedBlock(key, pressurePlateOwners, pressurePlatesByPlayer)
        pressurePlateTriggers.remove(key)
    }

    private fun removePistonBlocks(blocks: List<Block>, direction: BlockFace) {
        for (block in blocks) {
            removeTrackedBlock(BlockKey.from(block))
            removeTrackedBlock(BlockKey.from(block.getRelative(direction)))
        }
    }

    private fun pruneTransientState(tick: Int) {
        projectileShots.entries.removeIf({ entry ->
                tick - entry.value.tick() > PROJECTILE_RETENTION_TICKS
                        || Bukkit.getEntity(entry.key) == null })
        bellAttempts.entries.removeIf({ entry -> !isFresh(entry.value, tick, SHORT_CORRELATION_TICKS) })
        berryDamage.values.removeIf({ value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS) })
        minecartContacts.values.removeIf({ value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS) })
        turtleScutes.entries.removeIf({ entry ->
                tick - entry.value.tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.key) == null })
        pigShearAttempts.values.removeIf({ value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS) })
        pendingSelfArrowTotems.values.removeIf({ value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS) })
        droppedArmour.entries.removeIf({ entry ->
                tick - entry.value.tick() > ITEM_RETENTION_TICKS
                        || Bukkit.getEntity(entry.key) == null })
        piglinPickups.values.forEach({ map ->
                map.values.removeIf({ value -> tick - value.tick() > PIGLIN_RETENTION_TICKS }) })
        piglinPickups.values.removeIf({ it.isEmpty() })
        pressurePlateTriggers.values.removeIf({ value -> !isFresh(value.tick(), tick, SHORT_CORRELATION_TICKS) })
    }

    private fun pruneTransientStateIfDue(tick: Int) {
        if (lastPruneTick != Int.MIN_VALUE) {
            val age = tick - lastPruneTick
            if (age >= 0 && age < 20) return
        }
        lastPruneTick = tick
        pruneTransientState(tick)
    }

    private fun blockAt(key: BlockKey): Block? {
        val world = Bukkit.getWorld(key.worldId())
        return world?.getBlockAt(key.x(), key.y(), key.z())
    }

    private fun attemptToken(playerId: UUID): AttemptToken {
        return AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))
    }

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean {
        return token.detectorGeneration() == detectorGeneration
                && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)
    }
    private data class BlockKey(private val worldId: UUID, private val x: Int, private val y: Int, private val z: Int) {
        fun worldId(): UUID = worldId
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z
        companion object {

        @JvmStatic fun from(block: Block): BlockKey {
            return BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ())
        }

        }
    }

    private data class ProjectileShot(private val playerId: UUID, private val origin: Location, private val tick: Int) {
        fun playerId(): UUID = playerId
        fun origin(): Location = origin
        fun tick(): Int = tick
    }

    private data class BellAttemptKey(private val bell: BlockKey, private val playerId: UUID) {
        fun bell(): BlockKey = bell
        fun playerId(): UUID = playerId
    }

    private data class DoorCandidate(private val block: BlockKey, private val material: Material) {
        fun block(): BlockKey = block
        fun material(): Material = material
    }

    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }

    private data class TimedPlayer(private val playerId: UUID, private val tick: Int) {
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
    }

    private data class MinecartContact(private val playerId: UUID, private val fire: BlockKey, private val tick: Int) {
        fun playerId(): UUID = playerId
        fun fire(): BlockKey = fire
        fun tick(): Int = tick
    }

    private data class PrimedMinecart(private val playerId: UUID, private val tick: Int, private val maximumAge: Int) {
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
        fun maximumAge(): Int = maximumAge
    }

    private data class PigShearAttempt(private val playerId: UUID, private val tick: Int, private val dropObserved: Boolean) {
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
        fun dropObserved(): Boolean = dropObserved
    }

    private class ScuteProvenance(private val tick: Int) {
        private var consumed = false
        fun tick(): Int = tick
        fun consume(): Boolean {
            if (consumed) return false
            consumed = true
            return true
        }
        fun isConsumed(): Boolean = consumed
    }

    private data class ShelfAttempt(private val tick: Int, private val shelves: List<BlockKey>, private val facing: BlockFace, private val previousShelves: Array<ItemStack?>, private val previousHotbar: Array<ItemStack?>) {
        fun tick(): Int = tick
        fun shelves(): List<BlockKey> = shelves
        fun facing(): BlockFace = facing
        fun previousShelves(): Array<ItemStack?> = previousShelves
        fun previousHotbar(): Array<ItemStack?> = previousHotbar
    }

    private data class DroppedArmour(private val playerId: UUID, private val slot: EquipmentSlot, private val item: ItemStack, private val tick: Int) {
        fun playerId(): UUID = playerId
        fun slot(): EquipmentSlot = slot
        fun item(): ItemStack = item
        fun tick(): Int = tick
    }

    private data class PiglinPickup(private val playerId: UUID, private val item: ItemStack, private val tick: Int, private val observedInOffhand: Boolean, private val releasedTick: Int) {
        fun playerId(): UUID = playerId
        fun item(): ItemStack = item
        fun tick(): Int = tick
        fun observedInOffhand(): Boolean = observedInOffhand
        fun releasedTick(): Int = releasedTick
    }

    companion object {

        private const val MAX_OWNED_BLOCKS_PER_PLAYER = 2_048
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val SHORT_CORRELATION_TICKS = 3
        private const val PROJECTILE_RETENTION_TICKS = 20 * 60 * 5
        private const val ITEM_RETENTION_TICKS = 20 * 60 * 10
        private const val PIGLIN_RETENTION_TICKS = 20 * 30
        private const val TEN_BLOCKS_SQUARED = 100.0
        private const val PARROT_DANCE_DISTANCE_SQUARED = 3.46 * 3.46
        private val FACES = arrayOf(
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
        )
        private val HORIZONTAL_FACES = arrayOf(
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
        )
        private val GOLD_ARMOUR_SLOTS = EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

        @JvmStatic
        private fun hotbarContents(player: Player): Array<ItemStack?> {
            val items = arrayOfNulls<ItemStack>(9)
            for (slot in 0 until items.size) {
                items[slot] = copyOrNull(player.getInventory().getItem(slot))
            }
            return items
        }

        @JvmStatic
        private fun isEmpty(item: ItemStack?): Boolean {
            return item == null || item.getType().isAir() || item.getAmount() <= 0
        }

        @JvmStatic
        fun <T> transitionedAwayFromTrackedItem(tracked: T, previous: T, current: T, similarity: BiPredicate<T, T>): Boolean {
            return similarity.test(previous, tracked) && !similarity.test(current, tracked)
        }

        @JvmStatic
        fun canAttributePiglinArmour(fresh: Boolean, observedInOffhand: Boolean, releasedTick: Int, currentTick: Int, equippedSlotMatches: Boolean, equippedItemMatches: Boolean): Boolean {
            return fresh
                    && observedInOffhand
                    && releasedTick >= 0
                    && releasedTick == currentTick
                    && equippedSlotMatches
                    && equippedItemMatches
        }

        @JvmStatic
        fun shouldRetainFireOwnerAfterPlaceEvent(placedBlockIsFire: Boolean, placingPlayerId: UUID, recordedFireOwner: UUID?): Boolean {
            return placedBlockIsFire && placingPlayerId.equals(recordedFireOwner)
        }

        @JvmStatic
        private fun copyOrNull(item: ItemStack?): ItemStack? {
            return if (isEmpty(item)) null else item!!.clone()
        }

        @JvmStatic
        private fun shelfLeftOf(facing: BlockFace): BlockFace? = when (facing) {
            BlockFace.NORTH -> BlockFace.EAST
            BlockFace.SOUTH -> BlockFace.WEST
            BlockFace.EAST -> BlockFace.SOUTH
            BlockFace.WEST -> BlockFace.NORTH
            else -> null
        }

        @JvmStatic
        private fun isMatchingShelf(block: Block, facing: BlockFace): Boolean {
            val shelf = block.getBlockData() as? Shelf ?: return false
            return shelf.isPowered() && shelf.getFacing() == facing &&
                shelf.getSideChain() != SideChaining.ChainPart.UNCONNECTED
        }

        @JvmStatic
        private fun isPowered(block: Block): Boolean {
            val data = block.getBlockData()
            if (data is Powerable) return data.isPowered()
            return data is AnaloguePowerable && data.getPower() > 0
        }

        @JvmStatic
        private fun hasFullGoldenArmourFrom(piglin: Piglin, owners: Map<EquipmentSlot, UUID>, playerId: UUID): Boolean {
            if (!allArmourSlotsOwnedBy(owners, playerId)) return false
            for (slot in GOLD_ARMOUR_SLOTS) {
                if (goldenArmourSlot(piglin.getEquipment().getItem(slot).getType()) != slot) {
                    return false
                }
            }
            return true
        }

        @JvmStatic
        fun allArmourSlotsOwnedBy(owners: Map<EquipmentSlot, UUID>, playerId: UUID): Boolean {
            for (slot in GOLD_ARMOUR_SLOTS) {
                if (!playerId.equals(owners.get(slot))) return false
            }
            return true
        }

        @JvmStatic
        private fun goldenArmourSlot(material: Material): EquipmentSlot? = when (material) {
            Material.GOLDEN_HELMET -> EquipmentSlot.HEAD
            Material.GOLDEN_CHESTPLATE -> EquipmentSlot.CHEST
            Material.GOLDEN_LEGGINGS -> EquipmentSlot.LEGS
            Material.GOLDEN_BOOTS -> EquipmentSlot.FEET
            else -> null
        }

        @JvmStatic
        private fun canonicalDoor(block: Block): Block? {
            if (!Tag.DOORS.isTagged(block.getType())) return null
            val door = block.getBlockData() as? Door ?: return null
            return if (door.getHalf() == Bisected.Half.TOP) block.getRelative(BlockFace.DOWN) else block
        }

        @JvmStatic
        private fun setOwnedBlock(key: BlockKey, playerId: UUID, owners: MutableMap<BlockKey, UUID>, blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>, maximum: Int) {
            val previous = owners.put(key, playerId)
            if (previous != null && !previous.equals(playerId)) {
                removeFromOwnerIndex(blocksByPlayer, previous, key)
            }
            val blocks = blocksByPlayer.computeIfAbsent(
                    playerId, { ignored -> LinkedHashSet<BlockKey>() })
            blocks.add(key)
            while (blocks.size > maximum) {
                val oldest = blocks.iterator().next()
                blocks.remove(oldest)
                owners.remove(oldest, playerId)
            }
        }

        @JvmStatic
        private fun removeOwnedBlock(key: BlockKey, owners: MutableMap<BlockKey, UUID>, blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>): UUID? {
            val owner = owners.remove(key)
            if (owner != null) removeFromOwnerIndex(blocksByPlayer, owner, key)
            return owner
        }

        @JvmStatic
        private fun removeOwnedBlocks(playerId: UUID, owners: MutableMap<BlockKey, UUID>, blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>) {
            val blocks = blocksByPlayer.remove(playerId)
            if (blocks == null) return
            for (block in blocks) owners.remove(block, playerId)
        }

        @JvmStatic
        private fun removeFromOwnerIndex(blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>, playerId: UUID, key: BlockKey) {
            val blocks = blocksByPlayer.get(playerId)
            if (blocks == null) return
            blocks.remove(key)
            if (blocks.isEmpty()) blocksByPlayer.remove(playerId)
        }

        @JvmStatic
        private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) {
                map.remove(map.keys.iterator().next())
            }
            map.put(key, value)
        }

        @JvmStatic
        private fun sameWorld(first: Location, second: Location): Boolean {
            val world = first.getWorld()
            return world != null && world == second.getWorld()
        }

        @JvmStatic
        private fun isFresh(earlier: Int, current: Int, maximumAge: Int): Boolean {
            val age = current - earlier
            return age >= 0 && age <= maximumAge
        }

        @JvmStatic
        private fun singleItem(stack: ItemStack): ItemStack {
            val copy = stack.clone()
            copy.setAmount(1)
            return copy
        }
    }
}
