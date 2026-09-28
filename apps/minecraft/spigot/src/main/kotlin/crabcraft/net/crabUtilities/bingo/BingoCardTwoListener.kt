package crabcraft.net.crabUtilities.bingo

import crabcraft.net.crabUtilities.bingo.BingoTracking.BlockKey
import crabcraft.net.crabUtilities.bingo.BingoTracking.MAX_TRANSIENT_ENTRIES
import crabcraft.net.crabUtilities.bingo.BingoTracking.blockAt
import crabcraft.net.crabUtilities.bingo.BingoTracking.isFresh
import crabcraft.net.crabUtilities.bingo.BingoTracking.putBounded
import crabcraft.net.crabUtilities.bingo.BingoTracking.removeOwnedBlock
import crabcraft.net.crabUtilities.bingo.BingoTracking.removeOwnedBlocks
import crabcraft.net.crabUtilities.bingo.BingoTracking.sameWorld
import crabcraft.net.crabUtilities.bingo.BingoTracking.setOwnedBlock
import crabcraft.net.crabUtilities.bingo.BingoTracking.singleItem
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.block.BlockBreakBlockEvent
import io.papermc.paper.event.block.TargetHitEvent
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import io.papermc.paper.event.entity.EntityInsideBlockEvent
import io.papermc.paper.event.player.PlayerTradeEvent
import java.util.ArrayList
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
import org.bukkit.entity.Mob
import org.bukkit.entity.Parrot
import org.bukkit.entity.Pig
import org.bukkit.entity.Piglin
import org.bukkit.entity.Player
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
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for Bingo #2. */
class BingoCardTwoListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
) : AbstractBingoDetector() {
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
    private var lastPruneTick = Int.MIN_VALUE

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntitySheared(event: PlayerShearEntityEvent) {
        if (event.entity is Bogged && tracking.test(event.player, BingoTask.SHEAR_BOGGED))
            completion.accept(event.player, BingoTask.SHEAR_BOGGED)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileLaunched(event: org.bukkit.event.entity.ProjectileLaunchEvent) {
        val projectile = event.entity
        val player = projectile.shooter as? Player ?: return
        if (
            !tracking.test(player, BingoTask.RING_BELL_PROJECTILE) &&
                !tracking.test(player, BingoTask.TARGET_OPENS_DOOR) &&
                !tracking.test(player, BingoTask.SELF_ARROW_TOTEM)
        )
            return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        putBounded(
            projectileShots,
            projectile.uniqueId,
            ProjectileShot(player.uniqueId, projectile.location.clone(), tick),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onProjectileHit(event: org.bukkit.event.entity.ProjectileHitEvent) {
        val block = event.hitBlock ?: return
        if (block.type != Material.BELL) return
        val shot = projectileShots.remove(event.entity.uniqueId) ?: return
        if (
            !sameWorld(shot.origin, block.location) ||
                shot.origin.distanceSquared(block.location.toCenterLocation()) < TEN_BLOCKS_SQUARED
        )
            return
        val key = BellAttemptKey(BlockKey.from(block), shot.playerId)
        val tick = Bukkit.getCurrentTick()
        putBounded(bellAttempts, key, tick)
        Bukkit.getScheduler().runTask(plugin, Runnable { bellAttempts.remove(key, tick) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBellRung(event: BellRingEvent) {
        val tick = Bukkit.getCurrentTick()
        val entity = event.entity
        if (entity is Player) {
            val key = BellAttemptKey(BlockKey.from(event.block), entity.uniqueId)
            val attemptTick = bellAttempts.remove(key)
            if (
                attemptTick != null &&
                    isFresh(attemptTick, tick, 1) &&
                    tracking.test(entity, BingoTask.RING_BELL_PROJECTILE)
            )
                completion.accept(entity, BingoTask.RING_BELL_PROJECTILE)
            return
        }
        if (entity != null) return
        for (face in HORIZONTAL_FACES) {
            val plate = event.block.getRelative(face)
            val plateKey = BlockKey.from(plate)
            val trigger = pressurePlateTriggers.remove(plateKey) ?: continue
            if (trigger.tick != tick || !Tag.PRESSURE_PLATES.isTagged(plate.type) || !isPowered(plate)) continue
            val player = Bukkit.getPlayer(trigger.playerId)
            if (player != null && tracking.test(player, BingoTask.CREEPER_RINGS_BELL)) {
                completion.accept(player, BingoTask.CREEPER_RINGS_BELL)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTargetHit(event: TargetHitEvent) {
        val projectile = event.entity
        val shot = projectileShots.remove(projectile.uniqueId) ?: return
        val target = event.hitBlock ?: return
        if (
            projectile !is AbstractArrow ||
                projectile is Trident ||
                event.signalStrength <= 0 ||
                !sameWorld(shot.origin, target.location) ||
                shot.origin.distanceSquared(target.location.toCenterLocation()) < TEN_BLOCKS_SQUARED
        )
            return
        val player = Bukkit.getPlayer(shot.playerId) ?: return
        if (!tracking.test(player, BingoTask.TARGET_OPENS_DOOR)) return
        val targetKey = BlockKey.from(target)
        val doors = LinkedHashSet<DoorCandidate>()
        for (face in FACES) {
            val adjacent = canonicalDoor(target.getRelative(face)) ?: continue
            val door = adjacent.blockData
            if (door is Door && !door.isOpen && !door.isPowered)
                doors.add(DoorCandidate(BlockKey.from(adjacent), adjacent.type))
        }
        if (doors.isEmpty()) return
        val token = attemptToken(shot.playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmTargetDoor(shot.playerId, token, targetKey, doors) })
    }

    private fun confirmTargetDoor(playerId: UUID, token: AttemptToken, targetKey: BlockKey, doors: Set<DoorCandidate>) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val target = blockAt(targetKey)
        if (
            !isCurrent(playerId, token) ||
                target == null ||
                target.type != Material.TARGET ||
                !isPowered(target) ||
                !tracking.test(player, BingoTask.TARGET_OPENS_DOOR)
        )
            return
        for (candidate in doors) {
            val block = blockAt(candidate.block) ?: continue
            val door = block.blockData
            if (block.type == candidate.material && door is Door && door.isOpen && door.isPowered) {
                completion.accept(player, BingoTask.TARGET_OPENS_DOOR)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        if (!event.canBuild()) return
        val block = event.blockPlaced
        val key = BlockKey.from(block)
        val player = event.player
        removeOwnedBlock(key, berryBushOwners, berryBushesByPlayer)
        // Paper follows ignition with a captured placement event; retain the same player's fire.
        if (!shouldRetainFireOwnerAfterPlaceEvent(Tag.FIRE.isTagged(block.type), player.uniqueId, fireOwners[key]))
            removeOwnedBlock(key, fireOwners, firesByPlayer)
        removeOwnedBlock(key, pressurePlateOwners, pressurePlatesByPlayer)
        pressurePlateTriggers.remove(key)
        if (block.type == Material.SWEET_BERRY_BUSH && tracking.test(player, BingoTask.BERRY_BUSH_KILL))
            setOwnedBlock(key, player.uniqueId, berryBushOwners, berryBushesByPlayer, MAX_OWNED_BLOCKS_PER_PLAYER)
        if (Tag.PRESSURE_PLATES.isTagged(block.type) && tracking.test(player, BingoTask.CREEPER_RINGS_BELL))
            setOwnedBlock(
                key,
                player.uniqueId,
                pressurePlateOwners,
                pressurePlatesByPlayer,
                MAX_OWNED_BLOCKS_PER_PLAYER,
            )
        if (
            block.type == Material.WET_SPONGE &&
                event.itemInHand.type == Material.WET_SPONGE &&
                block.world.environment == World.Environment.NETHER &&
                tracking.test(player, BingoTask.DRY_SPONGE_NETHER)
        ) {
            val playerId = player.uniqueId
            val token = attemptToken(playerId)
            Bukkit.getScheduler()
                .runTask(
                    plugin,
                    Runnable {
                        val current = blockAt(key)
                        val currentPlayer = Bukkit.getPlayer(playerId)
                        if (
                            current != null &&
                                current.type == Material.SPONGE &&
                                currentPlayer != null &&
                                isCurrent(playerId, token) &&
                                tracking.test(currentPlayer, BingoTask.DRY_SPONGE_NETHER)
                        )
                            completion.accept(currentPlayer, BingoTask.DRY_SPONGE_NETHER)
                    },
                )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBroken(event: BlockBreakEvent) {
        removeTrackedBlock(BlockKey.from(event.block))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockBrokenByBlock(event: BlockBreakBlockEvent) {
        removeTrackedBlock(BlockKey.from(event.block))
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
    fun onBlockFaded(event: BlockFadeEvent) {
        removeOwnedBlock(BlockKey.from(event.block), fireOwners, firesByPlayer)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBerryBushDamage(event: EntityDamageByBlockEvent) {
        val damager = event.damager
        if (
            event.entity !is Enemy ||
                damager == null ||
                DamageType.SWEET_BERRY_BUSH != event.damageSource.damageType ||
                event.finalDamage <= 0.0
        )
            return
        berryDamage.remove(event.entity.uniqueId)
        val owner = berryBushOwners[BlockKey.from(damager)]
        val ownerPlayer = owner?.let(Bukkit::getPlayer)
        if (ownerPlayer != null && tracking.test(ownerPlayer, BingoTask.BERRY_BUSH_KILL))
            putBounded(berryDamage, event.entity.uniqueId, TimedPlayer(owner!!, Bukkit.getCurrentTick()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (event.entity !is Enemy) return
        val attempt = berryDamage.remove(event.entity.uniqueId) ?: return
        if (
            !isFresh(attempt.tick, Bukkit.getCurrentTick(), SHORT_CORRELATION_TICKS) ||
                DamageType.SWEET_BERRY_BUSH != event.damageSource.damageType
        )
            return
        val owner = Bukkit.getPlayer(attempt.playerId)
        if (owner != null && tracking.test(owner, BingoTask.BERRY_BUSH_KILL))
            completion.accept(owner, BingoTask.BERRY_BUSH_KILL)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJukeboxInteracted(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !event.action.isRightClick) return
        val clicked = event.clickedBlock ?: return
        val jukebox = clicked.state as? Jukebox ?: return
        if (jukebox.hasRecord()) return
        val item = event.item ?: return
        val player = event.player
        if (!item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE) || !tracking.test(player, BingoTask.FIVE_PARROTS_DANCE))
            return
        val key = BlockKey.from(clicked)
        val playerId = player.uniqueId
        val token = attemptToken(playerId)
        val inserted = singleItem(item)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmParrotsDancing(playerId, token, key, inserted) })
    }

    private fun confirmParrotsDancing(playerId: UUID, token: AttemptToken, key: BlockKey, inserted: ItemStack) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val block = blockAt(key) ?: return
        if (!isCurrent(playerId, token) || !tracking.test(player, BingoTask.FIVE_PARROTS_DANCE)) return
        val jukebox = block.state as? Jukebox ?: return
        if (!jukebox.isPlaying || !jukebox.record.isSimilar(inserted)) return
        val centre = block.location.toCenterLocation()
        val variants = EnumSet.noneOf(Parrot.Variant::class.java)
        block.world
            .getNearbyEntities(org.bukkit.util.BoundingBox.of(block).expand(3.0))
            .asSequence()
            .filterIsInstance<Parrot>()
            .filter { it.location.distanceSquared(centre) < PARROT_DANCE_DISTANCE_SQUARED }
            .forEach { variants.add(it.variant) }
        if (variants.size == Parrot.Variant.values().size) completion.accept(player, BingoTask.FIVE_PARROTS_DANCE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockIgnited(event: BlockIgniteEvent) {
        val key = BlockKey.from(event.block)
        removeOwnedBlock(key, fireOwners, firesByPlayer)
        if (
            event.cause != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL &&
                event.cause != BlockIgniteEvent.IgniteCause.FIREBALL
        )
            return
        val player = event.ignitingEntity as? Player ?: return
        if (!tracking.test(player, BingoTask.DETONATE_TNT_MINECART)) return
        setOwnedBlock(key, player.uniqueId, fireOwners, firesByPlayer, MAX_OWNED_BLOCKS_PER_PLAYER)
        val playerId = player.uniqueId
        Bukkit.getScheduler()
            .runTask(
                plugin,
                Runnable {
                    val current = blockAt(key)
                    if ((current == null || !Tag.FIRE.isTagged(current.type)) && playerId == fireOwners[key])
                        removeOwnedBlock(key, fireOwners, firesByPlayer)
                },
            )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityInsideBlock(event: EntityInsideBlockEvent) {
        val block = event.block
        val key = BlockKey.from(block)
        val tick = Bukkit.getCurrentTick()
        val entity = event.entity
        if (entity is ExplosiveMinecart && !entity.isIgnited && Tag.FIRE.isTagged(block.type)) {
            val owner = fireOwners[key]
            val player = owner?.let(Bukkit::getPlayer)
            if (player != null && tracking.test(player, BingoTask.DETONATE_TNT_MINECART))
                putBounded(minecartContacts, entity.uniqueId, MinecartContact(owner!!, key, tick))
        }
        if (entity is Creeper && Tag.PRESSURE_PLATES.isTagged(block.type) && !isPowered(block)) {
            val owner = pressurePlateOwners[key]
            if (owner != null) {
                val trigger = TimedPlayer(owner, tick)
                putBounded(pressurePlateTriggers, key, trigger)
                Bukkit.getScheduler().runTask(plugin, Runnable { pressurePlateTriggers.remove(key, trigger) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMinecartDestroyed(event: VehicleDestroyEvent) {
        val minecart = event.vehicle as? ExplosiveMinecart ?: return
        if (DamageType.IN_FIRE != event.damageSource.damageType) return
        val contact = minecartContacts.remove(minecart.uniqueId) ?: return
        if (contact.tick != Bukkit.getCurrentTick() || contact.playerId != fireOwners[contact.fire]) return
        val minecartId = minecart.uniqueId
        val primed = PrimedMinecart(contact.playerId, Bukkit.getCurrentTick(), 20 * 60)
        putBounded(primedMinecarts, minecartId, primed)
        Bukkit.getScheduler()
            .runTask(
                plugin,
                Runnable {
                    if (primedMinecarts[minecartId] !== primed) return@Runnable
                    val current = Bukkit.getEntity(minecartId)
                    if (current !is ExplosiveMinecart || !current.isIgnited) {
                        primedMinecarts.remove(minecartId, primed)
                        return@Runnable
                    }
                    val maximumAge = (current.fuseTicks + 5).coerceIn(45, 20 * 60)
                    val confirmed = PrimedMinecart(primed.playerId, Bukkit.getCurrentTick(), maximumAge)
                    if (!primedMinecarts.replace(minecartId, primed, confirmed)) return@Runnable
                    Bukkit.getScheduler()
                        .runTaskLater(
                            plugin,
                            Runnable { primedMinecarts.remove(minecartId, confirmed) },
                            maximumAge.toLong(),
                        )
                },
            )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onMinecartExplosionPrimed(event: ExplosionPrimeEvent) {
        if (event.entity !is ExplosiveMinecart) return
        val minecartId = event.entity.uniqueId
        val primed = primedMinecarts[minecartId] ?: return
        if (event.isCancelled) primedMinecarts.remove(minecartId, primed)
        Bukkit.getScheduler().runTask(plugin, Runnable { primedMinecarts.remove(minecartId, primed) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemMerged(event: ItemMergeEvent) {
        val provenance = turtleScutes[event.entity.uniqueId]
        val target = turtleScutes[event.target.uniqueId]
        if (provenance != null && (target == null || target.isConsumed()))
            putBounded(turtleScutes, event.target.uniqueId, provenance)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDroppedItem(event: EntityDropItemEvent) {
        val item = event.itemDrop
        val tick = Bukkit.getCurrentTick()
        val entity = event.entity
        if (
            !event.isCancelled &&
                entity is Turtle &&
                entity.isAdult &&
                entity.age == 0 &&
                item.itemStack.type == Material.TURTLE_SCUTE
        )
            putBounded(turtleScutes, item.uniqueId, ScuteProvenance(tick))
        if (entity is Pig && item.itemStack.type == Material.SADDLE) {
            val attempt = pigShearAttempts[entity.uniqueId]
            if (attempt != null && isFresh(attempt.tick, tick, 1)) {
                val confirmed = PigShearAttempt(attempt.playerId, attempt.tick, true)
                if (!pigShearAttempts.replace(entity.uniqueId, attempt, confirmed)) return
                val pigId = entity.uniqueId
                Bukkit.getScheduler().runTask(plugin, Runnable { confirmPigUnsaddled(pigId, confirmed) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onItemPickedUp(event: EntityPickupItemEvent) {
        val item = event.item
        val scute = turtleScutes.remove(item.uniqueId)
        val entity = event.entity
        if (
            scute != null &&
                scute.consume() &&
                entity is Player &&
                tracking.test(entity, BingoTask.COLLECT_TURTLE_SCUTE)
        )
            completion.accept(entity, BingoTask.COLLECT_TURTLE_SCUTE)
        if (entity is Piglin) {
            val armour = droppedArmour.remove(item.uniqueId) ?: return
            val pickups = piglinPickupsFor(entity.uniqueId)
            pickups[armour.slot] = PiglinPickup(armour.playerId, armour.item, Bukkit.getCurrentTick(), false, -1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryPickedUp(event: InventoryPickupItemEvent) {
        turtleScutes.remove(event.item.uniqueId)?.consume()
        droppedArmour.remove(event.item.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShelfInteracted(event: PlayerInteractEvent) {
        if (
            event.hand != EquipmentSlot.HAND ||
                !event.action.isRightClick ||
                event.useInteractedBlock() == Event.Result.DENY
        )
            return
        val clicked = event.clickedBlock ?: return
        val shelf = clicked.blockData as? Shelf ?: return
        if (!shelf.isPowered || event.blockFace != shelf.facing) return
        val player = event.player
        if (!tracking.test(player, BingoTask.SHELF_HOTBAR_SWAP)) return
        val shelves = connectedShelves(clicked, shelf.facing)
        if (shelves.size != 3) return
        val previousShelves = shelfContents(shelves, shelf.facing) ?: return
        val previousHotbar = hotbarContents(player)
        val playerId = player.uniqueId
        val attempt =
            ShelfAttempt(
                Bukkit.getCurrentTick(),
                java.util.List.copyOf(shelves),
                shelf.facing,
                previousShelves,
                previousHotbar,
            )
        shelfAttempts[playerId] = attempt
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmShelfSwap(playerId, attempt) })
    }

    private fun confirmShelfSwap(playerId: UUID, attempt: ShelfAttempt) {
        if (shelfAttempts[playerId] !== attempt) return
        shelfAttempts.remove(playerId)
        val player = Bukkit.getPlayer(playerId)
        val shelves = shelfContents(attempt.shelves, attempt.facing)
        val hotbar = player?.let(::hotbarContents)
        if (
            player == null ||
                shelves == null ||
                hotbar == null ||
                !tracking.test(player, BingoTask.SHELF_HOTBAR_SWAP) ||
                !hotbar.contentEquals(attempt.previousShelves) ||
                !shelves.contentEquals(attempt.previousHotbar) ||
                hotbar.contentEquals(attempt.previousHotbar)
        )
            return
        completion.accept(player, BingoTask.SHELF_HOTBAR_SWAP)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPigInteracted(event: PlayerInteractEntityEvent) {
        val pig = event.rightClicked as? Pig ?: return
        if (
            !pig.hasSaddle() ||
                event.player.inventory.getItem(event.hand).type != Material.SHEARS ||
                !tracking.test(event.player, BingoTask.REMOVE_PIG_SADDLE)
        )
            return
        val pigId = pig.uniqueId
        val attempt = PigShearAttempt(event.player.uniqueId, Bukkit.getCurrentTick(), false)
        putBounded(pigShearAttempts, pigId, attempt)
        Bukkit.getScheduler()
            .runTaskLater(plugin, Runnable { pigShearAttempts.remove(pigId, attempt) }, SHORT_CORRELATION_TICKS + 1L)
    }

    private fun confirmPigUnsaddled(pigId: UUID, attempt: PigShearAttempt) {
        if (!attempt.dropObserved || !pigShearAttempts.remove(pigId, attempt)) return
        val entity = Bukkit.getEntity(pigId)
        val player = Bukkit.getPlayer(attempt.playerId)
        if (
            entity is Pig &&
                entity.isValid &&
                !entity.isDead &&
                !entity.hasSaddle() &&
                player != null &&
                tracking.test(player, BingoTask.REMOVE_PIG_SADDLE)
        )
            completion.accept(player, BingoTask.REMOVE_PIG_SADDLE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTrade(event: PlayerTradeEvent) {
        val villager = event.merchant as? Villager ?: return
        if (villager.profession != Villager.Profession.CARTOGRAPHER) return
        val trade = event.trade
        val result = trade.result
        val decorations = result.getData(DataComponentTypes.MAP_DECORATIONS)
        if (
            result.type == Material.FILLED_MAP &&
                decorations != null &&
                decorations.decorations().isNotEmpty() &&
                trade.ingredients.any { it.type == Material.COMPASS } &&
                tracking.test(event.player, BingoTask.EXPLORER_MAP_TRADE)
        )
            completion.accept(event.player, BingoTask.EXPLORER_MAP_TRADE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDamaged(event: EntityDamageByEntityEvent) {
        val tick = Bukkit.getCurrentTick()
        val damager = event.damager
        val entity = event.entity
        if (damager is AbstractArrow && damager !is Trident && entity is Player) {
            val shot = projectileShots[damager.uniqueId]
            if (
                shot != null &&
                    shot.playerId == entity.uniqueId &&
                    event.finalDamage >= entity.health &&
                    tracking.test(entity, BingoTask.SELF_ARROW_TOTEM)
            ) {
                val attempt = TimedPlayer(shot.playerId, tick)
                putBounded(pendingSelfArrowTotems, entity.uniqueId, attempt)
                Bukkit.getScheduler()
                    .runTask(plugin, Runnable { pendingSelfArrowTotems.remove(entity.uniqueId, attempt) })
            }
        }
        if (damager is Bee && entity is Mob && DamageType.STING == event.damageSource.damageType && damager.isLeashed) {
            val ownerId = leashedBeeOwners[damager.uniqueId]
            if (ownerId != null) {
                val beeId = damager.uniqueId
                val token = attemptToken(ownerId)
                Bukkit.getScheduler().runTask(plugin, Runnable { confirmBeeStung(beeId, ownerId, token) })
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityResurrected(event: EntityResurrectEvent) {
        val player = event.entity as? Player ?: return
        val attempt = pendingSelfArrowTotems.remove(player.uniqueId)
        if (
            attempt != null &&
                !event.isCancelled &&
                event.hand != null &&
                attempt.tick == Bukkit.getCurrentTick() &&
                tracking.test(player, BingoTask.SELF_ARROW_TOTEM)
        )
            completion.accept(player, BingoTask.SELF_ARROW_TOTEM)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArmourDropped(event: PlayerDropItemEvent) {
        val slot = goldenArmourSlot(event.itemDrop.itemStack.type) ?: return
        if (!tracking.test(event.player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientStateIfDue(tick)
        putBounded(
            droppedArmour,
            event.itemDrop.uniqueId,
            DroppedArmour(event.player.uniqueId, slot, singleItem(event.itemDrop.itemStack), tick),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEquipmentChanged(event: EntityEquipmentChangedEvent) {
        val piglin = event.entity as? Piglin ?: return
        val piglinId = piglin.uniqueId
        val pickups = piglinPickups[piglinId]
        val offhand = event.equipmentChanges[EquipmentSlot.OFF_HAND]
        if (pickups != null && offhand != null) {
            val tick = Bukkit.getCurrentTick()
            for (entry in pickups.entries) {
                val pickup = entry.value
                val observed = pickup.observedInOffhand || offhand.newItem().isSimilar(pickup.item)
                var releasedTick = pickup.releasedTick
                if (
                    observed &&
                        transitionedAwayFromTrackedItem(
                            pickup.item,
                            offhand.oldItem(),
                            offhand.newItem(),
                            ItemStack::isSimilar,
                        )
                )
                    releasedTick = tick
                if (observed != pickup.observedInOffhand || releasedTick != pickup.releasedTick)
                    entry.setValue(PiglinPickup(pickup.playerId, pickup.item, pickup.tick, observed, releasedTick))
            }
        }
        for ((slot, change) in event.equipmentChanges) {
            if (!GOLD_ARMOUR_SLOTS.contains(slot)) continue
            tryRecordPiglinArmour(piglin, slot, change.newItem())
        }
    }

    private fun tryRecordPiglinArmour(piglin: Piglin, slot: EquipmentSlot, equipped: ItemStack) {
        val pickups = piglinPickups[piglin.uniqueId]
        val pickup = pickups?.get(slot)
        val owners = piglinArmourOwnersFor(piglin.uniqueId)
        val tick = Bukkit.getCurrentTick()
        if (
            pickup != null &&
                canAttributePiglinArmour(
                    isFresh(pickup.tick, tick, PIGLIN_RETENTION_TICKS),
                    pickup.observedInOffhand,
                    pickup.releasedTick,
                    tick,
                    goldenArmourSlot(equipped.type) == slot,
                    equipped.isSimilar(pickup.item),
                )
        ) {
            owners[slot] = pickup.playerId
            pickups.remove(slot)
            if (pickups.isEmpty()) piglinPickups.remove(piglin.uniqueId)
            val player = Bukkit.getPlayer(pickup.playerId)
            if (
                player != null &&
                    hasFullGoldenArmourFrom(piglin, owners, pickup.playerId) &&
                    tracking.test(player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)
            ) {
                piglinPickups.remove(piglin.uniqueId)
                piglinArmourOwners.remove(piglin.uniqueId)
                completion.accept(player, BingoTask.EQUIP_PIGLIN_GOLD_ARMOUR)
            }
            return
        }
        if (pickup != null && !isFresh(pickup.tick, tick, PIGLIN_RETENTION_TICKS)) {
            pickups.remove(slot)
            if (pickups.isEmpty()) piglinPickups.remove(piglin.uniqueId)
        }
        owners.remove(slot)
        if (owners.isEmpty()) piglinArmourOwners.remove(piglin.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityLeashed(event: PlayerLeashEntityEvent) {
        if (event.entity is Bee && tracking.test(event.player, BingoTask.LEASHED_BEE_STING))
            putBounded(leashedBeeOwners, event.entity.uniqueId, event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityUnleashed(event: PlayerUnleashEntityEvent) {
        leashedBeeOwners.remove(event.entity.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityUnleashed(event: EntityUnleashEvent) {
        leashedBeeOwners.remove(event.entity.uniqueId)
    }

    private fun confirmBeeStung(beeId: UUID, ownerId: UUID, token: AttemptToken) {
        val entity = Bukkit.getEntity(beeId)
        val owner = Bukkit.getPlayer(ownerId)
        if (
            entity is Bee &&
                entity.hasStung() &&
                owner != null &&
                isCurrent(ownerId, token) &&
                tracking.test(owner, BingoTask.LEASHED_BEE_STING)
        )
            completion.accept(owner, BingoTask.LEASHED_BEE_STING)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStatueMined(event: BlockDropItemEvent) {
        if (
            Tag.COPPER_GOLEM_STATUES.isTagged(event.blockState.type) &&
                event.items.any { Tag.ITEMS_COPPER_GOLEM_STATUES.isTagged(it.itemStack.type) } &&
                tracking.test(event.player, BingoTask.MINE_COPPER_GOLEM_STATUE)
        )
            completion.accept(event.player, BingoTask.MINE_COPPER_GOLEM_STATUE)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        val id = event.entity.uniqueId
        if (event.cause == EntityRemoveEvent.Cause.EXPLODE && event.entity is ExplosiveMinecart) {
            val primed = primedMinecarts.remove(id)
            val owner = primed?.let { Bukkit.getPlayer(it.playerId) }
            if (
                owner != null &&
                    primed != null &&
                    isFresh(primed.tick, Bukkit.getCurrentTick(), primed.maximumAge) &&
                    tracking.test(owner, BingoTask.DETONATE_TNT_MINECART)
            )
                completion.accept(owner, BingoTask.DETONATE_TNT_MINECART)
        } else primedMinecarts.remove(id)
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
        super.resetPlayer(playerId)
        projectileShots.values.removeIf { it.playerId == playerId }
        bellAttempts.keys.removeIf { it.playerId == playerId }
        removeOwnedBlocks(playerId, berryBushOwners, berryBushesByPlayer)
        berryDamage.values.removeIf { it.playerId == playerId }
        removeOwnedBlocks(playerId, fireOwners, firesByPlayer)
        minecartContacts.values.removeIf { it.playerId == playerId }
        primedMinecarts.values.removeIf { it.playerId == playerId }
        shelfAttempts.remove(playerId)
        pigShearAttempts.values.removeIf { it.playerId == playerId }
        pendingSelfArrowTotems.remove(playerId)
        droppedArmour.values.removeIf { it.playerId == playerId }
        piglinPickups.values.forEach { it.values.removeIf { value -> value.playerId == playerId } }
        piglinPickups.values.removeIf { it.isEmpty() }
        piglinArmourOwners.values.forEach { it.values.removeIf { owner -> owner == playerId } }
        piglinArmourOwners.values.removeIf { it.isEmpty() }
        leashedBeeOwners.values.removeIf { it == playerId }
        removeOwnedBlocks(playerId, pressurePlateOwners, pressurePlatesByPlayer)
        pressurePlateTriggers.values.removeIf { it.playerId == playerId }
    }

    override fun clear() {
        super.clear()
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
        val left = shelfLeftOf(facing) ?: return emptyList()
        if (!isMatchingShelf(origin, facing)) return emptyList()
        val connected = ArrayList<Block>(3)
        connected.add(origin)
        var cursor = origin
        for (distance in 0 until 2) {
            val candidate = cursor.getRelative(left)
            if (!isMatchingShelf(candidate, facing)) break
            val shelf = candidate.blockData as Shelf
            if (shelf.sideChain != SideChaining.ChainPart.CENTER && shelf.sideChain != SideChaining.ChainPart.LEFT)
                break
            connected.add(0, candidate)
            cursor = candidate
            if (shelf.sideChain == SideChaining.ChainPart.LEFT) break
        }
        cursor = origin
        for (distance in 0 until 2) {
            val candidate = cursor.getRelative(left.oppositeFace)
            if (!isMatchingShelf(candidate, facing)) break
            val shelf = candidate.blockData as Shelf
            if (shelf.sideChain != SideChaining.ChainPart.CENTER && shelf.sideChain != SideChaining.ChainPart.RIGHT)
                break
            connected.add(candidate)
            cursor = candidate
            if (shelf.sideChain == SideChaining.ChainPart.RIGHT) break
        }
        if (connected.size != 3) return emptyList()
        val expected = arrayOf(SideChaining.ChainPart.LEFT, SideChaining.ChainPart.CENTER, SideChaining.ChainPart.RIGHT)
        for (index in connected.indices) if ((connected[index].blockData as Shelf).sideChain != expected[index])
            return emptyList()
        return connected.map(BlockKey::from)
    }

    private fun shelfContents(keys: List<BlockKey>, facing: BlockFace): Array<ItemStack?>? {
        val items = ArrayList<ItemStack?>(9)
        val expectedParts =
            arrayOf(SideChaining.ChainPart.LEFT, SideChaining.ChainPart.CENTER, SideChaining.ChainPart.RIGHT)
        for (blockIndex in keys.indices) {
            val block = blockAt(keys[blockIndex]) ?: return null
            val data = block.blockData as? Shelf ?: return null
            if (!data.isPowered || data.facing != facing || data.sideChain != expectedParts[blockIndex]) return null
            val shelf = block.state as? org.bukkit.block.Shelf ?: return null
            val contents = shelf.inventory.storageContents
            if (contents.size != 3) return null
            for (item in contents) items.add(copyOrNull(item))
        }
        return items.toTypedArray()
    }

    private fun piglinPickupsFor(piglinId: UUID): EnumMap<EquipmentSlot, PiglinPickup> {
        if (!piglinPickups.containsKey(piglinId) && piglinPickups.size >= MAX_TRANSIENT_ENTRIES) {
            val oldest = piglinPickups.keys.first()
            piglinPickups.remove(oldest)
            piglinArmourOwners.remove(oldest)
        }
        return piglinPickups.getOrPut(piglinId) { EnumMap(EquipmentSlot::class.java) }
    }

    private fun piglinArmourOwnersFor(piglinId: UUID): EnumMap<EquipmentSlot, UUID> {
        if (!piglinArmourOwners.containsKey(piglinId) && piglinArmourOwners.size >= MAX_TRANSIENT_ENTRIES) {
            val oldest = piglinArmourOwners.keys.first()
            piglinArmourOwners.remove(oldest)
            piglinPickups.remove(oldest)
        }
        return piglinArmourOwners.getOrPut(piglinId) { EnumMap(EquipmentSlot::class.java) }
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
        projectileShots.entries.removeIf {
            tick - it.value.tick > PROJECTILE_RETENTION_TICKS || Bukkit.getEntity(it.key) == null
        }
        bellAttempts.entries.removeIf { !isFresh(it.value, tick, SHORT_CORRELATION_TICKS) }
        berryDamage.values.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
        minecartContacts.values.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
        turtleScutes.entries.removeIf {
            tick - it.value.tick > ITEM_RETENTION_TICKS || Bukkit.getEntity(it.key) == null
        }
        pigShearAttempts.values.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
        pendingSelfArrowTotems.values.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
        droppedArmour.entries.removeIf {
            tick - it.value.tick > ITEM_RETENTION_TICKS || Bukkit.getEntity(it.key) == null
        }
        piglinPickups.values.forEach { map -> map.values.removeIf { tick - it.tick > PIGLIN_RETENTION_TICKS } }
        piglinPickups.values.removeIf { it.isEmpty() }
        pressurePlateTriggers.values.removeIf { !isFresh(it.tick, tick, SHORT_CORRELATION_TICKS) }
    }

    private fun pruneTransientStateIfDue(tick: Int) {
        if (lastPruneTick != Int.MIN_VALUE) {
            val age = tick - lastPruneTick
            if (age >= 0 && age < 20) return
        }
        lastPruneTick = tick
        pruneTransientState(tick)
    }

    private data class ProjectileShot(val playerId: UUID, val origin: Location, val tick: Int)

    private data class BellAttemptKey(val bell: BlockKey, val playerId: UUID)

    private data class DoorCandidate(val block: BlockKey, val material: Material)

    private data class TimedPlayer(val playerId: UUID, val tick: Int)

    private data class MinecartContact(val playerId: UUID, val fire: BlockKey, val tick: Int)

    private data class PrimedMinecart(val playerId: UUID, val tick: Int, val maximumAge: Int)

    private data class PigShearAttempt(val playerId: UUID, val tick: Int, val dropObserved: Boolean)

    private class ScuteProvenance(val tick: Int) {
        private var consumed = false

        fun consume(): Boolean {
            if (consumed) return false
            consumed = true
            return true
        }

        fun isConsumed(): Boolean = consumed
    }

    private data class ShelfAttempt(
        val tick: Int,
        val shelves: List<BlockKey>,
        val facing: BlockFace,
        val previousShelves: Array<ItemStack?>,
        val previousHotbar: Array<ItemStack?>,
    )

    private data class DroppedArmour(val playerId: UUID, val slot: EquipmentSlot, val item: ItemStack, val tick: Int)

    private data class PiglinPickup(
        val playerId: UUID,
        val item: ItemStack,
        val tick: Int,
        val observedInOffhand: Boolean,
        val releasedTick: Int,
    )

    companion object {
        private const val MAX_OWNED_BLOCKS_PER_PLAYER = 2_048
        private const val SHORT_CORRELATION_TICKS = 3
        private const val PROJECTILE_RETENTION_TICKS = 20 * 60 * 5
        private const val ITEM_RETENTION_TICKS = 20 * 60 * 10
        private const val PIGLIN_RETENTION_TICKS = 20 * 30
        private const val TEN_BLOCKS_SQUARED = 100.0
        private const val PARROT_DANCE_DISTANCE_SQUARED = 3.46 * 3.46
        private val FACES =
            arrayOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        private val HORIZONTAL_FACES = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        private val GOLD_ARMOUR_SLOTS =
            EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

        private fun hotbarContents(player: Player): Array<ItemStack?> =
            Array(9) { copyOrNull(player.inventory.getItem(it)) }

        private fun isEmpty(item: ItemStack?): Boolean = item == null || item.type.isAir || item.amount <= 0

        @JvmStatic
        fun <T> transitionedAwayFromTrackedItem(
            tracked: T,
            previous: T,
            current: T,
            similarity: BiPredicate<T, T>,
        ): Boolean = similarity.test(previous, tracked) && !similarity.test(current, tracked)

        @JvmStatic
        fun canAttributePiglinArmour(
            fresh: Boolean,
            observedInOffhand: Boolean,
            releasedTick: Int,
            currentTick: Int,
            equippedSlotMatches: Boolean,
            equippedItemMatches: Boolean,
        ): Boolean =
            fresh &&
                observedInOffhand &&
                releasedTick >= 0 &&
                releasedTick == currentTick &&
                equippedSlotMatches &&
                equippedItemMatches

        @JvmStatic
        fun shouldRetainFireOwnerAfterPlaceEvent(
            placedBlockIsFire: Boolean,
            placingPlayerId: UUID,
            recordedFireOwner: UUID?,
        ): Boolean = placedBlockIsFire && placingPlayerId == recordedFireOwner

        private fun copyOrNull(item: ItemStack?): ItemStack? = if (isEmpty(item)) null else item!!.clone()

        private fun shelfLeftOf(facing: BlockFace): BlockFace? =
            when (facing) {
                BlockFace.NORTH -> BlockFace.EAST
                BlockFace.SOUTH -> BlockFace.WEST
                BlockFace.EAST -> BlockFace.SOUTH
                BlockFace.WEST -> BlockFace.NORTH
                else -> null
            }

        private fun isMatchingShelf(block: Block, facing: BlockFace): Boolean {
            val shelf = block.blockData
            return shelf is Shelf &&
                shelf.isPowered &&
                shelf.facing == facing &&
                shelf.sideChain != SideChaining.ChainPart.UNCONNECTED
        }

        private fun isPowered(block: Block): Boolean {
            val data = block.blockData
            if (data is Powerable) return data.isPowered
            return data is AnaloguePowerable && data.power > 0
        }

        private fun hasFullGoldenArmourFrom(piglin: Piglin, owners: Map<EquipmentSlot, UUID>, playerId: UUID): Boolean {
            if (!allArmourSlotsOwnedBy(owners, playerId)) return false
            for (slot in GOLD_ARMOUR_SLOTS) if (goldenArmourSlot(piglin.equipment.getItem(slot).type) != slot)
                return false
            return true
        }

        @JvmStatic
        fun allArmourSlotsOwnedBy(owners: Map<EquipmentSlot, UUID>, playerId: UUID): Boolean = GOLD_ARMOUR_SLOTS.all {
            owners[it] == playerId
        }

        private fun goldenArmourSlot(material: Material): EquipmentSlot? =
            when (material) {
                Material.GOLDEN_HELMET -> EquipmentSlot.HEAD
                Material.GOLDEN_CHESTPLATE -> EquipmentSlot.CHEST
                Material.GOLDEN_LEGGINGS -> EquipmentSlot.LEGS
                Material.GOLDEN_BOOTS -> EquipmentSlot.FEET
                else -> null
            }

        private fun canonicalDoor(block: Block): Block? {
            if (!Tag.DOORS.isTagged(block.type)) return null
            val door = block.blockData as? Door ?: return null
            return if (door.half == Bisected.Half.TOP) block.getRelative(BlockFace.DOWN) else block
        }
    }
}
