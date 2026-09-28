package crabcraft.net.crabUtilities.accurateplacement

import crabcraft.net.crabUtilities.CrabUtilities
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.bukkit.Axis
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Orientable
import org.bukkit.block.data.type.Bed
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Comparator
import org.bukkit.block.data.type.Repeater
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockCanBuildEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.messaging.PluginMessageListener

/**
 * Carpet accurate block placement v2 for Litematica and Tweakeroo clients. PacketEvents captures and normalises the
 * encoded X cursor coordinate; the matching BlockPlaceEvent applies the state through Bukkit block data.
 */
class AccurateBlockPlacementManager(private val plugin: CrabUtilities) : Listener, PluginMessageListener {
    private val candlesInAir = plugin.config.getBoolean("$CONFIG_ROOT.air-placement.candles", false)
    private val pendingPlacements = ConcurrentHashMap<UUID, PendingPackets>()
    private val currentInteractions = ConcurrentHashMap<UUID, PacketData>()
    private var packetRegistration: AutoCloseable? = null
    private var started = false
    @Volatile private var active = false

    fun start(enabled: Boolean): Boolean {
        check(!started) { "Accurate block placement is already running" }
        started = true
        if (!plugin.server.pluginManager.isPluginEnabled("packetevents")) {
            if (enabled)
                plugin.logger.warning("Accurate block placement requires PacketEvents; the protocol remains disabled.")
            return false
        }
        try {
            packetRegistration = AccurateBlockPlacementPacketEventsIntegration.register(this)
        } catch (exception: LinkageError) {
            logPacketFailure(exception)
            return false
        } catch (exception: RuntimeException) {
            logPacketFailure(exception)
            return false
        }
        if (!enabled) return false
        try {
            AccurateBlockPlacementPaperIntegration.verify()
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, CARPET_CHANNEL)
            Bukkit.getPluginManager().registerEvents(this, plugin)
            plugin.server.messenger.registerIncomingPluginChannel(plugin, CARPET_CHANNEL, this)
            active = true
        } catch (exception: LinkageError) {
            logProtocolFailure(exception)
            return false
        } catch (exception: RuntimeException) {
            logProtocolFailure(exception)
            return false
        }
        Bukkit.getOnlinePlayers().forEach(::advertiseTo)
        return true
    }

    private fun logPacketFailure(exception: Throwable) {
        plugin.logger.warning("PacketEvents could not initialise accurate block placement: ${exception.message}")
    }

    private fun logProtocolFailure(exception: Throwable) {
        unregisterProtocolState()
        plugin.logger.warning("Accurate block placement could not initialise: ${exception.message}")
    }

    fun isActive() = active

    fun shutdown() {
        val wasActive = active
        active = false
        if (wasActive) advertiseRuleToOnlinePlayers(false)
        started = false
        pendingPlacements.clear()
        currentInteractions.clear()
        unregisterProtocolState()
        unregisterPacketListener()
    }

    private fun unregisterProtocolState() {
        HandlerList.unregisterAll(this)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CARPET_CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CARPET_CHANNEL)
    }

    private fun unregisterPacketListener() {
        val registration = packetRegistration ?: return
        try {
            registration.close()
        } catch (exception: Exception) {
            plugin.logger.warning("Accurate block placement listener shutdown failed: ${exception.message}")
        }
        packetRegistration = null
    }

    /** Runs on the isolated PacketEvents adapter's packet thread. */
    fun capture(player: Player, x: Int, y: Int, z: Int, cursorX: Float, sequence: Int): Boolean {
        val protocolValue = decodeProtocolValue(cursorX)
        if (!active || protocolValue < 0) return protocolValue >= 0
        val packet = PacketData(x, y, z, protocolValue, sequence, System.nanoTime())
        pendingPlacements.compute(player.uniqueId) { _, packets ->
            (packets ?: PendingPackets()).also { it.add(packet) }
        }
        return true
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        advertiseTo(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        clearPlayer(event.player)
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        clearPlayer(event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!active || event.action != Action.RIGHT_CLICK_BLOCK) return
        val clicked = event.clickedBlock ?: return
        val playerId = event.player.uniqueId
        val packet =
            pendingPlacements[playerId]?.takeMatching(
                AccurateBlockPlacementPaperIntegration.currentSequence(event.player),
                clicked,
                System.nanoTime(),
            )
        if (packet == null) currentInteractions.remove(playerId) else currentInteractions[playerId] = packet
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBlockCanBuild(event: BlockCanBuildEvent) {
        val player = event.player
        if (!active || event.isBuildable || player == null) return
        if (
            shouldAllowCandleAirPlacement(
                candlesInAir,
                event.blockData.material,
                currentInteractions[player.uniqueId],
                System.nanoTime(),
            )
        ) {
            event.isBuildable = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val placed = event.block
        val against = event.blockAgainst
        val packet = currentInteractions.remove(event.player.uniqueId) ?: return
        if (
            !packet.isAccurate() ||
                !packet.isFresh(System.nanoTime()) ||
                (!packet.matches(placed) && !packet.matches(against)) ||
                event.isCancelled ||
                !event.canBuild()
        )
            return
        applyProtocol(event, packet.protocolValue())
    }

    private fun clearPlayer(player: Player) {
        pendingPlacements.remove(player.uniqueId)
        currentInteractions.remove(player.uniqueId)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (active && CARPET_CHANNEL == channel) sendPayload(player, ENABLED_RULES_PAYLOAD)
    }

    private fun advertiseTo(player: Player) {
        if (active && player.isOnline) sendPayload(player, HELLO_PAYLOAD)
    }

    private fun advertiseRuleToOnlinePlayers(enabled: Boolean) {
        val payload = if (enabled) ENABLED_RULES_PAYLOAD else DISABLED_RULES_PAYLOAD
        Bukkit.getOnlinePlayers().forEach { if (it.isOnline) sendPayload(it, payload) }
    }

    private fun sendPayload(player: Player, payload: ByteArray) {
        try {
            player.sendPluginMessage(plugin, CARPET_CHANNEL, payload)
        } catch (exception: RuntimeException) {
            plugin.logger.fine("Could not advertise accurate block placement to ${player.name}: ${exception.message}")
        }
    }

    private fun applyProtocol(event: BlockPlaceEvent, protocolValue: Int) {
        val block = event.block
        val data = block.blockData
        if (data is Bed) return
        when (data) {
            is Stairs -> applyStairState(block, data, protocolValue)
            is Directional -> {
                applyAdditionalState(data, protocolValue)
                applyDirection(data, protocolValue, false)
                if (data is Chest) applyChestType(event, data)
            }
            is Orientable -> {
                applyAdditionalState(data, protocolValue)
                val axis = axisFor(protocolValue)
                if (data.axes.contains(axis)) data.axis = axis
            }
            else -> applyAdditionalState(data, protocolValue)
        }
        val forceAirPlacement = candlesInAir && isCandle(data.material)
        if (!forceAirPlacement && !block.canPlace(data)) {
            event.isCancelled = true
            return
        }
        block.setBlockData(data, false)
    }

    data class PacketData(
        private val x: Int,
        private val y: Int,
        private val z: Int,
        private val protocolValue: Int,
        private val sequence: Int,
        private val capturedAtNanos: Long,
    ) {
        fun x() = x

        fun y() = y

        fun z() = z

        fun protocolValue() = protocolValue

        fun sequence() = sequence

        fun capturedAtNanos() = capturedAtNanos

        fun matches(block: Block) = x == block.x && y == block.y && z == block.z

        fun isAccurate() = protocolValue >= 0

        fun isFresh(nowNanos: Long) = nowNanos - capturedAtNanos <= PENDING_PACKET_LIFETIME_NANOS
    }

    class PendingPackets {
        private val packets = ArrayDeque<PacketData>()

        @Synchronized
        fun add(packet: PacketData) {
            packets.addLast(packet)
            while (packets.size > MAX_PENDING_PACKETS) packets.removeFirst()
        }

        @Synchronized
        fun takeMatching(sequence: Int, target: Block, nowNanos: Long): PacketData? {
            pruneExpired(nowNanos)
            var match: PacketData? = null
            while (packets.isNotEmpty() && packets.first.sequence() <= sequence) {
                val candidate = packets.removeFirst()
                if (candidate.sequence() == sequence) match = candidate
            }
            return match?.takeIf { it.matches(target) }
        }

        private fun pruneExpired(nowNanos: Long) {
            while (
                packets.isNotEmpty() && nowNanos - packets.first.capturedAtNanos() > PENDING_PACKET_LIFETIME_NANOS
            ) packets.removeFirst()
        }
    }

    companion object {
        const val CONFIG_ROOT = "mod-protocols.accurate-block-placement"
        const val CARPET_CHANNEL = "carpet:hello"
        private const val CARPET_PROTOCOL_VERSION = 69
        private const val SERVER_VERSION = "CRABUTILITIES-ABP"
        private const val MAX_PENDING_PACKETS = 16
        private val PENDING_PACKET_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(5)
        private val HELLO_PAYLOAD = encodeHelloPayload()
        private val ENABLED_RULES_PAYLOAD = encodeRulesPayload(true)
        private val DISABLED_RULES_PAYLOAD = encodeRulesPayload(false)

        @JvmStatic
        fun applyDirection(directional: Directional, protocolValue: Int, stairs: Boolean) {
            val facingIndex = protocolValue and 0xF
            if (facingIndex == 6 || stairs && facingIndex > 6) {
                val reversed = directional.facing.oppositeFace
                if (directional.faces.contains(reversed)) directional.facing = reversed
                return
            }
            val requested = faceFor(facingIndex)
            if (requested != null && directional.faces.contains(requested)) directional.facing = requested
        }

        @JvmStatic
        fun applyStairState(block: Block, stairs: Stairs, protocolValue: Int) {
            applyAdditionalState(stairs, protocolValue)
            applyDirection(stairs, protocolValue, true)
            stairs.shape = stairShape(block, stairs)
        }

        @JvmStatic
        fun applyAdditionalState(data: BlockData, protocolValue: Int) {
            val additional = protocolValue and -16
            if (data is Repeater) {
                val delay = additional / 16
                if (delay in data.minimumDelay..data.maximumDelay) data.delay = delay
            } else if (additional == 16 && data is Comparator) data.mode = Comparator.Mode.SUBTRACT
            else if (additional == 16 && data is Bisected) data.half = Bisected.Half.TOP
        }

        private fun applyChestType(event: BlockPlaceEvent, chest: Chest) {
            val block = event.block
            val against = event.blockAgainst
            chest.type = Chest.Type.SINGLE
            val left = rotateClockwise(chest.facing)
            val againstData = against.blockData
            if (against != block && againstData.material == chest.material) {
                if (
                    againstData is Chest && againstData.type == Chest.Type.SINGLE && againstData.facing == chest.facing
                ) {
                    val relation = block.getFace(against)
                    if (left == relation) chest.type = Chest.Type.LEFT
                    else if (left.oppositeFace == relation) chest.type = Chest.Type.RIGHT
                }
                return
            }
            if (event.player.isSneaking) return
            val leftData = block.getRelative(left).blockData
            val rightData = block.getRelative(left.oppositeFace).blockData
            if (isSingleMatchingChest(leftData, chest)) chest.type = Chest.Type.LEFT
            else if (isSingleMatchingChest(rightData, chest)) chest.type = Chest.Type.RIGHT
        }

        private fun isSingleMatchingChest(candidate: BlockData, placed: Chest) =
            candidate is Chest &&
                candidate.material == placed.material &&
                candidate.type == Chest.Type.SINGLE &&
                candidate.facing == placed.facing

        private fun stairShape(block: Block, stairs: Stairs): Stairs.Shape {
            val half = stairs.half
            val back = stairs.facing
            val front = back.oppositeFace
            val right = rotateClockwise(back)
            val left = right.oppositeFace
            val backStairs = stairsAt(block.getRelative(back))
            val frontStairs = stairsAt(block.getRelative(front))
            val leftStairs = stairsAt(block.getRelative(left))
            val rightStairs = stairsAt(block.getRelative(right))
            if (matchesStair(backStairs, half, left) && !matchesStair(rightStairs, half, back))
                return Stairs.Shape.OUTER_LEFT
            if (matchesStair(backStairs, half, right) && !matchesStair(leftStairs, half, back))
                return Stairs.Shape.OUTER_RIGHT
            if (matchesStair(frontStairs, half, left) && !matchesStair(leftStairs, half, back))
                return Stairs.Shape.INNER_LEFT
            if (matchesStair(frontStairs, half, right) && !matchesStair(rightStairs, half, back))
                return Stairs.Shape.INNER_RIGHT
            return Stairs.Shape.STRAIGHT
        }

        private fun stairsAt(block: Block) = block.blockData as? Stairs

        private fun matchesStair(stairs: Stairs?, half: Bisected.Half, facing: BlockFace) =
            stairs != null && stairs.half == half && stairs.facing == facing

        @JvmStatic
        fun decodeProtocolValue(cursorX: Float) =
            if (!cursorX.isFinite() || cursorX < 2.0F) -1 else (cursorX.toInt() - 2) / 2

        @JvmStatic
        fun faceFor(facingIndex: Int): BlockFace? =
            when (facingIndex) {
                0 -> BlockFace.DOWN
                1 -> BlockFace.UP
                2 -> BlockFace.NORTH
                3 -> BlockFace.SOUTH
                4 -> BlockFace.WEST
                5 -> BlockFace.EAST
                else -> null
            }

        @JvmStatic
        fun axisFor(protocolValue: Int) =
            when (protocolValue % 3) {
                0 -> Axis.X
                1 -> Axis.Y
                else -> Axis.Z
            }

        private fun rotateClockwise(face: BlockFace) =
            when (face) {
                BlockFace.NORTH -> BlockFace.EAST
                BlockFace.EAST -> BlockFace.SOUTH
                BlockFace.SOUTH -> BlockFace.WEST
                BlockFace.WEST -> BlockFace.NORTH
                BlockFace.NORTH_EAST -> BlockFace.SOUTH_EAST
                BlockFace.SOUTH_EAST -> BlockFace.SOUTH_WEST
                BlockFace.SOUTH_WEST -> BlockFace.NORTH_WEST
                BlockFace.NORTH_WEST -> BlockFace.NORTH_EAST
                else -> face
            }

        private fun isCandle(material: Material) = material == Material.CANDLE || material.name.endsWith("_CANDLE")

        @JvmStatic
        fun shouldAllowCandleAirPlacement(
            configured: Boolean,
            material: Material,
            packet: PacketData?,
            nowNanos: Long,
        ) = configured && isCandle(material) && packet != null && packet.isAccurate() && packet.isFresh(nowNanos)

        @JvmStatic fun helloPayload() = HELLO_PAYLOAD.clone()

        @JvmStatic
        fun rulesPayload(enabled: Boolean) = (if (enabled) ENABLED_RULES_PAYLOAD else DISABLED_RULES_PAYLOAD).clone()

        private fun encodeHelloPayload(): ByteArray {
            val output = ByteArrayOutputStream()
            writeVarInt(output, CARPET_PROTOCOL_VERSION)
            writeString(output, SERVER_VERSION)
            return output.toByteArray()
        }

        private fun encodeRulesPayload(enabled: Boolean): ByteArray {
            try {
                val output = ByteArrayOutputStream()
                writeVarInt(output, 1)
                val data = DataOutputStream(output)
                data.writeByte(10) // Root TAG_Compound.
                writeNbtString(data, "Value", enabled.toString())
                writeNbtString(data, "Manager", "carpet")
                writeNbtString(data, "Rule", "accurateBlockPlacement")
                data.writeByte(0) // TAG_End.
                return output.toByteArray()
            } catch (impossible: IOException) {
                throw IllegalStateException("Could not encode in-memory Carpet rule payload", impossible)
            }
        }

        private fun writeNbtString(output: DataOutputStream, name: String, value: String) {
            output.writeByte(8) // TAG_String.
            output.writeUTF(name)
            output.writeUTF(value)
        }

        private fun writeString(output: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeVarInt(output, bytes.size)
            output.writeBytes(bytes)
        }

        private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
            var remaining = value
            do {
                var next = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) next = next or 0x80
                output.write(next)
            } while (remaining != 0)
        }
    }
}
