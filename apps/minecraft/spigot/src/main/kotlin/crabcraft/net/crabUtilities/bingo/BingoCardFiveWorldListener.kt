package crabcraft.net.crabUtilities.bingo
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.LodestoneTracker
import io.papermc.paper.event.block.BlockBreakBlockEvent
import java.util.ArrayDeque
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.type.Snow
import org.bukkit.entity.Player
import org.bukkit.entity.minecart.PoweredMinecart
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the construction and world-interaction tasks on Bingo #5. */
class BingoCardFiveWorldListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier
) : BingoDetector {
    private val dripleafOwners = LinkedHashMap<BlockKey, UUID>()
    private val dripleavesByPlayer = HashMap<UUID, LinkedHashSet<BlockKey>>()
    private val snowOwners = LinkedHashMap<BlockKey, UUID>()
    private val snowByPlayer = HashMap<UUID, LinkedHashSet<BlockKey>>()
    private val compassBindingAttempts = HashMap<UUID, CompassBindingAttempt>()
    private val playerGenerations = HashMap<UUID, Long>()
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var detectorGeneration = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        val key = BlockKey.from(block)
        val existingSnowOwner = ownerOf(key, OwnershipKind.SNOW)
        removeOwnedBlock(key, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer)
        if (!event.canBuild()) {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
            return
        }
        val player = event.player
        val playerId = player.uniqueId
        if (isBigDripleaf(block.type) && tracking.test(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)) {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
            putOwnedBlock(key, playerId, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer)
            if (ownedDripleafColumnHeight(key, playerId) >= REQUIRED_DRIPLEAF_HEIGHT) {
                completion.accept(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)
            }
            return
        }
        if (block.type == Material.SNOW && tracking.test(player, BingoTask.SNOW_EVERY_HEIGHT)) {
            val beganWithFreshBlock = event.blockReplacedState.type != Material.SNOW
            val continuedOwnLayers = playerId == existingSnowOwner
            if (canOwnSnowLayer(beganWithFreshBlock, continuedOwnLayers)) {
                putOwnedBlock(key, playerId, OwnershipKind.SNOW, snowOwners, snowByPlayer)
                if (connectedSnowCoversEveryHeight(key, playerId)) {
                    completion.accept(player, BingoTask.SNOW_EVERY_HEIGHT)
                }
            } else {
                removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
            }
        } else {
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFertilised(event: BlockFertilizeEvent) {
        val player = event.player
        if (player == null || !tracking.test(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)) return
        val changedDripleaf = LinkedHashSet<BlockKey>()
        for (state in event.blocks) {
            if (isBigDripleaf(state.type)) changedDripleaf.add(BlockKey.from(state))
        }
        if (changedDripleaf.isEmpty()) return
        val playerId = player.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            confirmDripleafGrowth(playerId, changedDripleaf, token)
        })
    }

    private fun confirmDripleafGrowth(playerId: UUID, changedDripleaf: Set<BlockKey>, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !isCurrent(playerId, token)
                || !tracking.test(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)) return
        for (key in changedDripleaf) {
            val block = blockAt(key)
            if (block == null || !isBigDripleaf(block.type)) continue
            removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
            putOwnedBlock(key, playerId, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer)
            if (ownedDripleafColumnHeight(key, playerId) >= REQUIRED_DRIPLEAF_HEIGHT) {
                completion.accept(player, BingoTask.BUILD_TEN_TALL_DRIPLEAF)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCompassUsed(event: PlayerInteractEvent) {
        if (event.hand == null || event.action != Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY
                || event.clickedBlock?.type != Material.LODESTONE
                || event.item?.type != Material.COMPASS) return
        val player = event.player
        if (!tracking.test(player, BingoTask.LODESTONE_COMPASS)) return
        val lodestone = BlockKey.from(event.clickedBlock!!)
        val playerId = player.uniqueId
        val previousBoundCount = countCompassesPointingAt(player, lodestone)
        val attempt = CompassBindingAttempt(lodestone, previousBoundCount, attemptToken(playerId))
        compassBindingAttempts[playerId] = attempt
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmCompassBound(playerId, attempt) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCompassDropped(event: PlayerDropItemEvent) {
        val playerId = event.player.uniqueId
        val attempt = compassBindingAttempts[playerId]
        val item = event.itemDrop.itemStack
        if (attempt == null || !isCurrent(playerId, attempt.token) || item.type != Material.COMPASS) return
        val tracker = item.getData(DataComponentTypes.LODESTONE_TRACKER)
        if (tracker != null && pointsAt(tracker, attempt.lodestone)) {
            attempt.matchingDropObserved = true
        }
    }

    private fun confirmCompassBound(playerId: UUID, attempt: CompassBindingAttempt) {
        compassBindingAttempts.remove(playerId, attempt)
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !isCurrent(playerId, attempt.token)
                || !tracking.test(player, BingoTask.LODESTONE_COMPASS)) return
        val currentBoundCount = countCompassesPointingAt(player, attempt.lodestone)
        if (compassBindingSucceeded(
                attempt.previousBoundCount, currentBoundCount, attempt.matchingDropObserved)) {
            completion.accept(player, BingoTask.LODESTONE_COMPASS)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMinecartFuelled(event: PlayerInteractEntityEvent) {
        val minecart = event.rightClicked as? PoweredMinecart ?: return
        val item: ItemStack? = event.player.inventory.getItem(event.hand)
        if (item == null || !Tag.ITEMS_FURNACE_MINECART_FUEL.isTagged(item.type)
                || !tracking.test(event.player, BingoTask.POWER_FURNACE_MINECART)) return
        val playerId = event.player.uniqueId
        val minecartId = minecart.uniqueId
        val previousFuel = minecart.fuel
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            confirmMinecartFuelled(playerId, minecartId, previousFuel, token)
        })
    }

    private fun confirmMinecartFuelled(playerId: UUID, minecartId: UUID, previousFuel: Int,
            token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val entity = Bukkit.getEntity(minecartId)
        if (player != null && entity is PoweredMinecart && entity.isValid && !entity.isDead
                && fuelIncreased(previousFuel, entity.fuel) && isCurrent(playerId, token)
                && tracking.test(player, BingoTask.POWER_FURNACE_MINECART)) {
            completion.accept(player, BingoTask.POWER_FURNACE_MINECART)
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
    fun onBlockGrown(event: BlockGrowEvent) {
        removeTrackedBlock(BlockKey.from(event.block))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFaded(event: BlockFadeEvent) {
        removeTrackedBlock(BlockKey.from(event.block))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangedBlock(event: EntityChangeBlockEvent) {
        val key = BlockKey.from(event.block)
        if (!isBigDripleaf(event.to)) {
            removeOwnedBlock(key, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer)
        }
        if (event.to != Material.SNOW) removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
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

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        playerResetAtMillis[playerId] = System.currentTimeMillis()
        compassBindingAttempts.remove(playerId)
        removeOwnedBlocks(playerId, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer)
        removeOwnedBlocks(playerId, OwnershipKind.SNOW, snowOwners, snowByPlayer)
    }

    override fun clear() {
        detectorGeneration++
        dripleafOwners.clear()
        dripleavesByPlayer.clear()
        snowOwners.clear()
        snowByPlayer.clear()
        compassBindingAttempts.clear()
        playerGenerations.clear()
        playerResetAtMillis.clear()
    }

    private fun ownedDripleafColumnHeight(origin: BlockKey, playerId: UUID): Int {
        val originBlock = blockAt(origin)
        if (originBlock == null || !isBigDripleaf(originBlock.type)
                || playerId != ownerOf(origin, OwnershipKind.DRIPLEAF)) return 0
        val world = originBlock.world
        var bottom = origin.y()
        while (bottom > world.minHeight && isOwnedDripleaf(origin.withY(bottom - 1), playerId)) bottom--
        var height = 0
        for (y in bottom until world.maxHeight) {
            if (!isOwnedDripleaf(origin.withY(y), playerId)) break
            height++
        }
        return height
    }

    private fun isOwnedDripleaf(key: BlockKey, playerId: UUID): Boolean {
        if (playerId != ownerOf(key, OwnershipKind.DRIPLEAF)) return false
        val block = blockAt(key)
        return block != null && isBigDripleaf(block.type)
    }

    private fun connectedSnowCoversEveryHeight(origin: BlockKey, playerId: UUID): Boolean {
        if (playerId != ownerOf(origin, OwnershipKind.SNOW)) return false
        val present = BooleanArray(REQUIRED_SNOW_HEIGHTS + 1)
        var distinct = 0
        val visited = HashSet<BlockKey>()
        val pending = ArrayDeque<BlockKey>()
        pending.add(origin)
        while (!pending.isEmpty()) {
            val key = pending.removeFirst()
            if (!visited.add(key) || playerId != ownerOf(key, OwnershipKind.SNOW)) continue
            val block = blockAt(key) ?: continue
            val snow = block.blockData as? Snow ?: continue
            val layers = snow.layers
            if (layers >= 1 && layers <= REQUIRED_SNOW_HEIGHTS && !present[layers]) {
                present[layers] = true
                distinct++
                if (distinct == REQUIRED_SNOW_HEIGHTS) return true
            }
            for (face in HORIZONTAL_FACES) {
                val adjacent = key.relative(face)
                if (adjacent !in visited && playerId == ownerOf(adjacent, OwnershipKind.SNOW)) {
                    pending.addLast(adjacent)
                }
            }
        }
        return false
    }

    private fun removePistonBlocks(blocks: List<Block>, direction: BlockFace) {
        for (block in blocks) {
            val key = BlockKey.from(block)
            removeTrackedBlock(key)
            removeTrackedBlock(key.relative(direction))
        }
    }

    private fun removeTrackedBlock(key: BlockKey) {
        removeOwnedBlock(key, OwnershipKind.DRIPLEAF, dripleafOwners, dripleavesByPlayer)
        removeOwnedBlock(key, OwnershipKind.SNOW, snowOwners, snowByPlayer)
    }

    private fun blockAt(key: BlockKey): Block? =
            Bukkit.getWorld(key.worldId())?.getBlockAt(key.x(), key.y(), key.z())

    private fun attemptToken(playerId: UUID) = AttemptToken(
            detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken) = attemptIsCurrent(
            detectorGeneration, playerGenerations.getOrDefault(playerId, 0L),
            token.detectorGeneration(), token.playerGeneration())

    private fun putOwnedBlock(key: BlockKey, playerId: UUID, kind: OwnershipKind,
            owners: MutableMap<BlockKey, UUID>, byPlayer: MutableMap<UUID, LinkedHashSet<BlockKey>>) {
        val cardId = activeCardId.asInt
        if (cardId == Int.MIN_VALUE) return
        setOwnershipMarker(key, kind, BlockOwnership(cardId, playerId, System.currentTimeMillis()))
        cacheOwnedBlock(key, playerId, owners, byPlayer)
    }

    private fun cacheOwnedBlock(key: BlockKey, playerId: UUID, owners: MutableMap<BlockKey, UUID>,
            byPlayer: MutableMap<UUID, LinkedHashSet<BlockKey>>) {
        val previous = owners.put(key, playerId)
        if (previous != null && previous != playerId) {
            val previousBlocks = byPlayer[previous]
            if (previousBlocks != null) {
                previousBlocks.remove(key)
                if (previousBlocks.isEmpty()) byPlayer.remove(previous)
            }
        }
        val owned = byPlayer.computeIfAbsent(playerId) { LinkedHashSet() }
        owned.add(key)
        while (owned.size > MAX_OWNED_BLOCKS_PER_PLAYER) {
            val iterator = owned.iterator()
            if (!iterator.hasNext()) break
            val oldest = iterator.next()
            iterator.remove()
            owners.remove(oldest, playerId)
        }
    }

    private fun removeOwnedBlock(key: BlockKey, kind: OwnershipKind,
            owners: MutableMap<BlockKey, UUID>, byPlayer: MutableMap<UUID, LinkedHashSet<BlockKey>>) {
        clearOwnershipMarker(key, kind)
        val playerId = owners.remove(key) ?: return
        val owned = byPlayer[playerId] ?: return
        owned.remove(key)
        if (owned.isEmpty()) byPlayer.remove(playerId)
    }

    private fun removeOwnedBlocks(playerId: UUID, kind: OwnershipKind,
            owners: MutableMap<BlockKey, UUID>, byPlayer: MutableMap<UUID, LinkedHashSet<BlockKey>>) {
        val owned = byPlayer.remove(playerId)
        owned?.forEach { key ->
            owners.remove(key, playerId)
            clearOwnershipMarker(key, kind)
        }
    }

    private fun ownerOf(key: BlockKey, kind: OwnershipKind): UUID? {
        val chunk = chunkAt(key) ?: return null
        val markerKey = ownershipKey(key, kind)
        val encoded = chunk.persistentDataContainer.get(markerKey, PersistentDataType.STRING)
        val ownership = decodeOwnership(encoded)
        val cardId = activeCardId.asInt
        val resetAt = if (ownership == null) 0L
                else playerResetAtMillis.getOrDefault(ownership.playerId(), 0L)
        if (!ownershipIsCurrent(ownership, cardId, resetAt, System.currentTimeMillis())) {
            if (cardId != Int.MIN_VALUE && encoded != null) chunk.persistentDataContainer.remove(markerKey)
            return null
        }
        if (kind == OwnershipKind.DRIPLEAF) {
            cacheOwnedBlock(key, ownership!!.playerId(), dripleafOwners, dripleavesByPlayer)
        } else {
            cacheOwnedBlock(key, ownership!!.playerId(), snowOwners, snowByPlayer)
        }
        return ownership.playerId()
    }

    private fun setOwnershipMarker(key: BlockKey, kind: OwnershipKind, ownership: BlockOwnership) {
        val chunk = chunkAt(key) ?: return
        chunk.persistentDataContainer.set(ownershipKey(key, kind),
                PersistentDataType.STRING, encodeOwnership(ownership))
    }

    private fun clearOwnershipMarker(key: BlockKey, kind: OwnershipKind) {
        chunkAt(key)?.persistentDataContainer?.remove(ownershipKey(key, kind))
    }

    private fun chunkAt(key: BlockKey): Chunk? = Bukkit.getWorld(key.worldId())?.getChunkAt(
            Math.floorDiv(key.x(), 16), Math.floorDiv(key.z(), 16))

    private fun ownershipKey(key: BlockKey, kind: OwnershipKind) = NamespacedKey(plugin,
            "bingo_card5_" + kind.keyPart + "_" + Math.floorMod(key.x(), 16) + "_" + key.y() + "_"
                    + Math.floorMod(key.z(), 16))

    companion object {
        private const val MAX_OWNED_BLOCKS_PER_PLAYER = 4_096
        private const val REQUIRED_DRIPLEAF_HEIGHT = 10
        private const val REQUIRED_SNOW_HEIGHTS = 8
        private val HORIZONTAL_FACES = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

        @JvmStatic
        private fun countCompassesPointingAt(player: Player, lodestone: BlockKey): Int {
            var count = 0
            for (item in player.inventory.contents) {
                if (item == null || item.type != Material.COMPASS) continue
                val tracker = item.getData(DataComponentTypes.LODESTONE_TRACKER)
                if (tracker != null && pointsAt(tracker, lodestone)) count += item.amount
            }
            return count
        }

        @JvmStatic
        fun pointsAt(tracker: LodestoneTracker, lodestone: BlockKey): Boolean {
            val location = tracker.location()
            return location != null && location.world != null && location.world!!.uid == lodestone.worldId()
                    && location.blockX == lodestone.x() && location.blockY == lodestone.y()
                    && location.blockZ == lodestone.z()
        }

        @JvmStatic
        fun isBigDripleaf(material: Material?) =
                material == Material.BIG_DRIPLEAF || material == Material.BIG_DRIPLEAF_STEM

        @JvmStatic
        fun attemptIsCurrent(detectorGeneration: Long, playerGeneration: Long,
                tokenDetectorGeneration: Long, tokenPlayerGeneration: Long) =
                tokenDetectorGeneration == detectorGeneration && tokenPlayerGeneration == playerGeneration

        @JvmStatic
        fun canOwnSnowLayer(beganWithFreshBlock: Boolean, continuedOwnLayers: Boolean) =
                beganWithFreshBlock || continuedOwnLayers

        @JvmStatic
        fun fuelIncreased(previousFuel: Int, currentFuel: Int) = currentFuel > previousFuel

        @JvmStatic
        fun compassBindingSucceeded(previousBoundCount: Int, currentBoundCount: Int,
                matchingDropObserved: Boolean) = currentBoundCount > previousBoundCount || matchingDropObserved

        @JvmStatic
        fun encodeOwnership(ownership: BlockOwnership) =
                ownership.cardId().toString() + "|" + ownership.playerId() + "|" + ownership.timestamp()

        @JvmStatic
        fun decodeOwnership(encoded: String?): BlockOwnership? {
            if (encoded == null) return null
            val parts = encoded.split('|')
            if (parts.size != 3) return null
            return try {
                BlockOwnership(parts[0].toInt(), UUID.fromString(parts[1]), parts[2].toLong())
            } catch (ignored: IllegalArgumentException) {
                null
            }
        }

        @JvmStatic
        fun ownershipIsCurrent(ownership: BlockOwnership?, activeCardId: Int,
                playerResetAt: Long, now: Long) = ownership != null && activeCardId != Int.MIN_VALUE
                && ownership.cardId() == activeCardId && ownership.timestamp() > playerResetAt
                && ownership.timestamp() <= now
    }

    data class BlockKey(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
        fun worldId() = worldId
        fun x() = x
        fun y() = y
        fun z() = z
        fun relative(face: BlockFace) = BlockKey(worldId, x + face.modX, y + face.modY, z + face.modZ)
        fun withY(nextY: Int) = BlockKey(worldId, x, nextY, z)

        companion object {
            @JvmStatic
            fun from(block: Block) = BlockKey(block.world.uid, block.x, block.y, block.z)

            @JvmStatic
            fun from(state: BlockState) = BlockKey(state.world.uid, state.x, state.y, state.z)
        }
    }

    private data class AttemptToken(val detectorGeneration: Long, val playerGeneration: Long) {
        fun detectorGeneration() = detectorGeneration
        fun playerGeneration() = playerGeneration
    }

    data class BlockOwnership(val cardId: Int, val playerId: UUID, val timestamp: Long) {
        fun cardId() = cardId
        fun playerId() = playerId
        fun timestamp() = timestamp
    }

    private enum class OwnershipKind(val keyPart: String) {
        DRIPLEAF("dripleaf"), SNOW("snow")
    }

    private class CompassBindingAttempt(val lodestone: BlockKey, val previousBoundCount: Int,
            val token: AttemptToken) {
        var matchingDropObserved = false
    }
}
