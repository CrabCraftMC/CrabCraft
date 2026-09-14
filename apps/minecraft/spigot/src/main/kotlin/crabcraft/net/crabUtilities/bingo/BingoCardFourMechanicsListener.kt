package crabcraft.net.crabUtilities.bingo

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Deque
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.PistonMoveReaction
import org.bukkit.block.data.FaceAttachable
import org.bukkit.block.data.type.Switch
import org.bukkit.entity.Enemy
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.SculkBloomEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType

/** Event-driven detectors for the redstone and growth tasks on Bingo #4. */
class BingoCardFourMechanicsListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>
) : BingoDetector {
    private val leverAttempts = LinkedHashMap<BlockKey, MutableList<TimedAttempt>>()
    private val sculkKillAttempts: Deque<SculkKillAttempt> = ArrayDeque()
    private val playerGenerations = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var lastPruneTick = Int.MIN_VALUE

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrown(event: StructureGrowEvent) {
        val player = event.player
        if (player == null || !event.isFromBonemeal || !tracking.test(player, BingoTask.TREE_WITH_BEE_NEST)) return
        for (state in event.blocks) {
            if (isBeeNest(state.type)) {
                completion.accept(player, BingoTask.TREE_WITH_BEE_NEST)
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHoneyBottleConsumed(event: PlayerItemConsumeEvent) {
        val player = event.player
        if (!canStartHoneyCure(event.item.type, player.hasPotionEffect(PotionEffectType.POISON)) ||
            !tracking.test(player, BingoTask.CURE_POISON_HONEY_BOTTLE)) return
        val playerId = player.uniqueId
        val token = tokenFor(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmPoisonCured(playerId, token) })
    }

    private fun confirmPoisonCured(playerId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !isCurrent(playerId, token) || player.hasPotionEffect(PotionEffectType.POISON) ||
            !tracking.test(player, BingoTask.CURE_POISON_HONEY_BOTTLE)) return
        completion.accept(player, BingoTask.CURE_POISON_HONEY_BOTTLE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeverUsed(event: PlayerInteractEvent) {
        if (!event.action.isRightClick || event.hand != EquipmentSlot.HAND ||
            event.useInteractedBlock() == Event.Result.DENY) return
        val lever = event.clickedBlock ?: return
        val leverData = lever.blockData
        if (lever.type != Material.LEVER || leverData !is Switch || leverData.isPowered) return
        val player = event.player
        if (!tracking.test(player, BingoTask.PISTON_PUSH_TWELVE)) return
        val piston = lever.getRelative(leverSupportFace(leverData.attachedFace, leverData.facing))
        if (!isPiston(piston.type)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientState(tick)
        addWindowAttempt(leverAttempts, BlockKey.from(piston), TimedAttempt(player.uniqueId, tick, tokenFor(player.uniqueId)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtended(event: BlockPistonExtendEvent) {
        val tick = Bukkit.getCurrentTick()
        pruneTransientState(tick)
        val attempts = leverAttempts.remove(BlockKey.from(event.block))
        val pushedBlockCount = countPushedBlocks(event.blocks)
        if (!isPistonPushCorrelation(attempts, tick, pushedBlockCount)) return
        val owner = singleCurrentOwner(attempts, tick, PISTON_WINDOW_TICKS)
        val player = owner?.let { Bukkit.getPlayer(it) }
        if (player != null && tracking.test(player, BingoTask.PISTON_PUSH_TWELVE)) {
            completion.accept(player, BingoTask.PISTON_PUSH_TWELVE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnemyKilled(event: EntityDeathEvent) {
        val player = event.damageSource.causingEntity
        if (event.entity !is Enemy || player !is Player || !tracking.test(player, BingoTask.SCULK_CATALYST_PLAYER_KILL)) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientState(tick)
        if (sculkKillAttempts.size >= MAX_TRANSIENT_KEYS) sculkKillAttempts.removeFirst()
        sculkKillAttempts.addLast(SculkKillAttempt(
            player.uniqueId, event.entity.location.clone().add(0.0, 0.5, 0.0), tick, tokenFor(player.uniqueId)))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSculkBloom(event: SculkBloomEvent) {
        if (event.charge <= 0) return
        val tick = Bukkit.getCurrentTick()
        pruneTransientState(tick)
        val bloom = event.block.location.toCenterLocation()
        val matches = ArrayList<SculkKillAttempt>()
        for (attempt in sculkKillAttempts) {
            if (isCurrent(attempt.playerId(), attempt.token()) &&
                isWithinWindow(attempt.tick(), tick, SCULK_WINDOW_TICKS) &&
                isNearby(attempt.location(), bloom, SCULK_BLOOM_DISTANCE_SQUARED)) matches.add(attempt)
        }
        if (matches.isEmpty()) return
        sculkKillAttempts.removeAll(matches)
        val owner = singleDistinctOwner(matches.map { it.playerId() })
        val player = owner?.let { Bukkit.getPlayer(it) }
        if (player != null && tracking.test(player, BingoTask.SCULK_CATALYST_PLAYER_KILL)) {
            completion.accept(player, BingoTask.SCULK_CATALYST_PLAYER_KILL)
        }
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        removePlayerAttempts(leverAttempts, playerId)
        sculkKillAttempts.removeIf { it.playerId() == playerId }
    }

    override fun clear() {
        detectorGeneration++
        leverAttempts.clear()
        sculkKillAttempts.clear()
        playerGenerations.clear()
        lastPruneTick = Int.MIN_VALUE
    }

    private fun pruneTransientState(tick: Int) {
        if (lastPruneTick == tick) return
        lastPruneTick = tick
        pruneAttemptMap(leverAttempts, tick)
        sculkKillAttempts.removeIf {
            !isCurrent(it.playerId(), it.token()) || !isWithinWindow(it.tick(), tick, SCULK_WINDOW_TICKS)
        }
    }

    private fun addWindowAttempt(
        attemptsByBlock: MutableMap<BlockKey, MutableList<TimedAttempt>>, block: BlockKey, attempt: TimedAttempt
    ) {
        if (!attemptsByBlock.containsKey(block) && attemptsByBlock.size >= MAX_TRANSIENT_KEYS) {
            val oldest = attemptsByBlock.keys.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        val attempts = attemptsByBlock.computeIfAbsent(block) { ArrayList() }
        attempts.removeIf { it.playerId() == attempt.playerId() }
        if (attempts.size < MAX_OWNERS_PER_WINDOW) attempts.add(attempt)
    }

    private fun singleCurrentOwner(attempts: List<TimedAttempt>?, tick: Int, maximumTicks: Int): UUID? {
        if (attempts == null) return null
        return singleDistinctOwner(attempts.filter { isCurrent(it.playerId(), it.token()) }
            .filter { isWithinWindow(it.tick(), tick, maximumTicks) }.map { it.playerId() })
    }

    private fun tokenFor(playerId: UUID): AttemptToken =
        AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration() == detectorGeneration && token.playerGeneration() == playerGenerations.getOrDefault(playerId, 0L)

    companion object {
        private const val MAX_TRANSIENT_KEYS = 4_096
        private const val MAX_OWNERS_PER_WINDOW = 8
        private const val PISTON_WINDOW_TICKS = 1
        private const val SCULK_WINDOW_TICKS = 1
        private const val SCULK_BLOOM_DISTANCE_SQUARED = 4.0

        @JvmStatic private fun pruneAttemptMap(attemptsByBlock: MutableMap<BlockKey, MutableList<TimedAttempt>>, tick: Int) {
            pruneAttemptMap(attemptsByBlock, tick, PISTON_WINDOW_TICKS)
        }

        @JvmStatic private fun pruneAttemptMap(
            attemptsByBlock: MutableMap<BlockKey, MutableList<TimedAttempt>>, tick: Int, maximumTicks: Int
        ) {
            attemptsByBlock.values.forEach { attempts -> attempts.removeIf { !isWithinWindow(it.tick(), tick, maximumTicks) } }
            attemptsByBlock.values.removeIf { it.isEmpty() }
        }

        @JvmStatic private fun removePlayerAttempts(attemptsByBlock: MutableMap<BlockKey, MutableList<TimedAttempt>>, playerId: UUID) {
            attemptsByBlock.values.forEach { attempts -> attempts.removeIf { it.playerId() == playerId } }
            attemptsByBlock.values.removeIf { it.isEmpty() }
        }

        @JvmStatic fun isBeeNest(material: Material): Boolean = material == Material.BEE_NEST
        @JvmStatic fun canStartHoneyCure(consumed: Material, poisoned: Boolean): Boolean = consumed == Material.HONEY_BOTTLE && poisoned
        @JvmStatic fun isPiston(material: Material): Boolean = material == Material.PISTON || material == Material.STICKY_PISTON

        @JvmStatic
        fun leverSupportFace(attachedFace: FaceAttachable.AttachedFace, facing: BlockFace): BlockFace = when (attachedFace) {
            FaceAttachable.AttachedFace.FLOOR -> BlockFace.DOWN
            FaceAttachable.AttachedFace.CEILING -> BlockFace.UP
            FaceAttachable.AttachedFace.WALL -> facing.oppositeFace
        }

        @JvmStatic private fun isPistonPushCorrelation(attempts: List<TimedAttempt>?, eventTick: Int, pushedBlockCount: Int): Boolean =
            attempts != null && attempts.any { isPistonPushCorrelation(it.tick(), eventTick, pushedBlockCount) }

        @JvmStatic
        fun isPistonPushCorrelation(armedTick: Int, eventTick: Int, pushedBlockCount: Int): Boolean =
            pushedBlockCount == 12 && isWithinWindow(armedTick, eventTick, PISTON_WINDOW_TICKS)

        @JvmStatic fun countPushedBlocks(eventBlocks: List<Block>): Int = countPushedReactions(eventBlocks.map { it.pistonMoveReaction })
        @JvmStatic fun countPushedReactions(reactions: Collection<PistonMoveReaction>): Int = reactions.count { it != PistonMoveReaction.BREAK }

        @JvmStatic
        fun isWithinWindow(armedTick: Int, eventTick: Int, maximumTicks: Int): Boolean {
            val elapsed = eventTick - armedTick
            return elapsed >= 0 && elapsed <= maximumTicks
        }

        @JvmStatic
        fun isNearby(first: Location, second: Location, maximumDistanceSquared: Double): Boolean =
            first.world != null && second.world != null && isNearby(
                first.world!!.uid, first.x, first.y, first.z, second.world!!.uid, second.x, second.y, second.z, maximumDistanceSquared)

        @JvmStatic
        fun isNearby(
            firstWorld: UUID, firstX: Double, firstY: Double, firstZ: Double,
            secondWorld: UUID, secondX: Double, secondY: Double, secondZ: Double, maximumDistanceSquared: Double
        ): Boolean {
            if (firstWorld != secondWorld) return false
            val x = firstX - secondX
            val y = firstY - secondY
            val z = firstZ - secondZ
            return x * x + y * y + z * z <= maximumDistanceSquared
        }

        @JvmStatic
        fun singleDistinctOwner(owners: Collection<UUID>): UUID? {
            var owner: UUID? = null
            for (candidate in owners) {
                if (owner == null) owner = candidate
                else if (owner != candidate) return null
            }
            return owner
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
    }
    private data class AttemptToken(private val detectorGeneration: Long, private val playerGeneration: Long) {
        fun detectorGeneration(): Long = detectorGeneration
        fun playerGeneration(): Long = playerGeneration
    }
    private data class TimedAttempt(private val playerId: UUID, private val tick: Int, private val token: AttemptToken) {
        fun playerId(): UUID = playerId
        fun tick(): Int = tick
        fun token(): AttemptToken = token
    }
    private data class SculkKillAttempt(private val playerId: UUID, private val location: Location, private val tick: Int, private val token: AttemptToken) {
        fun playerId(): UUID = playerId
        fun location(): Location = location
        fun tick(): Int = tick
        fun token(): AttemptToken = token
    }
}
