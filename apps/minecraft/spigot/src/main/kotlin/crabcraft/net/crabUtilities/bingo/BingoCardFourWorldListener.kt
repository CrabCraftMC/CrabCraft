package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.entity.EntityEquipmentChangedEvent
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.entity.Blaze
import org.bukkit.entity.Boat
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.HappyGhast
import org.bukkit.entity.Player
import org.bukkit.entity.Snowball
import org.bukkit.entity.Snowman
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the construction and transport tasks on Bingo #4. */
class BingoCardFourWorldListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>
) : BingoDetector {
    private val snowBuilderKey = NamespacedKey(plugin, "bingo_card4_snow_builder")
    private val snowBuiltAtKey = NamespacedKey(plugin, "bingo_card4_snow_built_at")
    private val harnessOwnerKey = NamespacedKey(plugin, "bingo_card4_harness_owner")
    private val harnessEquippedAtKey = NamespacedKey(plugin, "bingo_card4_harness_equipped_at")
    private val snowBuildAttempts = LinkedHashMap<BlockKey, BuildAttempt>()
    private val harnessAttempts = LinkedHashMap<UUID, HarnessAttempt>()
    private val playerGenerations = HashMap<UUID, Long>()
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var clearedAtMillis = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortalCreated(event: PortalCreateEvent) {
        val player = event.entity
        if (event.reason != PortalCreateEvent.CreateReason.FIRE || player !is Player ||
            !tracking.test(player, BingoTask.FOUR_BY_FOUR_NETHER_PORTAL)) return
        val portalBlocks = LinkedHashSet<BlockPoint>()
        for (state in event.blocks) {
            if (state.type == Material.NETHER_PORTAL) portalBlocks.add(BlockPoint.from(state))
        }
        if (!formsFourByFourPortal(portalBlocks)) return
        val playerId = player.uniqueId
        val worldId = event.world.uid
        val token = attemptToken(playerId)
        val expected = java.util.Set.copyOf(portalBlocks)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmPortal(playerId, worldId, expected, token) })
    }

    private fun confirmPortal(playerId: UUID, worldId: UUID, expected: Set<BlockPoint>, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val world = Bukkit.getWorld(worldId)
        if (player == null || world == null || !isCurrent(playerId, token) || !tracking.test(player, BingoTask.FOUR_BY_FOUR_NETHER_PORTAL)) return
        for (point in expected) {
            if (!world.isChunkLoaded(point.x() shr 4, point.z() shr 4) ||
                world.getBlockAt(point.x(), point.y(), point.z()).type != Material.NETHER_PORTAL) return
        }
        completion.accept(player, BingoTask.FOUR_BY_FOUR_NETHER_PORTAL)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnderPearlTeleport(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL ||
            !isHundredBlockHorizontalTeleport(event.from, event.to!!) ||
            !tracking.test(event.player, BingoTask.ENDER_PEARL_TELEPORT_HUNDRED)) return
        completion.accept(event.player, BingoTask.ENDER_PEARL_TELEPORT_HUNDRED)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGolemPumpkinPlaced(event: BlockPlaceEvent) {
        if (!event.canBuild() || !isGolemPumpkin(event.blockPlaced.type)) return
        val player = event.player
        val playerId = player.uniqueId
        val tick = Bukkit.getCurrentTick()
        val pumpkin = event.blockPlaced
        if (!tracking.test(player, BingoTask.SNOW_GOLEM_KILLS_BLAZE)) return
        val attempt = BuildAttempt(playerId, tick, attemptToken(playerId))
        for (direction in CARTESIAN_FACES) {
            if (pumpkin.getRelative(direction).type != Material.SNOW_BLOCK || pumpkin.getRelative(direction, 2).type != Material.SNOW_BLOCK) continue
            val key = BlockKey.from(pumpkin.getRelative(direction, 2))
            putBounded(snowBuildAttempts, key, attempt)
            expireBuildAttempt(snowBuildAttempts, key, attempt)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGolemSpawned(event: CreatureSpawnEvent) {
        val tick = Bukkit.getCurrentTick()
        val snowman = event.entity
        if (snowman is Snowman && event.spawnReason == CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) {
            val attempt = snowBuildAttempts.remove(BlockKey.from(snowman.location))
            if (validBuildAttempt(attempt, tick, BingoTask.SNOW_GOLEM_KILLS_BLAZE)) {
                setMarker(snowman, snowBuilderKey, snowBuiltAtKey, attempt!!.playerId(), System.currentTimeMillis())
            }
        }
    }

    private fun validBuildAttempt(attempt: BuildAttempt?, tick: Int, task: BingoTask): Boolean {
        if (attempt == null || attempt.tick() != tick || !isCurrent(attempt.playerId(), attempt.token())) return false
        val player = Bukkit.getPlayer(attempt.playerId())
        return player != null && tracking.test(player, task)
    }

    private fun expireBuildAttempt(attempts: MutableMap<BlockKey, BuildAttempt>, key: BlockKey, attempt: BuildAttempt) {
        Bukkit.getScheduler().runTask(plugin, Runnable { attempts.remove(key, attempt) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlazeKilled(event: EntityDeathEvent) {
        if (event.entity !is Blaze) return
        val snowball = event.damageSource.directEntity as? Snowball ?: return
        val snowman = snowball.shooter as? Snowman ?: return
        val marker = markerFrom(snowman, snowBuilderKey, snowBuiltAtKey)
        if (marker == null || !markerIsCurrent(marker)) return
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && tracking.test(player, BingoTask.SNOW_GOLEM_KILLS_BLAZE)) completion.accept(player, BingoTask.SNOW_GOLEM_KILLS_BLAZE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHappyGhastInteracted(event: PlayerInteractEntityEvent) {
        val ghast = event.rightClicked
        if (ghast !is HappyGhast || !ghast.isAdult || !isHarness(event.player.inventory.getItem(event.hand))) return
        if (!isEmpty(ghast.equipment.getItem(EquipmentSlot.BODY))) return
        clearMarker(ghast, harnessOwnerKey, harnessEquippedAtKey)
        val player = event.player
        if (!tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) return
        val playerId = player.uniqueId
        val ghastId = ghast.uniqueId
        val attempt = HarnessAttempt(playerId, event.player.inventory.getItem(event.hand).type, attemptToken(playerId))
        putBounded(harnessAttempts, ghastId, attempt)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmHarnessEquipped(ghastId, attempt) })
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { harnessAttempts.remove(ghastId, attempt) }, 2L)
    }

    private fun confirmHarnessEquipped(ghastId: UUID, attempt: HarnessAttempt) {
        if (attempt != harnessAttempts[ghastId]) return
        val player = Bukkit.getPlayer(attempt.playerId())
        val ghast = Bukkit.getEntity(ghastId)
        if (player == null || ghast !is HappyGhast || !ghast.isValid || ghast.isDead || !ghast.isAdult ||
            ghast.equipment.getItem(EquipmentSlot.BODY).type != attempt.harnessType() || !isHarness(ghast.equipment.getItem(EquipmentSlot.BODY)) ||
            !isCurrent(attempt.playerId(), attempt.token()) || !tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) return
        setMarker(ghast, harnessOwnerKey, harnessEquippedAtKey, attempt.playerId(), System.currentTimeMillis())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBoatLeashed(event: PlayerLeashEntityEvent) {
        val boat = event.entity
        val ghast = event.leashHolder
        if (boat !is Boat || ghast !is HappyGhast || !ghast.isAdult || !isHarness(ghast.equipment.getItem(EquipmentSlot.BODY))) return
        val player = event.player
        val marker = markerFrom(ghast, harnessOwnerKey, harnessEquippedAtKey)
        if (marker == null || marker.playerId() != player.uniqueId || !markerIsCurrent(marker) ||
            !tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) return
        val enemyId = liveEnemyPassenger(boat) ?: return
        val playerId = player.uniqueId
        val boatId = boat.uniqueId
        val ghastId = ghast.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmBoatLeashed(playerId, boatId, ghastId, enemyId, marker, token) })
    }

    private fun confirmBoatLeashed(playerId: UUID, boatId: UUID, ghastId: UUID, enemyId: UUID, expectedMarker: PersistentMarker, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val boat = Bukkit.getEntity(boatId)
        val ghast = Bukkit.getEntity(ghastId)
        if (player == null || boat !is Boat || ghast !is HappyGhast || !boat.isValid || boat.isDead || !boat.isLeashed ||
            !ghast.isValid || ghast.isDead || !ghast.isAdult || !isHarness(ghast.equipment.getItem(EquipmentSlot.BODY)) ||
            expectedMarker != markerFrom(ghast, harnessOwnerKey, harnessEquippedAtKey) || !markerIsCurrent(expectedMarker) ||
            !isCurrent(playerId, token) || !tracking.test(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)) return
        val leashHolder = try { boat.leashHolder } catch (ignored: IllegalStateException) { return }
        if (ghastId != leashHolder.uniqueId) return
        val sameEnemyAboard = boat.passengers.any {
            it.uniqueId == enemyId && it is Enemy && it.isValid && !it.isDead
        }
        if (sameEnemyAboard) completion.accept(player, BingoTask.HAPPY_GHAST_HOSTILE_BOAT)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEquipmentChanged(event: EntityEquipmentChangedEvent) {
        val bodyChange = event.equipmentChanges[EquipmentSlot.BODY]
        val ghast = event.entity
        if (bodyChange != null && ghast is HappyGhast) {
            val pending = harnessAttempts[ghast.uniqueId]
            val expectedDirectEquip = isHarness(bodyChange.newItem()) && pending != null && bodyChange.newItem().type == pending.harnessType()
            val existing = markerFrom(ghast, harnessOwnerKey, harnessEquippedAtKey)
            val persistedHarnessLoaded = isEmpty(bodyChange.oldItem()) && isHarness(bodyChange.newItem()) && existing != null && markerIsCurrent(existing)
            if (!expectedDirectEquip && !persistedHarnessLoaded) {
                clearMarker(ghast, harnessOwnerKey, harnessEquippedAtKey)
                harnessAttempts.remove(ghast.uniqueId)
            }
        }
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        playerResetAtMillis[playerId] = System.currentTimeMillis()
        snowBuildAttempts.values.removeIf { it.playerId() == playerId }
        harnessAttempts.values.removeIf { it.playerId() == playerId }
    }

    override fun clear() {
        detectorGeneration++
        clearedAtMillis = System.currentTimeMillis()
        snowBuildAttempts.clear()
        harnessAttempts.clear()
        playerGenerations.clear()
        playerResetAtMillis.clear()
    }

    private fun attemptToken(playerId: UUID): AttemptToken = AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))
    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration() == detectorGeneration && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

    private fun markerFrom(entity: Entity, ownerKey: NamespacedKey, timestampKey: NamespacedKey): PersistentMarker? {
        val data = entity.persistentDataContainer
        val owner = data.get(ownerKey, PersistentDataType.STRING)
        val timestamp = data.get(timestampKey, PersistentDataType.LONG)
        if (owner == null || timestamp == null) return null
        return try { PersistentMarker(UUID.fromString(owner), timestamp) } catch (ignored: IllegalArgumentException) {
            clearMarker(entity, ownerKey, timestampKey)
            null
        }
    }

    private fun markerIsCurrent(marker: PersistentMarker): Boolean {
        val now = System.currentTimeMillis()
        val resetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L)
        return marker.timestamp() > clearedAtMillis && marker.timestamp() > resetAt && marker.timestamp() <= now &&
            now - marker.timestamp() <= MAX_PERSISTENT_ATTRIBUTION_MILLIS
    }

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val ENDER_PEARL_HORIZONTAL_DISTANCE_SQUARED = 100.0 * 100.0
        private const val MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private val CARTESIAN_FACES = arrayOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

        @JvmStatic
        fun formsFourByFourPortal(points: Set<BlockPoint>): Boolean {
            if (points.size != 16) return false
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var minZ = Int.MAX_VALUE
            var maxZ = Int.MIN_VALUE
            for (point in points) {
                minX = minOf(minX, point.x())
                maxX = maxOf(maxX, point.x())
                minY = minOf(minY, point.y())
                maxY = maxOf(maxY, point.y())
                minZ = minOf(minZ, point.z())
                maxZ = maxOf(maxZ, point.z())
            }
            if (maxY - minY != 3) return false
            val xPlane = minX == maxX && maxZ - minZ == 3
            val zPlane = minZ == maxZ && maxX - minX == 3
            if (!xPlane && !zPlane) return false
            for (y in minY..maxY) {
                for (horizontal in 0 until 4) {
                    val expected = if (xPlane) BlockPoint(minX, y, minZ + horizontal) else BlockPoint(minX + horizontal, y, minZ)
                    if (!points.contains(expected)) return false
                }
            }
            return true
        }

        @JvmStatic
        fun isHundredBlockHorizontalTeleport(from: Location, to: Location): Boolean =
            from.world != null && to.world != null && isHundredBlockHorizontalTeleport(
                from.world!!.uid, from.x, from.z, to.world!!.uid, to.x, to.z)

        @JvmStatic
        fun isHundredBlockHorizontalTeleport(fromWorld: UUID, fromX: Double, fromZ: Double, toWorld: UUID, toX: Double, toZ: Double): Boolean {
            if (fromWorld != toWorld) return false
            val x = toX - fromX
            val z = toZ - fromZ
            return x * x + z * z >= ENDER_PEARL_HORIZONTAL_DISTANCE_SQUARED
        }

        @JvmStatic private fun isGolemPumpkin(material: Material): Boolean = material == Material.CARVED_PUMPKIN || material == Material.JACK_O_LANTERN
        @JvmStatic private fun liveEnemyPassenger(boat: Boat): UUID? = boat.passengers.asSequence()
            .filter { it is Enemy }.filter { it.isValid }.filter { !it.isDead }.map { it.uniqueId }.firstOrNull()
        @JvmStatic private fun isHarness(item: ItemStack?): Boolean = !isEmpty(item) && Tag.ITEMS_HARNESSES.isTagged(item!!.type)
        @JvmStatic private fun isEmpty(item: ItemStack?): Boolean = item == null || item.isEmpty || item.type.isAir

        @JvmStatic private fun setMarker(entity: Entity, ownerKey: NamespacedKey, timestampKey: NamespacedKey, playerId: UUID, timestamp: Long) {
            val data = entity.persistentDataContainer
            data.set(ownerKey, PersistentDataType.STRING, playerId.toString())
            data.set(timestampKey, PersistentDataType.LONG, timestamp)
        }

        @JvmStatic private fun clearMarker(entity: Entity, ownerKey: NamespacedKey, timestampKey: NamespacedKey) {
            val data = entity.persistentDataContainer
            data.remove(ownerKey)
            data.remove(timestampKey)
        }

        @JvmStatic private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) {
                val oldest = map.keys.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            map[key] = value
        }
    }
    data class BlockPoint(private val x: Int, private val y: Int, private val z: Int) {
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z
        companion object {
            @JvmStatic fun from(state: BlockState): BlockPoint = BlockPoint(state.x, state.y, state.z)
        }
    }
    private data class BlockKey(private val worldId: UUID, private val x: Int, private val y: Int, private val z: Int) {
        fun worldId(): UUID = worldId
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z
        companion object {
            @JvmStatic fun from(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
            @JvmStatic fun from(location: Location): BlockKey = BlockKey(location.world!!.uid, location.blockX, location.blockY, location.blockZ)
        }
    }
    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }
    private data class BuildAttempt(private val playerId: UUID, private val tick: Int, private val token: AttemptToken) {
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
        fun token(): AttemptToken = token
    }
    private data class HarnessAttempt(private val playerId: UUID, private val harnessType: Material, private val token: AttemptToken) {
        fun playerId(): UUID = playerId
        fun harnessType(): Material = harnessType
        fun token(): AttemptToken = token
    }
    private data class PersistentMarker(private val playerId: UUID, private val timestamp: Long) {
        fun playerId(): UUID = playerId
        fun timestamp(): Long = timestamp
    }
}
