package crabcraft.net.crabUtilities.accurateplacement

import org.bukkit.Axis
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Comparator
import org.bukkit.block.data.type.Repeater
import org.bukkit.block.data.type.Stairs
import org.bukkit.configuration.file.YamlConfiguration
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object AccurateBlockPlacementRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        protocolCursorIsDecoded()
        paperSequenceBridgeIsAvailable()
        requestedDirectionsAndAxesAreDecoded()
        blockStatesAreApplied()
        stairHalfIsAppliedBeforeShape()
        packetPositionMatchesEitherPlacementBlock()
        pendingPacketsPreserveOrder()
        rejectedPacketsAreDiscarded()
        candleAirPlacementRequiresAccurateFreshContext()
        carpetPayloadsHaveTheExpectedWireShape()
        lifecycleKeepsScrubbingAndShutdownAdvertisementOrdered()
        configIsDisabledByDefault()
    }

    private fun protocolCursorIsDecoded() {
        check(AccurateBlockPlacementManager.decodeProtocolValue(1.999f) == -1,
            "a vanilla cursor was treated as an accurate-placement packet")
        check(AccurateBlockPlacementManager.decodeProtocolValue(2.0f) == 0,
            "the first encoded protocol value was decoded incorrectly")
        check(AccurateBlockPlacementManager.decodeProtocolValue(4.0f) == 1,
            "the v2 multiply-by-two encoding was not reversed")
        check(AccurateBlockPlacementManager.decodeProtocolValue(66.0f) == 32, "additional block-state bits were lost")
        check(AccurateBlockPlacementManager.decodeProtocolValue(Float.POSITIVE_INFINITY) == -1,
            "a non-finite cursor was accepted")
    }

    private fun paperSequenceBridgeIsAvailable() { AccurateBlockPlacementPaperIntegration.verify() }

    private fun requestedDirectionsAndAxesAreDecoded() {
        val faces = arrayOf(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST)
        for (index in faces.indices) {
            check(AccurateBlockPlacementManager.faceFor(index) == faces[index], "direction index $index changed")
        }
        check(AccurateBlockPlacementManager.faceFor(6) == null,
            "the facing-reversal marker was treated as an absolute face")
        check(AccurateBlockPlacementManager.axisFor(0) == Axis.X, "axis 0 is not X")
        check(AccurateBlockPlacementManager.axisFor(1) == Axis.Y, "axis 1 is not Y")
        check(AccurateBlockPlacementManager.axisFor(2) == Axis.Z, "axis 2 is not Z")
        check(AccurateBlockPlacementManager.axisFor(5) == Axis.Z, "axis values no longer wrap modulo three")
    }

    private fun packetPositionMatchesEitherPlacementBlock() {
        val now = System.nanoTime()
        val packet = AccurateBlockPlacementManager.PacketData(12, 80, -4, 3, 10, now)
        check(packet.matches(block(12, 80, -4)), "matching packet position was rejected")
        check(!packet.matches(block(13, 80, -4)), "mismatched packet position was accepted")
    }

    private fun pendingPacketsPreserveOrder() {
        val now = System.nanoTime()
        val packets = AccurateBlockPlacementManager.PendingPackets()
        packets.add(AccurateBlockPlacementManager.PacketData(1, 64, 1, 2, 10, now))
        packets.add(AccurateBlockPlacementManager.PacketData(2, 64, 2, 4, 11, now))
        packets.add(AccurateBlockPlacementManager.PacketData(3, 64, 3, 5, 12, now))
        val first = packets.takeMatching(10, block(1, 64, 1), now)
        check(first != null && first.protocolValue() == 2, "the first burst placement was overwritten")
        val third = packets.takeMatching(12, block(3, 64, 3), now)
        check(third != null && third.protocolValue() == 5, "a rejected packet blocked a later accurate placement")
        val sameTarget = AccurateBlockPlacementManager.PendingPackets()
        sameTarget.add(AccurateBlockPlacementManager.PacketData(4, 64, 4, 3, 20, now))
        sameTarget.add(AccurateBlockPlacementManager.PacketData(4, 64, 4, 5, 21, now))
        val firstSameTarget = sameTarget.takeMatching(20, block(4, 64, 4), now)
        val secondSameTarget = sameTarget.takeMatching(21, block(4, 64, 4), now)
        check(firstSameTarget != null && firstSameTarget.protocolValue() == 3,
            "the first same-target interaction was not consumed in order")
        check(secondSameTarget != null && secondSameTarget.protocolValue() == 5,
            "the second same-target interaction was not consumed in order")
    }

    private fun rejectedPacketsAreDiscarded() {
        val now = System.nanoTime()
        val packets = AccurateBlockPlacementManager.PendingPackets()
        val rejected = AccurateBlockPlacementManager.PacketData(4, 64, 4, 1, 40, now)
        val accepted = AccurateBlockPlacementManager.PacketData(4, 64, 4, 5, 41, now)
        packets.add(rejected)
        packets.add(accepted)
        val match = packets.takeMatching(41, block(4, 64, 4), now)
        check(match === accepted, "a rejected packet remained ahead of the accepted interaction")
        check(packets.takeMatching(41, block(4, 64, 4), now) == null, "an interaction sequence was consumed more than once")
        val wrongTarget = AccurateBlockPlacementManager.PendingPackets()
        wrongTarget.add(AccurateBlockPlacementManager.PacketData(4, 64, 4, 2, 50, now))
        check(wrongTarget.takeMatching(50, block(5, 64, 4), now) == null, "a packet was matched to the wrong block")
    }

    private fun candleAirPlacementRequiresAccurateFreshContext() {
        val now = System.nanoTime()
        val accurate = AccurateBlockPlacementManager.PacketData(1, 64, 1, 2, 60, now)
        val ordinary = AccurateBlockPlacementManager.PacketData(1, 64, 1, -1, 61, now)
        val expired = AccurateBlockPlacementManager.PacketData(1, 64, 1, 2, 62, now - 6_000_000_000L)
        check(AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(true, Material.CANDLE, accurate, now),
            "a fresh accurate candle placement was not allowed")
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(false, Material.CANDLE, accurate, now),
            "candle air placement ignored the disabled config")
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(true, Material.STONE, accurate, now),
            "a non-candle was treated as air-placeable")
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(true, Material.CANDLE, ordinary, now),
            "an ordinary candle placement bypassed its support check")
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(true, Material.CANDLE, expired, now),
            "an expired placement bypassed its support check")
    }

    private fun blockStatesAreApplied() {
        val facing = AtomicReference(BlockFace.NORTH)
        val directional = proxy(Directional::class.java) { _, method, args ->
            when (method.name) {
                "getFacing" -> facing.get()
                "setFacing" -> { facing.set(args!![0] as BlockFace); null }
                "getFaces" -> setOf(BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST)
                else -> defaultValue(method.returnType)
            }
        }
        AccurateBlockPlacementManager.applyDirection(directional, 5, false)
        check(facing.get() == BlockFace.EAST, "absolute facing was not applied")
        AccurateBlockPlacementManager.applyDirection(directional, 6, false)
        check(facing.get() == BlockFace.WEST, "facing reversal was not applied")
        facing.set(BlockFace.NORTH)
        AccurateBlockPlacementManager.applyDirection(directional, 7, false)
        check(facing.get() == BlockFace.NORTH, "non-stair special facing changed a block")
        AccurateBlockPlacementManager.applyDirection(directional, 7, true)
        check(facing.get() == BlockFace.SOUTH, "stair special facing was not reversed")
        facing.set(BlockFace.DOWN)
        val hopper = proxy(Directional::class.java) { _, method, args ->
            when (method.name) {
                "getFacing" -> facing.get()
                "setFacing" -> { facing.set(args!![0] as BlockFace); null }
                "getFaces" -> setOf(BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST)
                else -> defaultValue(method.returnType)
            }
        }
        AccurateBlockPlacementManager.applyDirection(hopper, 6, false)
        check(facing.get() == BlockFace.DOWN, "a directional block accepted an unsupported reversed face")
        val delay = AtomicInteger(1)
        val repeater = proxy(Repeater::class.java) { _, method, args ->
            when (method.name) {
                "getMinimumDelay" -> 1
                "getMaximumDelay" -> 4
                "setDelay" -> { delay.set(args!![0] as Int); null }
                else -> defaultValue(method.returnType)
            }
        }
        AccurateBlockPlacementManager.applyAdditionalState(repeater, 48)
        check(delay.get() == 3, "repeater delay was not decoded from the upper bits")
        val mode = AtomicReference(Comparator.Mode.COMPARE)
        val comparator = proxy(Comparator::class.java) { _, method, args ->
            when (method.name) {
                "setMode" -> { mode.set(args!![0] as Comparator.Mode); null }
                else -> defaultValue(method.returnType)
            }
        }
        AccurateBlockPlacementManager.applyAdditionalState(comparator, 16)
        check(mode.get() == Comparator.Mode.SUBTRACT, "comparator subtract mode was not applied")
        val half = AtomicReference(Bisected.Half.BOTTOM)
        val bisected = proxy(Bisected::class.java) { _, method, args ->
            when (method.name) {
                "setHalf" -> { half.set(args!![0] as Bisected.Half); null }
                else -> defaultValue(method.returnType)
            }
        }
        AccurateBlockPlacementManager.applyAdditionalState(bisected, 16)
        check(half.get() == Bisected.Half.TOP, "bisected top half was not applied")
    }

    private fun stairHalfIsAppliedBeforeShape() {
        val half = AtomicReference(Bisected.Half.BOTTOM)
        val facing = AtomicReference(BlockFace.SOUTH)
        val shape = AtomicReference(Stairs.Shape.STRAIGHT)
        val placed = proxy(Stairs::class.java) { _, method, args ->
            when (method.name) {
                "getHalf" -> half.get()
                "setHalf" -> { half.set(args!![0] as Bisected.Half); null }
                "getFacing" -> facing.get()
                "setFacing" -> { facing.set(args!![0] as BlockFace); null }
                "getFaces" -> setOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST)
                "setShape" -> { shape.set(args!![0] as Stairs.Shape); null }
                else -> defaultValue(method.returnType)
            }
        }
        val topWest = stair(Bisected.Half.TOP, BlockFace.WEST)
        val empty = block(null)
        val north = block(topWest)
        val placedBlock = proxy(Block::class.java) { _, method, args ->
            when (method.name) {
                "getRelative" -> if (args!![0] == BlockFace.NORTH) north else empty
                else -> defaultValue(method.returnType)
            }
        }
        AccurateBlockPlacementManager.applyStairState(placedBlock, placed, 18)
        check(half.get() == Bisected.Half.TOP, "the requested stair half was not applied")
        check(facing.get() == BlockFace.NORTH, "the requested stair facing was not applied")
        check(shape.get() == Stairs.Shape.OUTER_LEFT, "stair shape was calculated before the requested top half")
    }

    private fun carpetPayloadsHaveTheExpectedWireShape() {
        val helloBytes = ByteArrayInputStream(AccurateBlockPlacementManager.helloPayload())
        check(readVarInt(helloBytes) == 69, "Carpet hello protocol version changed")
        check(readString(helloBytes) == "CRABUTILITIES-ABP", "Carpet hello server id changed")
        check(helloBytes.available() == 0, "Carpet hello has trailing bytes")
        assertRulePayload(AccurateBlockPlacementManager.rulesPayload(true), "true")
        assertRulePayload(AccurateBlockPlacementManager.rulesPayload(false), "false")
    }

    private fun lifecycleKeepsScrubbingAndShutdownAdvertisementOrdered() {
        val source = Files.readString(Path.of(
            "src/main/kotlin/crabcraft/net/crabUtilities/accurateplacement/AccurateBlockPlacementManager.kt"))
        val packetRegistration = source.indexOf("packetRegistration = AccurateBlockPlacementPacketEventsIntegration.register(this)")
        val disabledGuard = source.indexOf("if (!enabled)", packetRegistration)
        check(packetRegistration >= 0 && disabledGuard > packetRegistration,
            "disabled mode no longer registers the PacketEvents scrub listener")
        val shutdown = source.indexOf("fun shutdown()")
        val disabledAdvertisement = source.indexOf("advertiseRuleToOnlinePlayers(false)", shutdown)
        val deactivate = source.indexOf("active = false", shutdown)
        val resetLifecycle = source.indexOf("started = false", shutdown)
        val protocolUnregister = source.indexOf("unregisterProtocolState()", shutdown)
        val packetUnregister = source.indexOf("unregisterPacketListener()", shutdown)
        check(shutdown >= 0 && deactivate > shutdown && disabledAdvertisement > deactivate
            && resetLifecycle > disabledAdvertisement && protocolUnregister > disabledAdvertisement
            && packetUnregister > protocolUnregister,
            "shutdown no longer disables the Carpet rule before unregistering its channels and listener")
        check(resetLifecycle < protocolUnregister, "shutdown no longer permits a clean subsequent lifecycle")
    }

    private fun assertRulePayload(payload: ByteArray, expectedValue: String) {
        val ruleBytes = ByteArrayInputStream(payload)
        check(readVarInt(ruleBytes) == 1, "Carpet rules message type changed")
        val data = DataInputStream(ruleBytes)
        check(data.readUnsignedByte() == 10, "Carpet rules root is not an NBT compound")
        val tags = LinkedHashMap<String, String>()
        while (true) {
            val type = data.readUnsignedByte()
            if (type == 0) break
            check(type == 8, "Carpet rule contains a non-string NBT tag")
            tags[data.readUTF()] = data.readUTF()
        }
        check(tags == mapOf("Value" to expectedValue, "Manager" to "carpet", "Rule" to "accurateBlockPlacement"),
            "Carpet accurateBlockPlacement rule payload changed")
        check(data.available() == 0, "Carpet rules payload has trailing bytes")
    }

    private fun configIsDisabledByDefault() {
        val config = YamlConfiguration()
        AccurateBlockPlacementRegressionTest::class.java.classLoader.getResourceAsStream("modules/integrations.yml").use { input ->
            check(input != null, "bundled modules/integrations.yml is missing")
            config.loadFromString(String(input!!.readAllBytes(), StandardCharsets.UTF_8))
        }
        check(!config.getBoolean("mod-protocols.accurate-block-placement.enabled", true),
            "accurate block placement is not disabled by default")
        check(!config.getBoolean("mod-protocols.accurate-block-placement.air-placement.candles", true),
            "unsupported candle placement is not disabled by default")
    }

    private fun readVarInt(input: ByteArrayInputStream): Int {
        var value = 0
        var position = 0
        var current: Int
        do {
            current = input.read()
            check(current >= 0 && position < 32, "invalid VarInt")
            value = value or ((current and 0x7F) shl position)
            position += 7
        } while ((current and 0x80) != 0)
        return value
    }

    private fun readString(input: ByteArrayInputStream): String {
        val length = readVarInt(input)
        val bytes = input.readNBytes(length)
        check(bytes.size == length, "truncated string")
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun block(x: Int, y: Int, z: Int): Block = proxy(Block::class.java) { proxy, method, args ->
        when (method.name) {
            "getX" -> x
            "getY" -> y
            "getZ" -> z
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            else -> defaultValue(method.returnType)
        }
    }

    private fun block(data: BlockData?): Block = proxy(Block::class.java) { _, method, _ ->
        when (method.name) {
            "getBlockData" -> data
            else -> defaultValue(method.returnType)
        }
    }

    private fun stair(half: Bisected.Half, facing: BlockFace): Stairs = proxy(Stairs::class.java) { _, method, _ ->
        when (method.name) {
            "getHalf" -> half
            "getFacing" -> facing
            else -> defaultValue(method.returnType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

    private fun defaultValue(type: Class<*>): Any? {
        if (!type.isPrimitive) return null
        if (type == Boolean::class.javaPrimitiveType) return false
        if (type == Char::class.javaPrimitiveType) return '\u0000'
        if (type == Byte::class.javaPrimitiveType) return 0.toByte()
        if (type == Short::class.javaPrimitiveType) return 0.toShort()
        if (type == Int::class.javaPrimitiveType) return 0
        if (type == Long::class.javaPrimitiveType) return 0L
        if (type == Float::class.javaPrimitiveType) return 0f
        return 0.0
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
