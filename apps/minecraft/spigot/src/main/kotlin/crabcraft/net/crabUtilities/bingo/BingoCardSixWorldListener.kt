package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.player.PlayerNameEntityEvent
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.PortalType
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Campfire
import org.bukkit.block.Conduit
import org.bukkit.block.Sign
import org.bukkit.block.data.Powerable
import org.bukkit.block.sign.Side
import org.bukkit.entity.EntityType
import org.bukkit.entity.Ghast
import org.bukkit.entity.Painting
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven world and block detectors for Bingo #6. */
class BingoCardSixWorldListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier
) : BingoDetector {
    private val ghastOwnerKey = NamespacedKey(plugin, "bingo_card6_ghast_owner")
    private val ghastNamedAtKey = NamespacedKey(plugin, "bingo_card6_ghast_named_at")
    private val ghastCardIdKey = NamespacedKey(plugin, "bingo_card6_ghast_card")
    private val ghastPlayerRunKey = NamespacedKey(plugin, "bingo_card6_ghast_run")
    private val ghastArrivedAtKey = NamespacedKey(plugin, "bingo_card6_ghast_arrived")
    private val playerRunKey = NamespacedKey(plugin, "bingo_card6_world_run")
    private val playerGenerations = HashMap<UUID, Long>()
    private val pendingGhastConfirmations = HashMap<UUID, PersistentMarker>()
    private var detectorGeneration = 0L

    init {
        Bukkit.getScheduler().runTask(plugin, Runnable { resumeLoadedGhasts(null) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingPlaced(event: HangingPlaceEvent) {
        val player = event.player ?: return
        val painting = event.entity as? Painting ?: return
        if (!isFourByFour(painting) || !tracking.test(player, BingoTask.HANG_FOUR_BY_FOUR_PAINTING)) return
        completion.accept(player, BingoTask.HANG_FOUR_BY_FOUR_PAINTING)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockInteracted(event: PlayerInteractEvent) {
        if (event.hand == null
            || event.action != Action.RIGHT_CLICK_BLOCK
            || event.useInteractedBlock() == Event.Result.DENY
            || event.useItemInHand() == Event.Result.DENY
            || event.clickedBlock == null
            || event.item == null) return
        detectHangingSignOutline(event)
        detectCampfireFilled(event)
    }

    private fun detectHangingSignOutline(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val item = event.item ?: return
        if (item.type != Material.GLOW_INK_SAC || !Tag.ALL_HANGING_SIGNS.isTagged(block.type)) return
        val sign = block.state as? Sign ?: return
        val player = event.player
        if (!tracking.test(player, BingoTask.OUTLINE_HANGING_SIGN)) return
        val side: Side? = sign.getInteractableSideFor(player)
        if (side == null || sign.getSide(side).isGlowingText || !hasVisibleText(sign, side)) return

        val playerId = player.uniqueId
        val signKey = BlockKey.from(block)
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmHangingSignOutlined(playerId, signKey, side, token) })
    }

    private fun confirmHangingSignOutlined(playerId: UUID, signKey: BlockKey, side: Side, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val block = blockAt(signKey)
        if (player == null || block == null
            || !isCurrent(playerId, token)
            || !tracking.test(player, BingoTask.OUTLINE_HANGING_SIGN)
            || !Tag.ALL_HANGING_SIGNS.isTagged(block.type)) return
        val sign = block.state as? Sign ?: return
        if (!hasVisibleText(sign, side) || !sign.getSide(side).isGlowingText) return
        completion.accept(player, BingoTask.OUTLINE_HANGING_SIGN)
    }

    private fun detectCampfireFilled(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (!Tag.CAMPFIRES.isTagged(block.type)) return
        val campfire = block.state as? Campfire ?: return
        if (occupiedCampfireSlots(campfire) != campfire.size - 1) return
        val player = event.player
        if (!tracking.test(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)) return
        val playerId = player.uniqueId
        val campfireKey = BlockKey.from(block)
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmCampfireFilled(playerId, campfireKey, token) })
    }

    private fun confirmCampfireFilled(playerId: UUID, campfireKey: BlockKey, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val block = blockAt(campfireKey)
        if (player == null || block == null
            || !isCurrent(playerId, token)
            || !tracking.test(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)) return
        val campfire = block.state as? Campfire ?: return
        if (occupiedCampfireSlots(campfire) != campfire.size) return
        completion.accept(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArrowInteractsWithButton(event: EntityInteractEvent) {
        val entity = event.entity
        val hitBlock = event.block
        if (entity !is Projectile || !isArrow(entity.type) || !Tag.WOODEN_BUTTONS.isTagged(hitBlock.type)) return
        val player = entity.shooter as? Player ?: return
        if (!tracking.test(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)) return
        val playerId = player.uniqueId
        val buttonKey = BlockKey.from(hitBlock)
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmButtonPowered(playerId, buttonKey, token) })
    }

    private fun confirmButtonPowered(playerId: UUID, buttonKey: BlockKey, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val button = blockAt(buttonKey)
        if (player == null || button == null
            || !isCurrent(playerId, token)
            || !tracking.test(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)
            || !Tag.WOODEN_BUTTONS.isTagged(button.type)) return
        val powerable = button.blockData as? Powerable ?: return
        if (!powerable.isPowered) return
        completion.accept(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        if (!event.canBuild()
            || !CONDUIT_FRAME_MATERIALS.contains(event.blockPlaced.type)
            || CONDUIT_FRAME_MATERIALS.contains(event.blockReplacedState.type)) return
        val player = event.player
        if (!tracking.test(player, BingoTask.FULLY_POWER_CONDUIT)) return
        val playerId = player.uniqueId
        val frameKey = BlockKey.from(event.blockPlaced)
        val completedConduits = findCompletedConduits(event.blockPlaced)
        if (completedConduits.isEmpty()) return
        val token = attemptToken(playerId)
        // Vanilla refreshes a Conduit's frame cache on a 40-tick cadence rather
        // than synchronously with every neighbouring block placement.
        Bukkit.getScheduler().runTaskLater(plugin,
            Runnable { confirmConduitFullyPowered(playerId, frameKey, completedConduits, token) }, 45L)
    }

    private fun confirmConduitFullyPowered(playerId: UUID, frameKey: BlockKey, completedConduits: List<BlockKey>, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val placedFrame = blockAt(frameKey)
        if (player == null || placedFrame == null
            || !isCurrent(playerId, token)
            || !tracking.test(player, BingoTask.FULLY_POWER_CONDUIT)
            || !CONDUIT_FRAME_MATERIALS.contains(placedFrame.type)) return
        for (conduitKey in completedConduits) {
            val block = blockAt(conduitKey)
            val conduit = block?.state as? Conduit
            if (conduit != null && isFullyPoweredBy(conduit, placedFrame)) {
                completion.accept(player, BingoTask.FULLY_POWER_CONDUIT)
                return
            }
        }
    }

    private fun findCompletedConduits(placedFrame: Block): List<BlockKey> {
        val completed = ArrayList<BlockKey>()
        val world = placedFrame.world
        for (x in placedFrame.x - CONDUIT_SEARCH_RADIUS..placedFrame.x + CONDUIT_SEARCH_RADIUS) {
            for (y in placedFrame.y - CONDUIT_SEARCH_RADIUS..placedFrame.y + CONDUIT_SEARCH_RADIUS) {
                for (z in placedFrame.z - CONDUIT_SEARCH_RADIUS..placedFrame.z + CONDUIT_SEARCH_RADIUS) {
                    val candidate = world.getBlockAt(x, y, z)
                    val dx = placedFrame.x - x
                    val dy = placedFrame.y - y
                    val dz = placedFrame.z - z
                    if (candidate.type == Material.CONDUIT && isConduitFrameOffset(dx, dy, dz) && hasCompleteConduitFrame(candidate)) {
                        completed.add(BlockKey.from(candidate))
                    }
                }
            }
        }
        return java.util.List.copyOf(completed)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFishBucketEmptied(event: PlayerBucketEmptyEvent) {
        val player = event.player
        if (player.world.environment == World.Environment.NETHER
            && isFishBucket(event.bucket)
            && tracking.test(player, BingoTask.PLACE_FISH_IN_NETHER)) {
            completion.accept(player, BingoTask.PLACE_FISH_IN_NETHER)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGhastNamed(event: PlayerNameEntityEvent) {
        val ghast = event.entity as? Ghast ?: return
        clearGhastMarker(ghast)
        val player = event.player
        val cardId = activeCardId.asInt
        val name = event.name
        if (name == null || PLAIN.serialize(name).isBlank()
            || cardId == Int.MIN_VALUE
            || !tracking.test(player, BingoTask.NAMED_GHAST_OVERWORLD)) return
        setGhastMarker(ghast, PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId, playerRun(player)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGhastPortal(event: EntityPortalEvent) {
        val ghast = event.entity as? Ghast ?: return
        if (event.portalType != PortalType.NETHER
            || event.from.world?.environment != World.Environment.NETHER
            || event.to?.world?.environment != World.Environment.NORMAL) return

        val marker = ghastMarker(ghast)
        if (marker == null || !timestampIsCurrent(marker.timestamp(), System.currentTimeMillis(), MAX_GHAST_ATTRIBUTION_MILLIS)) return
        val cardId = activeCardId.asInt
        if (cardId != Int.MIN_VALUE && marker.cardId() != cardId) {
            clearGhastMarker(ghast)
            return
        }
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && marker.playerRun() != playerRun(player)) {
            clearGhastMarker(ghast)
            return
        }
        ghast.persistentDataContainer.set(ghastArrivedAtKey, PersistentDataType.LONG, System.currentTimeMillis())
        scheduleGhastConfirmation(ghast, marker)
    }

    @EventHandler
    fun onEntitiesLoaded(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (entity is Ghast) resumeGhastConfirmation(entity, null)
        }
    }

    @EventHandler
    fun onPlayerJoined(event: PlayerJoinEvent) {
        resumeLoadedGhasts(event.player.uniqueId)
    }

    private fun resumeLoadedGhasts(ownerFilter: UUID?) {
        for (world in Bukkit.getWorlds()) {
            if (world.environment != World.Environment.NORMAL) continue
            for (ghast in world.getEntitiesByClass(Ghast::class.java)) resumeGhastConfirmation(ghast, ownerFilter)
        }
    }

    private fun resumeGhastConfirmation(ghast: Ghast, ownerFilter: UUID?) {
        if (ghast.world.environment != World.Environment.NORMAL || !hasArrivedInOverworldMarker(ghast)) return
        val marker = ghastMarker(ghast)
        if (marker == null || (ownerFilter != null && ownerFilter != marker.playerId())) return
        val cardId = activeCardId.asInt
        if (!timestampIsCurrent(marker.timestamp(), System.currentTimeMillis(), MAX_GHAST_ATTRIBUTION_MILLIS)
            || (cardId != Int.MIN_VALUE && cardId != marker.cardId())) {
            clearGhastMarker(ghast)
            return
        }
        val player = Bukkit.getPlayer(marker.playerId())
        if (player != null && marker.playerRun() != playerRun(player)) {
            clearGhastMarker(ghast)
            return
        }
        scheduleGhastConfirmation(ghast, marker)
    }

    private fun scheduleGhastConfirmation(ghast: Ghast, marker: PersistentMarker) {
        val ghastId = ghast.uniqueId
        val previous = pendingGhastConfirmations.put(ghastId, marker)
        if (marker == previous) return
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmGhastInOverworld(ghastId, marker, 0) })
    }

    private fun confirmGhastInOverworld(ghastId: UUID, marker: PersistentMarker, attempts: Int) {
        if (marker != pendingGhastConfirmations[ghastId]) return
        val cardId = activeCardId.asInt
        if (!timestampIsCurrent(marker.timestamp(), System.currentTimeMillis(), MAX_GHAST_ATTRIBUTION_MILLIS)
            || (cardId != Int.MIN_VALUE && cardId != marker.cardId())) {
            pendingGhastConfirmations.remove(ghastId, marker)
            val staleEntity = Bukkit.getEntity(ghastId)
            if (staleEntity is Ghast) clearGhastMarker(staleEntity)
            return
        }
        val entity = Bukkit.getEntity(ghastId)
        val player = Bukkit.getPlayer(marker.playerId())
        if (entity !is Ghast) {
            deferGhastConfirmation(ghastId, marker, attempts)
            return
        }
        val ghast = entity
        if (!ghast.isValid || ghast.isDead
            || ghast.world.environment != World.Environment.NORMAL
            || !hasArrivedInOverworldMarker(ghast)
            || marker != ghastMarker(ghast)) {
            pendingGhastConfirmations.remove(ghastId, marker)
            return
        }
        if (player != null && marker.playerRun() != playerRun(player)) {
            pendingGhastConfirmations.remove(ghastId, marker)
            clearGhastMarker(ghast)
            return
        }
        if (persistentConfirmationNeedsContext(marker.cardId(), cardId, player != null)) {
            if (shouldDeferPersistentConfirmation(marker.cardId(), cardId, player != null, true,
                    attempts, MAX_STARTUP_DEFERRAL_ATTEMPTS)) {
                deferGhastConfirmation(ghastId, marker, attempts)
            } else {
                // Leave the PDC marker intact so a later entity-load or player-join
                // scan can start a fresh bounded confirmation window.
                pendingGhastConfirmations.remove(ghastId, marker)
            }
            return
        }
        if (player == null || !markerIsCurrent(marker)) {
            pendingGhastConfirmations.remove(ghastId, marker)
            clearGhastMarker(ghast)
            return
        }
        if (!tracking.test(player, BingoTask.NAMED_GHAST_OVERWORLD)) {
            // Retain the persistent attribution, but release this in-memory
            // attempt so a later entity-load or player-join scan can retry.
            pendingGhastConfirmations.remove(ghastId, marker)
            return
        }
        pendingGhastConfirmations.remove(ghastId, marker)
        clearGhastMarker(ghast)
        completion.accept(player, BingoTask.NAMED_GHAST_OVERWORLD)
    }

    private fun deferGhastConfirmation(ghastId: UUID, marker: PersistentMarker, attempts: Int) {
        if (attempts >= MAX_STARTUP_DEFERRAL_ATTEMPTS) {
            pendingGhastConfirmations.remove(ghastId, marker)
            return
        }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { confirmGhastInOverworld(ghastId, marker, attempts + 1) }, 20L)
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        val player = Bukkit.getPlayer(playerId)
        if (player != null) {
            val data = player.persistentDataContainer
            val nextRun = data.getOrDefault(playerRunKey, PersistentDataType.LONG, 0L) + 1L
            data.set(playerRunKey, PersistentDataType.LONG, nextRun)
        }
    }

    override fun clear() {
        detectorGeneration++
        playerGenerations.clear()
    }

    private fun attemptToken(playerId: UUID): AttemptToken =
        AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean = attemptIsCurrent(
        detectorGeneration, playerGenerations.getOrDefault(playerId, 0L), token.detectorGeneration(), token.playerGeneration())

    private fun blockAt(key: BlockKey): Block? {
        val world = Bukkit.getWorld(key.worldId())
        return world?.getBlockAt(key.x(), key.y(), key.z())
    }

    private fun ghastMarker(ghast: Ghast): PersistentMarker? {
        val data = ghast.persistentDataContainer
        val owner = data.get(ghastOwnerKey, PersistentDataType.STRING)
        val namedAt = data.get(ghastNamedAtKey, PersistentDataType.LONG)
        val cardId = data.get(ghastCardIdKey, PersistentDataType.INTEGER)
        val playerRun = data.get(ghastPlayerRunKey, PersistentDataType.LONG)
        if (owner == null || namedAt == null || cardId == null || playerRun == null) return null
        return try {
            PersistentMarker(UUID.fromString(owner), namedAt, cardId, playerRun)
        } catch (ignored: IllegalArgumentException) {
            clearGhastMarker(ghast)
            null
        }
    }

    private fun setGhastMarker(ghast: Ghast, marker: PersistentMarker) {
        val data = ghast.persistentDataContainer
        data.set(ghastOwnerKey, PersistentDataType.STRING, marker.playerId().toString())
        data.set(ghastNamedAtKey, PersistentDataType.LONG, marker.timestamp())
        data.set(ghastCardIdKey, PersistentDataType.INTEGER, marker.cardId())
        data.set(ghastPlayerRunKey, PersistentDataType.LONG, marker.playerRun())
    }

    private fun clearGhastMarker(ghast: Ghast) {
        pendingGhastConfirmations.remove(ghast.uniqueId)
        val data = ghast.persistentDataContainer
        data.remove(ghastOwnerKey)
        data.remove(ghastNamedAtKey)
        data.remove(ghastCardIdKey)
        data.remove(ghastPlayerRunKey)
        data.remove(ghastArrivedAtKey)
    }

    private fun hasArrivedInOverworldMarker(ghast: Ghast): Boolean =
        ghast.persistentDataContainer.has(ghastArrivedAtKey, PersistentDataType.LONG)

    private fun markerIsCurrent(marker: PersistentMarker): Boolean {
        val now = System.currentTimeMillis()
        val player = Bukkit.getPlayer(marker.playerId()) ?: return false
        return markerIsCurrent(marker.cardId(), activeCardId.asInt, marker.timestamp(), now,
            marker.playerRun(), playerRun(player), MAX_GHAST_ATTRIBUTION_MILLIS)
    }

    private fun playerRun(player: Player): Long =
        player.persistentDataContainer.getOrDefault(playerRunKey, PersistentDataType.LONG, 0L)

    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }

    private data class PersistentMarker(private val playerId: UUID, private val timestamp: Long, private val cardId: Int, private val playerRun: Long) {
        fun playerId(): UUID = playerId
        fun timestamp(): Long = timestamp
        fun cardId(): Int = cardId
        fun playerRun(): Long = playerRun
    }

    private data class BlockKey(private val worldId: UUID, private val x: Int, private val y: Int, private val z: Int) {
        fun worldId(): UUID = worldId
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z

        companion object {
            @JvmStatic
            fun from(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
        }
    }

    companion object {
        private const val FULL_CONDUIT_FRAME_BLOCKS = 42
        private const val CONDUIT_SEARCH_RADIUS = 2
        private const val MAX_GHAST_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_STARTUP_DEFERRAL_ATTEMPTS = 300
        private val PLAIN = PlainTextComponentSerializer.plainText()
        private val CONDUIT_FRAME_MATERIALS = setOf(Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE, Material.SEA_LANTERN)
        private val FISH_BUCKETS = setOf(Material.COD_BUCKET, Material.SALMON_BUCKET, Material.PUFFERFISH_BUCKET, Material.TROPICAL_FISH_BUCKET)

        @JvmStatic
        private fun hasCompleteConduitFrame(conduit: Block): Boolean {
            val world = conduit.world
            for (dx in -2..2) {
                for (dy in -2..2) {
                    for (dz in -2..2) {
                        if (isConduitFrameOffset(dx, dy, dz)
                            && !CONDUIT_FRAME_MATERIALS.contains(world.getBlockAt(conduit.x + dx, conduit.y + dy, conduit.z + dz).type)) return false
                    }
                }
            }
            return true
        }

        @JvmStatic
        fun isFourByFour(painting: Painting): Boolean = isFourByFour(painting.art.blockWidth, painting.art.blockHeight)

        @JvmStatic
        fun isFourByFour(width: Int, height: Int): Boolean = width == 4 && height == 4

        @JvmStatic
        private fun hasVisibleText(sign: Sign, side: Side): Boolean = sign.getSide(side).lines().any { PLAIN.serialize(it).isNotBlank() }

        @JvmStatic
        fun occupiedCampfireSlots(campfire: Campfire): Int {
            var occupied = 0
            for (slot in 0 until campfire.size) {
                val item = campfire.getItem(slot)
                if (item != null && !item.isEmpty && !item.type.isAir) occupied++
            }
            return occupied
        }

        @JvmStatic
        fun isArrow(type: EntityType): Boolean = type == EntityType.ARROW || type == EntityType.SPECTRAL_ARROW

        @JvmStatic
        fun isFullyPoweredBy(conduit: Conduit, placedFrame: Block): Boolean {
            val placedFrameIncluded = conduit.frameBlocks.any { it == placedFrame }
            return isFullyPoweredConduit(conduit.isActive, conduit.frameBlockCount, placedFrameIncluded)
        }

        @JvmStatic
        fun isFullyPoweredConduit(active: Boolean, frameBlockCount: Int, placedFrameIncluded: Boolean): Boolean =
            active && frameBlockCount >= FULL_CONDUIT_FRAME_BLOCKS && placedFrameIncluded

        @JvmStatic
        fun isConduitFrameOffset(dx: Int, dy: Int, dz: Int): Boolean {
            if (Math.abs(dx) > 2 || Math.abs(dy) > 2 || Math.abs(dz) > 2) return false
            if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1) return false
            return (dx == 0 && (Math.abs(dy) == 2 || Math.abs(dz) == 2))
                || (dy == 0 && (Math.abs(dx) == 2 || Math.abs(dz) == 2))
                || (dz == 0 && (Math.abs(dx) == 2 || Math.abs(dy) == 2))
        }

        @JvmStatic
        fun isFishBucket(material: Material): Boolean = FISH_BUCKETS.contains(material)

        @JvmStatic
        fun attemptIsCurrent(detectorGeneration: Long, playerGeneration: Long,
            tokenDetectorGeneration: Long, tokenPlayerGeneration: Long): Boolean =
            detectorGeneration == tokenDetectorGeneration && playerGeneration == tokenPlayerGeneration

        @JvmStatic
        fun markerIsCurrent(markerCardId: Int, activeCardId: Int, timestamp: Long, now: Long,
            markerPlayerRun: Long, currentPlayerRun: Long, maximumAge: Long): Boolean =
            activeCardId != Int.MIN_VALUE && markerCardId == activeCardId && markerPlayerRun == currentPlayerRun
                && timestampIsCurrent(timestamp, now, maximumAge)

        @JvmStatic
        fun timestampIsCurrent(timestamp: Long, now: Long, maximumAge: Long): Boolean = timestamp <= now && now - timestamp <= maximumAge

        @JvmStatic
        fun shouldDeferPersistentConfirmation(markerCardId: Int, activeCardId: Int, ownerOnline: Boolean,
            timestampCurrent: Boolean, attempts: Int, maximumAttempts: Int): Boolean =
            persistentConfirmationNeedsContext(markerCardId, activeCardId, ownerOnline) && timestampCurrent && attempts < maximumAttempts

        @JvmStatic
        fun persistentConfirmationNeedsContext(markerCardId: Int, activeCardId: Int, ownerOnline: Boolean): Boolean =
            activeCardId == Int.MIN_VALUE || (!ownerOnline && markerCardId == activeCardId)
    }
}
