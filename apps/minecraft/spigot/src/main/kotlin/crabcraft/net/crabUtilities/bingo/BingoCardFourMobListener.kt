package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.block.BlockBreakBlockEvent
import io.papermc.paper.event.player.PlayerNameEntityEvent
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.MushroomCow
import org.bukkit.entity.Player
import org.bukkit.entity.Skeleton
import org.bukkit.entity.Silverfish
import org.bukkit.entity.Stray
import org.bukkit.entity.SulfurCube
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.BoundingBox

/** Event-driven detectors for the mob-interaction tasks in Bingo #4. */
class BingoCardFourMobListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>
) : BingoDetector {
    private val mooshroomOwnerKey = NamespacedKey(plugin, "bingo_card4_mooshroom_owner")
    private val mooshroomPrimedAtKey = NamespacedKey(plugin, "bingo_card4_mooshroom_primed_at")
    private val silverfishOwnerKey = NamespacedKey(plugin, "bingo_card4_silverfish_owner")
    private val silverfishNamedAtKey = NamespacedKey(plugin, "bingo_card4_silverfish_named_at")
    private val powderSnowOwners = LinkedHashMap<BlockKey, OwnedPowderSnow>()
    private val playerGenerations = HashMap<UUID, Long>()
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var clearedAtMillis = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSulfurCubeBucketed(event: PlayerBucketEntityEvent) {
        val cube = event.entity as? SulfurCube ?: return
        val player = event.player
        val bodyItem = cube.equipment.getItem(EquipmentSlot.BODY).type
        if (isDiamondSulfurCubeBucketTarget(cube.isAdult, cube.fuseTicks, bodyItem, event.originalBucket.type) &&
            tracking.test(player, BingoTask.SULFUR_CUBE_DIAMOND_BUCKET)) completion.accept(player, BingoTask.SULFUR_CUBE_DIAMOND_BUCKET)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSilverfishNamed(event: PlayerNameEntityEvent) {
        val silverfish = event.entity
        if (silverfish !is Silverfish || !hasName(event.name)) return
        val player = event.player
        if (!tracking.test(player, BingoTask.SILVERFISH_HIDE_IN_STONE)) return
        val data = silverfish.persistentDataContainer
        data.set(silverfishOwnerKey, PersistentDataType.STRING, player.uniqueId.toString())
        data.set(silverfishNamedAtKey, PersistentDataType.LONG, System.currentTimeMillis())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPowderSnowPlaced(event: PlayerBucketEmptyEvent) {
        if (event.bucket != Material.POWDER_SNOW_BUCKET || !tracking.test(event.player, BingoTask.FREEZE_SKELETON_STRAY)) return
        schedulePowderSnowConfirmation(event.player, BlockKey.from(event.block))
    }

    private fun confirmPowderSnowPlaced(playerId: UUID, key: BlockKey, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val block = blockAt(key)
        if (player == null || block == null || block.type != Material.POWDER_SNOW || !isCurrent(playerId, token) ||
            !tracking.test(player, BingoTask.FREEZE_SKELETON_STRAY)) return
        val now = System.currentTimeMillis()
        prunePowderSnowOwners(now)
        putBounded(powderSnowOwners, key, OwnedPowderSnow(playerId, now))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSkeletonFrozen(event: EntityTransformEvent) {
        val skeleton = event.entity
        if (event.transformReason != EntityTransformEvent.TransformReason.FROZEN || skeleton !is Skeleton ||
            event.transformedEntity !is Stray || !skeleton.isInPowderedSnow) return
        val owner = newestIntersectingPowderSnow(skeleton) ?: return
        val player = Bukkit.getPlayer(owner.playerId())
        if (player != null && tracking.test(player, BingoTask.FREEZE_SKELETON_STRAY)) completion.accept(player, BingoTask.FREEZE_SKELETON_STRAY)
    }

    private fun newestIntersectingPowderSnow(skeleton: Skeleton): OwnedPowderSnow? {
        val now = System.currentTimeMillis()
        prunePowderSnowOwners(now)
        val worldId = skeleton.world.uid
        val skeletonBounds = skeleton.boundingBox
        var newest: OwnedPowderSnow? = null
        val iterator = powderSnowOwners.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val key = entry.key
            if (key.worldId() != worldId || !intersectsBlock(skeletonBounds, key.x(), key.y(), key.z())) continue
            val block = blockAt(key)
            if (block == null || block.type != Material.POWDER_SNOW) {
                iterator.remove()
                continue
            }
            val candidate = entry.value
            if (newest == null || candidate.placedAtMillis() > newest.placedAtMillis()) newest = candidate
        }
        return newest
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMooshroomInteracted(event: PlayerInteractEntityEvent) {
        val mooshroom = event.rightClicked
        if (mooshroom !is MushroomCow || !mooshroom.isAdult || mooshroom.variant != MushroomCow.Variant.BROWN) return
        val player = event.player
        val used = player.inventory.getItem(event.hand).type
        if (used == Material.WITHER_ROSE) beginMooshroomPrime(player, mooshroom)
        else if (used == Material.BOWL) beginMooshroomBowl(player, mooshroom)
    }

    private fun beginMooshroomPrime(player: Player, mooshroom: MushroomCow) {
        if (mooshroom.hasEffectsForNextStew() || !tracking.test(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)) return
        val playerId = player.uniqueId
        val mooshroomId = mooshroom.uniqueId
        val preliminaryMarker = PersistentMarker(playerId, System.currentTimeMillis())
        writeMooshroomMarker(mooshroom, preliminaryMarker)
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmMooshroomPrimed(playerId, mooshroomId, preliminaryMarker, token) })
    }

    private fun confirmMooshroomPrimed(playerId: UUID, mooshroomId: UUID, preliminaryMarker: PersistentMarker, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val entity = Bukkit.getEntity(mooshroomId)
        if (player == null || entity !is MushroomCow || !isPrimedBrownMooshroom(entity) || !isCurrent(playerId, token) ||
            !tracking.test(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)) {
            if (entity is MushroomCow && preliminaryMarker == mooshroomMarker(entity)) clearMooshroomAttribution(entity)
            return
        }
        if (preliminaryMarker != mooshroomMarker(entity)) return
    }

    private fun beginMooshroomBowl(player: Player, mooshroom: MushroomCow) {
        if (!mooshroom.hasEffectForNextStew(PotionEffectType.WITHER)) return
        val marker = mooshroomMarker(mooshroom)
        if (marker == null || !markerIsCurrent(marker)) return
        val playerId = player.uniqueId
        val mooshroomId = mooshroom.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmMooshroomBowl(playerId, mooshroomId, marker, token) })
    }

    private fun confirmMooshroomBowl(playerId: UUID, mooshroomId: UUID, expectedMarker: PersistentMarker, token: AttemptToken) {
        val mooshroom = Bukkit.getEntity(mooshroomId)
        if (mooshroom !is MushroomCow || mooshroom.hasEffectsForNextStew()) return
        val currentMarker = mooshroomMarker(mooshroom)
        if (expectedMarker != currentMarker) return
        clearMooshroomAttribution(mooshroom)
        val player = Bukkit.getPlayer(playerId)
        if (player != null && expectedMarker.playerId() == playerId && markerIsCurrent(expectedMarker) &&
            isCurrent(playerId, token) && tracking.test(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)) {
            completion.accept(player, BingoTask.BROWN_MOOSHROOM_WITHER_STEW)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        val key = BlockKey.from(event.blockPlaced)
        removePowderSnow(key)
        // Powder Snow Bucket is a SolidBucketItem and uses BlockPlaceEvent on Paper 26.2.
        if (event.canBuild() && event.blockPlaced.type == Material.POWDER_SNOW && event.itemInHand.type == Material.POWDER_SNOW_BUCKET &&
            tracking.test(event.player, BingoTask.FREEZE_SKELETON_STRAY)) schedulePowderSnowConfirmation(event.player, key)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBroken(event: BlockBreakEvent) { removePowderSnow(BlockKey.from(event.block)) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockBrokenByBlock(event: BlockBreakBlockEvent) { removePowderSnow(BlockKey.from(event.block)) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPowderSnowCollected(event: PlayerBucketFillEvent) {
        removePowderSnow(BlockKey.from(event.block))
        removePowderSnow(BlockKey.from(event.blockClicked))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFaded(event: BlockFadeEvent) {
        if (event.newState.type != Material.POWDER_SNOW) removePowderSnow(BlockKey.from(event.block))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangedBlock(event: EntityChangeBlockEvent) {
        val silverfish = event.entity
        if (silverfish is Silverfish && isSilverfishInfestation(event.block.type, event.to)) completeSilverfishInfestation(silverfish)
        if (event.to != Material.POWDER_SNOW) removePowderSnow(BlockKey.from(event.block))
    }

    private fun completeSilverfishInfestation(silverfish: Silverfish) {
        val marker = silverfishMarker(silverfish)
        if (marker == null || !markerIsCurrent(marker)) return
        clearSilverfishAttribution(silverfish)
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && tracking.test(player, BingoTask.SILVERFISH_HIDE_IN_STONE)) completion.accept(player, BingoTask.SILVERFISH_HIDE_IN_STONE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) { event.blockList().forEach { removePowderSnow(BlockKey.from(it)) } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) { event.blockList().forEach { removePowderSnow(BlockKey.from(it)) } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) { removePistonBlocks(event.blocks, event.direction) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) { removePistonBlocks(event.blocks, event.direction) }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        powderSnowOwners.values.removeIf { it.playerId() == playerId }
        playerResetAtMillis[playerId] = System.currentTimeMillis()
    }

    override fun clear() {
        detectorGeneration++
        powderSnowOwners.clear()
        playerGenerations.clear()
        playerResetAtMillis.clear()
        clearedAtMillis = System.currentTimeMillis()
    }

    private fun removePistonBlocks(blocks: List<Block>, direction: BlockFace) {
        for (block in blocks) {
            val key = BlockKey.from(block)
            removePowderSnow(key)
            removePowderSnow(key.relative(direction))
        }
    }

    private fun schedulePowderSnowConfirmation(player: Player, key: BlockKey) {
        val playerId = player.uniqueId
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmPowderSnowPlaced(playerId, key, token) })
    }

    private fun removePowderSnow(key: BlockKey) { powderSnowOwners.remove(key) }

    private fun prunePowderSnowOwners(now: Long) {
        powderSnowOwners.values.removeIf { !markerIsCurrent(PersistentMarker(it.playerId(), it.placedAtMillis()), now) }
    }

    private fun mooshroomMarker(mooshroom: MushroomCow): PersistentMarker? {
        val data = mooshroom.persistentDataContainer
        val owner = data.get(mooshroomOwnerKey, PersistentDataType.STRING)
        val primedAt = data.get(mooshroomPrimedAtKey, PersistentDataType.LONG)
        if (owner == null || primedAt == null) return null
        return try { PersistentMarker(UUID.fromString(owner), primedAt) } catch (ignored: IllegalArgumentException) { null }
    }

    private fun writeMooshroomMarker(mooshroom: MushroomCow, marker: PersistentMarker) {
        val data = mooshroom.persistentDataContainer
        data.set(mooshroomOwnerKey, PersistentDataType.STRING, marker.playerId().toString())
        data.set(mooshroomPrimedAtKey, PersistentDataType.LONG, marker.timestamp())
    }

    private fun silverfishMarker(silverfish: Silverfish): PersistentMarker? {
        val data = silverfish.persistentDataContainer
        val owner = data.get(silverfishOwnerKey, PersistentDataType.STRING)
        val namedAt = data.get(silverfishNamedAtKey, PersistentDataType.LONG)
        if (owner == null || namedAt == null) return null
        return try { PersistentMarker(UUID.fromString(owner), namedAt) } catch (ignored: IllegalArgumentException) { null }
    }

    private fun clearMooshroomAttribution(mooshroom: MushroomCow) {
        val data = mooshroom.persistentDataContainer
        data.remove(mooshroomOwnerKey)
        data.remove(mooshroomPrimedAtKey)
    }

    private fun clearSilverfishAttribution(silverfish: Silverfish) {
        val data = silverfish.persistentDataContainer
        data.remove(silverfishOwnerKey)
        data.remove(silverfishNamedAtKey)
    }

    private fun markerIsCurrent(marker: PersistentMarker): Boolean = markerIsCurrent(marker, System.currentTimeMillis())
    private fun markerIsCurrent(marker: PersistentMarker, now: Long): Boolean {
        val playerResetAt = playerResetAtMillis.getOrDefault(marker.playerId(), 0L)
        return isFreshAttribution(marker.timestamp(), now, clearedAtMillis, playerResetAt, MAX_ATTRIBUTION_MILLIS)
    }

    private fun attemptToken(playerId: UUID): AttemptToken = AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))
    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration() == detectorGeneration && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

    private fun blockAt(key: BlockKey): Block? {
        val world = Bukkit.getWorld(key.worldId())
        return world?.getBlockAt(key.x(), key.y(), key.z())
    }

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val MAX_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000

        @JvmStatic
        fun isDiamondSulfurCubeBucketTarget(adult: Boolean, fuseTicks: Int, bodyItem: Material, originalBucket: Material): Boolean =
            adult && fuseTicks < 0 && bodyItem == Material.DIAMOND_BLOCK && originalBucket == Material.BUCKET

        @JvmStatic fun hasName(name: net.kyori.adventure.text.Component?): Boolean = name != null

        @JvmStatic
        fun intersectsBlock(bounds: BoundingBox, x: Int, y: Int, z: Int): Boolean =
            bounds.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))

        @JvmStatic private fun isPrimedBrownMooshroom(mooshroom: MushroomCow): Boolean =
            mooshroom.isAdult && mooshroom.variant == MushroomCow.Variant.BROWN && mooshroom.hasEffectForNextStew(PotionEffectType.WITHER)

        @JvmStatic
        fun isSilverfishInfestation(from: Material, to: Material): Boolean = when (from) {
            Material.STONE -> to == Material.INFESTED_STONE
            Material.COBBLESTONE -> to == Material.INFESTED_COBBLESTONE
            Material.STONE_BRICKS -> to == Material.INFESTED_STONE_BRICKS
            Material.MOSSY_STONE_BRICKS -> to == Material.INFESTED_MOSSY_STONE_BRICKS
            Material.CRACKED_STONE_BRICKS -> to == Material.INFESTED_CRACKED_STONE_BRICKS
            Material.CHISELED_STONE_BRICKS -> to == Material.INFESTED_CHISELED_STONE_BRICKS
            Material.DEEPSLATE -> to == Material.INFESTED_DEEPSLATE
            else -> false
        }

        @JvmStatic
        fun isFreshAttribution(timestamp: Long, now: Long, clearedAt: Long, playerResetAt: Long, maximumAge: Long): Boolean =
            timestamp > clearedAt && timestamp > playerResetAt && timestamp <= now && now - timestamp <= maximumAge

        @JvmStatic private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) map.remove(map.keys.iterator().next())
            map[key] = value
        }
    }
    private data class BlockKey(private val worldId: UUID, private val x: Int, private val y: Int, private val z: Int) {
        fun worldId(): UUID = worldId
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z
        companion object {
            @JvmStatic fun from(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
        }
        fun relative(face: BlockFace): BlockKey = BlockKey(worldId, x + face.modX, y + face.modY, z + face.modZ)
    }
    private data class OwnedPowderSnow(private val playerId: UUID, private val placedAtMillis: Long) {
        fun playerId(): UUID = playerId
        fun placedAtMillis(): Long = placedAtMillis
    }
    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }
    private data class PersistentMarker(private val playerId: UUID, private val timestamp: Long) {
        fun playerId(): UUID = playerId
        fun timestamp(): Long = timestamp
    }
}
