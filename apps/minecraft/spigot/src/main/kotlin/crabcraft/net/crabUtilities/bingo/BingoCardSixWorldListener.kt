package crabcraft.net.crabUtilities.bingo

import crabcraft.net.crabUtilities.bingo.BingoTracking.BlockKey
import crabcraft.net.crabUtilities.bingo.BingoTracking.blockAt
import io.papermc.paper.event.player.PlayerNameEntityEvent
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import java.util.function.IntSupplier
import kotlin.math.abs
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
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven world and block detectors for Bingo #6. */
class BingoCardSixWorldListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
    private val activeCardId: IntSupplier,
) : AbstractBingoDetector() {
    private val ghastOwnerKey = NamespacedKey(plugin, "bingo_card6_ghast_owner")
    private val ghastNamedAtKey = NamespacedKey(plugin, "bingo_card6_ghast_named_at")
    private val ghastCardIdKey = NamespacedKey(plugin, "bingo_card6_ghast_card")
    private val ghastPlayerRunKey = NamespacedKey(plugin, "bingo_card6_ghast_run")
    private val ghastArrivedAtKey = NamespacedKey(plugin, "bingo_card6_ghast_arrived")
    private val playerRunKey = NamespacedKey(plugin, "bingo_card6_world_run")
    private val pendingGhastConfirmations = HashMap<UUID, PersistentMarker>()

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
        if (
            event.hand == null ||
                event.action != Action.RIGHT_CLICK_BLOCK ||
                event.useInteractedBlock() == Event.Result.DENY ||
                event.useItemInHand() == Event.Result.DENY ||
                event.clickedBlock == null ||
                event.item == null
        )
            return
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
        val side = sign.getInteractableSideFor(player) ?: return
        if (sign.getSide(side).isGlowingText || !hasVisibleText(sign, side)) return
        val playerId = player.uniqueId
        val signKey = BlockKey.from(block)
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmHangingSignOutlined(playerId, signKey, side, token) })
    }

    private fun confirmHangingSignOutlined(playerId: UUID, signKey: BlockKey, side: Side, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val block = blockAt(signKey) ?: return
        if (
            !isCurrent(playerId, token) ||
                !tracking.test(player, BingoTask.OUTLINE_HANGING_SIGN) ||
                !Tag.ALL_HANGING_SIGNS.isTagged(block.type)
        )
            return
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
        val player = Bukkit.getPlayer(playerId) ?: return
        val block = blockAt(campfireKey) ?: return
        if (!isCurrent(playerId, token) || !tracking.test(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)) return
        val campfire = block.state as? Campfire ?: return
        if (occupiedCampfireSlots(campfire) != campfire.size) return
        completion.accept(player, BingoTask.FILL_CAMPFIRE_FOUR_SLOTS)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArrowInteractsWithButton(event: EntityInteractEvent) {
        val projectile = event.entity as? Projectile ?: return
        val hitBlock = event.block
        if (!isArrow(projectile.type) || !Tag.WOODEN_BUTTONS.isTagged(hitBlock.type)) return
        val player = projectile.shooter as? Player ?: return
        if (!tracking.test(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)) return
        val playerId = player.uniqueId
        val buttonKey = BlockKey.from(hitBlock)
        val token = attemptToken(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmButtonPowered(playerId, buttonKey, token) })
    }

    private fun confirmButtonPowered(playerId: UUID, buttonKey: BlockKey, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val button = blockAt(buttonKey) ?: return
        if (
            !isCurrent(playerId, token) ||
                !tracking.test(player, BingoTask.SHOOT_BUTTON_WITH_ARROW) ||
                !Tag.WOODEN_BUTTONS.isTagged(button.type)
        )
            return
        val powerable = button.blockData as? Powerable ?: return
        if (!powerable.isPowered) return
        completion.accept(player, BingoTask.SHOOT_BUTTON_WITH_ARROW)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlaced(event: BlockPlaceEvent) {
        if (
            !event.canBuild() ||
                event.blockPlaced.type !in CONDUIT_FRAME_MATERIALS ||
                event.blockReplacedState.type in CONDUIT_FRAME_MATERIALS
        )
            return
        val player = event.player
        if (!tracking.test(player, BingoTask.FULLY_POWER_CONDUIT)) return
        val playerId = player.uniqueId
        val frameKey = BlockKey.from(event.blockPlaced)
        val completedConduits = findCompletedConduits(event.blockPlaced)
        if (completedConduits.isEmpty()) return
        val token = attemptToken(playerId)
        // Vanilla refreshes a Conduit's frame cache on a 40-tick cadence rather
        // than synchronously with every neighbouring block placement.
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                Runnable {
                    confirmConduitFullyPowered(playerId, frameKey, completedConduits, token)
                },
                45L,
            )
    }

    private fun confirmConduitFullyPowered(
        playerId: UUID,
        frameKey: BlockKey,
        completedConduits: List<BlockKey>,
        token: AttemptToken,
    ) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val placedFrame = blockAt(frameKey) ?: return
        if (
            !isCurrent(playerId, token) ||
                !tracking.test(player, BingoTask.FULLY_POWER_CONDUIT) ||
                placedFrame.type !in CONDUIT_FRAME_MATERIALS
        )
            return
        for (conduitKey in completedConduits) {
            val conduit = blockAt(conduitKey)?.state as? Conduit ?: continue
            if (isFullyPoweredBy(conduit, placedFrame)) {
                completion.accept(player, BingoTask.FULLY_POWER_CONDUIT)
                return
            }
        }
    }

    private fun findCompletedConduits(placedFrame: Block): List<BlockKey> {
        val completed = ArrayList<BlockKey>()
        for (offset in CONDUIT_FRAME_OFFSETS) {
            val candidate = placedFrame.getRelative(offset.x, offset.y, offset.z)
            if (candidate.type == Material.CONDUIT && hasCompleteConduitFrame(candidate))
                completed.add(BlockKey.from(candidate))
        }
        return completed.toList()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFishBucketEmptied(event: PlayerBucketEmptyEvent) {
        val player = event.player
        if (
            player.world.environment == World.Environment.NETHER &&
                isFishBucket(event.bucket) &&
                tracking.test(player, BingoTask.PLACE_FISH_IN_NETHER)
        )
            completion.accept(player, BingoTask.PLACE_FISH_IN_NETHER)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGhastNamed(event: PlayerNameEntityEvent) {
        val ghast = event.entity as? Ghast ?: return
        clearGhastMarker(ghast)
        val player = event.player
        val cardId = activeCardId.asInt
        val name = event.name ?: return
        if (
            PLAIN.serialize(name).isBlank() ||
                cardId == Int.MIN_VALUE ||
                !tracking.test(player, BingoTask.NAMED_GHAST_OVERWORLD)
        )
            return
        setGhastMarker(ghast, PersistentMarker(player.uniqueId, System.currentTimeMillis(), cardId, playerRun(player)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGhastPortal(event: EntityPortalEvent) {
        val ghast = event.entity as? Ghast ?: return
        if (
            event.portalType != PortalType.NETHER ||
                event.from.world?.environment != World.Environment.NETHER ||
                event.to?.world?.environment != World.Environment.NORMAL
        )
            return
        val marker = ghastMarker(ghast) ?: return
        if (!timestampIsCurrent(marker.timestamp, System.currentTimeMillis(), MAX_GHAST_ATTRIBUTION_MILLIS)) return
        val cardId = activeCardId.asInt
        if (cardId != Int.MIN_VALUE && marker.cardId != cardId) {
            clearGhastMarker(ghast)
            return
        }
        val player = Bukkit.getPlayer(marker.playerId)
        if (player != null && marker.playerRun != playerRun(player)) {
            clearGhastMarker(ghast)
            return
        }
        ghast.persistentDataContainer.set(ghastArrivedAtKey, PersistentDataType.LONG, System.currentTimeMillis())
        scheduleGhastConfirmation(ghast, marker)
    }

    @EventHandler
    fun onEntitiesLoaded(event: EntitiesLoadEvent) {
        for (entity in event.entities) if (entity is Ghast) resumeGhastConfirmation(entity, null)
    }

    @EventHandler fun onPlayerJoined(event: PlayerJoinEvent) = resumeLoadedGhasts(event.player.uniqueId)

    private fun resumeLoadedGhasts(ownerFilter: UUID?) {
        for (world in Bukkit.getWorlds()) {
            if (world.environment != World.Environment.NORMAL) continue
            for (ghast in world.getEntitiesByClass(Ghast::class.java)) resumeGhastConfirmation(ghast, ownerFilter)
        }
    }

    private fun resumeGhastConfirmation(ghast: Ghast, ownerFilter: UUID?) {
        if (ghast.world.environment != World.Environment.NORMAL || !hasArrivedInOverworldMarker(ghast)) return
        val marker = ghastMarker(ghast) ?: return
        if (ownerFilter != null && ownerFilter != marker.playerId) return
        val cardId = activeCardId.asInt
        if (
            !timestampIsCurrent(marker.timestamp, System.currentTimeMillis(), MAX_GHAST_ATTRIBUTION_MILLIS) ||
                cardId != Int.MIN_VALUE && cardId != marker.cardId
        ) {
            clearGhastMarker(ghast)
            return
        }
        val player = Bukkit.getPlayer(marker.playerId)
        if (player != null && marker.playerRun != playerRun(player)) {
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
        if (
            !timestampIsCurrent(marker.timestamp, System.currentTimeMillis(), MAX_GHAST_ATTRIBUTION_MILLIS) ||
                cardId != Int.MIN_VALUE && cardId != marker.cardId
        ) {
            pendingGhastConfirmations.remove(ghastId, marker)
            (Bukkit.getEntity(ghastId) as? Ghast)?.let { clearGhastMarker(it) }
            return
        }
        val ghast = Bukkit.getEntity(ghastId) as? Ghast
        val player = Bukkit.getPlayer(marker.playerId)
        if (ghast == null) {
            deferGhastConfirmation(ghastId, marker, attempts)
            return
        }
        if (
            !ghast.isValid ||
                ghast.isDead ||
                ghast.world.environment != World.Environment.NORMAL ||
                !hasArrivedInOverworldMarker(ghast) ||
                marker != ghastMarker(ghast)
        ) {
            pendingGhastConfirmations.remove(ghastId, marker)
            return
        }
        if (player != null && marker.playerRun != playerRun(player)) {
            pendingGhastConfirmations.remove(ghastId, marker)
            clearGhastMarker(ghast)
            return
        }
        if (persistentConfirmationNeedsContext(marker.cardId, cardId, player != null)) {
            if (
                shouldDeferPersistentConfirmation(
                    marker.cardId,
                    cardId,
                    player != null,
                    true,
                    attempts,
                    MAX_STARTUP_DEFERRAL_ATTEMPTS,
                )
            ) {
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
            // Retain attribution, but release the attempt for a later load or join scan.
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
        Bukkit.getScheduler()
            .runTaskLater(plugin, Runnable { confirmGhastInOverworld(ghastId, marker, attempts + 1) }, 20L)
    }

    override fun resetPlayer(playerId: UUID) {
        super.resetPlayer(playerId)
        Bukkit.getPlayer(playerId)?.let { player ->
            val data = player.persistentDataContainer
            data.set(
                playerRunKey,
                PersistentDataType.LONG,
                data.getOrDefault(playerRunKey, PersistentDataType.LONG, 0L) + 1L,
            )
        }
    }

    private fun ghastMarker(ghast: Ghast): PersistentMarker? {
        val data = ghast.persistentDataContainer
        val owner = data.get(ghastOwnerKey, PersistentDataType.STRING) ?: return null
        val namedAt = data.get(ghastNamedAtKey, PersistentDataType.LONG) ?: return null
        val cardId = data.get(ghastCardIdKey, PersistentDataType.INTEGER) ?: return null
        val run = data.get(ghastPlayerRunKey, PersistentDataType.LONG) ?: return null
        return try {
            PersistentMarker(UUID.fromString(owner), namedAt, cardId, run)
        } catch (_: IllegalArgumentException) {
            clearGhastMarker(ghast)
            null
        }
    }

    private fun setGhastMarker(ghast: Ghast, marker: PersistentMarker) {
        val data = ghast.persistentDataContainer
        data.set(ghastOwnerKey, PersistentDataType.STRING, marker.playerId.toString())
        data.set(ghastNamedAtKey, PersistentDataType.LONG, marker.timestamp)
        data.set(ghastCardIdKey, PersistentDataType.INTEGER, marker.cardId)
        data.set(ghastPlayerRunKey, PersistentDataType.LONG, marker.playerRun)
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
        val player = Bukkit.getPlayer(marker.playerId) ?: return false
        return markerIsCurrent(
            marker.cardId,
            activeCardId.asInt,
            marker.timestamp,
            now,
            marker.playerRun,
            playerRun(player),
            MAX_GHAST_ATTRIBUTION_MILLIS,
        )
    }

    private fun playerRun(player: Player): Long =
        player.persistentDataContainer.getOrDefault(playerRunKey, PersistentDataType.LONG, 0L)

    private data class PersistentMarker(val playerId: UUID, val timestamp: Long, val cardId: Int, val playerRun: Long)

    private data class FrameOffset(val x: Int, val y: Int, val z: Int)

    companion object {
        private const val FULL_CONDUIT_FRAME_BLOCKS = 42
        private const val CONDUIT_SEARCH_RADIUS = 2
        private val CONDUIT_FRAME_OFFSETS = conduitFrameOffsets()
        private const val MAX_GHAST_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_STARTUP_DEFERRAL_ATTEMPTS = 300
        private val PLAIN = PlainTextComponentSerializer.plainText()
        private val CONDUIT_FRAME_MATERIALS =
            setOf(Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE, Material.SEA_LANTERN)
        private val FISH_BUCKETS =
            setOf(
                Material.COD_BUCKET,
                Material.SALMON_BUCKET,
                Material.PUFFERFISH_BUCKET,
                Material.TROPICAL_FISH_BUCKET,
            )

        private fun hasCompleteConduitFrame(conduit: Block): Boolean = CONDUIT_FRAME_OFFSETS.all {
            conduit.getRelative(it.x, it.y, it.z).type in CONDUIT_FRAME_MATERIALS
        }

        private fun conduitFrameOffsets(): List<FrameOffset> {
            val offsets = ArrayList<FrameOffset>(FULL_CONDUIT_FRAME_BLOCKS)
            for (x in -CONDUIT_SEARCH_RADIUS..CONDUIT_SEARCH_RADIUS) {
                for (y in -CONDUIT_SEARCH_RADIUS..CONDUIT_SEARCH_RADIUS) {
                    for (z in -CONDUIT_SEARCH_RADIUS..CONDUIT_SEARCH_RADIUS) {
                        if (isConduitFrameOffset(x, y, z)) offsets.add(FrameOffset(x, y, z))
                    }
                }
            }
            return offsets.toList()
        }

        @JvmStatic
        fun isFourByFour(painting: Painting): Boolean = isFourByFour(painting.art.blockWidth, painting.art.blockHeight)

        @JvmStatic fun isFourByFour(width: Int, height: Int): Boolean = width == 4 && height == 4

        private fun hasVisibleText(sign: Sign, side: Side): Boolean =
            sign.getSide(side).lines().any { PLAIN.serialize(it).isNotBlank() }

        @JvmStatic
        fun occupiedCampfireSlots(campfire: Campfire): Int =
            (0 until campfire.size).count {
                val item = campfire.getItem(it)
                item != null && !item.isEmpty && !item.type.isAir
            }

        @JvmStatic
        fun isArrow(type: EntityType): Boolean = type == EntityType.ARROW || type == EntityType.SPECTRAL_ARROW

        @JvmStatic
        fun isFullyPoweredBy(conduit: Conduit, placedFrame: Block): Boolean =
            isFullyPoweredConduit(
                conduit.isActive,
                conduit.frameBlockCount,
                conduit.frameBlocks.any { it == placedFrame },
            )

        @JvmStatic
        fun isFullyPoweredConduit(active: Boolean, frameBlockCount: Int, placedFrameIncluded: Boolean): Boolean =
            active && frameBlockCount >= FULL_CONDUIT_FRAME_BLOCKS && placedFrameIncluded

        @JvmStatic
        fun isConduitFrameOffset(dx: Int, dy: Int, dz: Int): Boolean {
            if (abs(dx) > 2 || abs(dy) > 2 || abs(dz) > 2) return false
            if (abs(dx) <= 1 && abs(dy) <= 1 && abs(dz) <= 1) return false
            return dx == 0 && (abs(dy) == 2 || abs(dz) == 2) ||
                dy == 0 && (abs(dx) == 2 || abs(dz) == 2) ||
                dz == 0 && (abs(dx) == 2 || abs(dy) == 2)
        }

        @JvmStatic fun isFishBucket(material: Material): Boolean = material in FISH_BUCKETS

        @JvmStatic
        fun markerIsCurrent(
            markerCardId: Int,
            activeCardId: Int,
            timestamp: Long,
            now: Long,
            markerPlayerRun: Long,
            currentPlayerRun: Long,
            maximumAge: Long,
        ): Boolean =
            activeCardId != Int.MIN_VALUE &&
                markerCardId == activeCardId &&
                markerPlayerRun == currentPlayerRun &&
                timestampIsCurrent(timestamp, now, maximumAge)

        @JvmStatic
        fun timestampIsCurrent(timestamp: Long, now: Long, maximumAge: Long): Boolean =
            timestamp <= now && now - timestamp <= maximumAge

        @JvmStatic
        fun shouldDeferPersistentConfirmation(
            markerCardId: Int,
            activeCardId: Int,
            ownerOnline: Boolean,
            timestampCurrent: Boolean,
            attempts: Int,
            maximumAttempts: Int,
        ): Boolean =
            persistentConfirmationNeedsContext(markerCardId, activeCardId, ownerOnline) &&
                timestampCurrent &&
                attempts < maximumAttempts

        @JvmStatic
        fun persistentConfirmationNeedsContext(markerCardId: Int, activeCardId: Int, ownerOnline: Boolean): Boolean =
            activeCardId == Int.MIN_VALUE || !ownerOnline && markerCardId == activeCardId
    }
}
