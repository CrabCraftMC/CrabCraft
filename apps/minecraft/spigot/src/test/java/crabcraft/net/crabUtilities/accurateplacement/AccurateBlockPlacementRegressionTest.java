package crabcraft.net.crabUtilities.accurateplacement;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AccurateBlockPlacementRegressionTest {

    public static void main(String[] args) throws Exception {
        protocolCursorIsDecoded();
        paperSequenceBridgeIsAvailable();
        requestedDirectionsAndAxesAreDecoded();
        blockStatesAreApplied();
        stairHalfIsAppliedBeforeShape();
        packetPositionMatchesEitherPlacementBlock();
        pendingPacketsPreserveOrder();
        rejectedPacketsAreDiscarded();
        candleAirPlacementRequiresAccurateFreshContext();
        carpetPayloadsHaveTheExpectedWireShape();
        lifecycleKeepsScrubbingAndShutdownAdvertisementOrdered();
        configIsDisabledByDefault();
    }

    private static void protocolCursorIsDecoded() {
        check(AccurateBlockPlacementManager.decodeProtocolValue(1.999F) == -1,
                "a vanilla cursor was treated as an accurate-placement packet");
        check(AccurateBlockPlacementManager.decodeProtocolValue(2.0F) == 0,
                "the first encoded protocol value was decoded incorrectly");
        check(AccurateBlockPlacementManager.decodeProtocolValue(4.0F) == 1,
                "the v2 multiply-by-two encoding was not reversed");
        check(AccurateBlockPlacementManager.decodeProtocolValue(66.0F) == 32,
                "additional block-state bits were lost");
        check(AccurateBlockPlacementManager.decodeProtocolValue(Float.POSITIVE_INFINITY) == -1,
                "a non-finite cursor was accepted");
    }

    private static void paperSequenceBridgeIsAvailable() {
        AccurateBlockPlacementPaperIntegration.verify();
    }

    private static void requestedDirectionsAndAxesAreDecoded() {
        BlockFace[] faces = {
                BlockFace.DOWN,
                BlockFace.UP,
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.WEST,
                BlockFace.EAST
        };
        for (int index = 0; index < faces.length; index++) {
            check(AccurateBlockPlacementManager.faceFor(index) == faces[index],
                    "direction index " + index + " changed");
        }
        check(AccurateBlockPlacementManager.faceFor(6) == null,
                "the facing-reversal marker was treated as an absolute face");
        check(AccurateBlockPlacementManager.axisFor(0) == Axis.X, "axis 0 is not X");
        check(AccurateBlockPlacementManager.axisFor(1) == Axis.Y, "axis 1 is not Y");
        check(AccurateBlockPlacementManager.axisFor(2) == Axis.Z, "axis 2 is not Z");
        check(AccurateBlockPlacementManager.axisFor(5) == Axis.Z, "axis values no longer wrap modulo three");
    }

    private static void packetPositionMatchesEitherPlacementBlock() {
        long now = System.nanoTime();
        var packet = new AccurateBlockPlacementManager.PacketData(12, 80, -4, 3, 10, now);
        check(packet.matches(block(12, 80, -4)), "matching packet position was rejected");
        check(!packet.matches(block(13, 80, -4)), "mismatched packet position was accepted");
    }

    private static void pendingPacketsPreserveOrder() {
        long now = System.nanoTime();
        var packets = new AccurateBlockPlacementManager.PendingPackets();
        packets.add(new AccurateBlockPlacementManager.PacketData(1, 64, 1, 2, 10, now));
        packets.add(new AccurateBlockPlacementManager.PacketData(2, 64, 2, 4, 11, now));
        packets.add(new AccurateBlockPlacementManager.PacketData(3, 64, 3, 5, 12, now));

        var first = packets.takeMatching(10, block(1, 64, 1), now);
        check(first != null && first.protocolValue() == 2, "the first burst placement was overwritten");
        var third = packets.takeMatching(12, block(3, 64, 3), now);
        check(third != null && third.protocolValue() == 5,
                "a rejected packet blocked a later accurate placement");

        var sameTarget = new AccurateBlockPlacementManager.PendingPackets();
        sameTarget.add(new AccurateBlockPlacementManager.PacketData(4, 64, 4, 3, 20, now));
        sameTarget.add(new AccurateBlockPlacementManager.PacketData(4, 64, 4, 5, 21, now));
        var firstSameTarget = sameTarget.takeMatching(20, block(4, 64, 4), now);
        var secondSameTarget = sameTarget.takeMatching(21, block(4, 64, 4), now);
        check(firstSameTarget != null && firstSameTarget.protocolValue() == 3,
                "the first same-target interaction was not consumed in order");
        check(secondSameTarget != null && secondSameTarget.protocolValue() == 5,
                "the second same-target interaction was not consumed in order");
    }

    private static void rejectedPacketsAreDiscarded() {
        long now = System.nanoTime();
        var packets = new AccurateBlockPlacementManager.PendingPackets();
        var rejected = new AccurateBlockPlacementManager.PacketData(4, 64, 4, 1, 40, now);
        var accepted = new AccurateBlockPlacementManager.PacketData(4, 64, 4, 5, 41, now);
        packets.add(rejected);
        packets.add(accepted);

        var match = packets.takeMatching(41, block(4, 64, 4), now);
        check(match == accepted, "a rejected packet remained ahead of the accepted interaction");
        check(packets.takeMatching(41, block(4, 64, 4), now) == null,
                "an interaction sequence was consumed more than once");

        var wrongTarget = new AccurateBlockPlacementManager.PendingPackets();
        wrongTarget.add(new AccurateBlockPlacementManager.PacketData(4, 64, 4, 2, 50, now));
        check(wrongTarget.takeMatching(50, block(5, 64, 4), now) == null,
                "a packet was matched to the wrong block");
    }

    private static void candleAirPlacementRequiresAccurateFreshContext() {
        long now = System.nanoTime();
        var accurate = new AccurateBlockPlacementManager.PacketData(1, 64, 1, 2, 60, now);
        var ordinary = new AccurateBlockPlacementManager.PacketData(1, 64, 1, -1, 61, now);
        var expired = new AccurateBlockPlacementManager.PacketData(1, 64, 1, 2, 62, now - 6_000_000_000L);

        check(AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(
                        true, Material.CANDLE, accurate, now),
                "a fresh accurate candle placement was not allowed");
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(
                        false, Material.CANDLE, accurate, now),
                "candle air placement ignored the disabled config");
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(
                        true, Material.STONE, accurate, now),
                "a non-candle was treated as air-placeable");
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(
                        true, Material.CANDLE, ordinary, now),
                "an ordinary candle placement bypassed its support check");
        check(!AccurateBlockPlacementManager.shouldAllowCandleAirPlacement(
                        true, Material.CANDLE, expired, now),
                "an expired placement bypassed its support check");
    }

    private static void blockStatesAreApplied() {
        AtomicReference<BlockFace> facing = new AtomicReference<>(BlockFace.NORTH);
        Directional directional = proxy(Directional.class, (proxy, method, args) -> switch (method.getName()) {
            case "getFacing" -> facing.get();
            case "setFacing" -> {
                facing.set((BlockFace) args[0]);
                yield null;
            }
            case "getFaces" -> Set.of(
                    BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH,
                    BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
            default -> defaultValue(method.getReturnType());
        });

        AccurateBlockPlacementManager.applyDirection(directional, 5, false);
        check(facing.get() == BlockFace.EAST, "absolute facing was not applied");
        AccurateBlockPlacementManager.applyDirection(directional, 6, false);
        check(facing.get() == BlockFace.WEST, "facing reversal was not applied");
        facing.set(BlockFace.NORTH);
        AccurateBlockPlacementManager.applyDirection(directional, 7, false);
        check(facing.get() == BlockFace.NORTH, "non-stair special facing changed a block");
        AccurateBlockPlacementManager.applyDirection(directional, 7, true);
        check(facing.get() == BlockFace.SOUTH, "stair special facing was not reversed");

        facing.set(BlockFace.DOWN);
        Directional hopper = proxy(Directional.class, (proxy, method, args) -> switch (method.getName()) {
            case "getFacing" -> facing.get();
            case "setFacing" -> {
                facing.set((BlockFace) args[0]);
                yield null;
            }
            case "getFaces" -> Set.of(
                    BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
            default -> defaultValue(method.getReturnType());
        });
        AccurateBlockPlacementManager.applyDirection(hopper, 6, false);
        check(facing.get() == BlockFace.DOWN, "a directional block accepted an unsupported reversed face");

        AtomicInteger delay = new AtomicInteger(1);
        Repeater repeater = proxy(Repeater.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMinimumDelay" -> 1;
            case "getMaximumDelay" -> 4;
            case "setDelay" -> {
                delay.set((int) args[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        AccurateBlockPlacementManager.applyAdditionalState(repeater, 48);
        check(delay.get() == 3, "repeater delay was not decoded from the upper bits");

        AtomicReference<Comparator.Mode> mode = new AtomicReference<>(Comparator.Mode.COMPARE);
        Comparator comparator = proxy(Comparator.class, (proxy, method, args) -> switch (method.getName()) {
            case "setMode" -> {
                mode.set((Comparator.Mode) args[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        AccurateBlockPlacementManager.applyAdditionalState(comparator, 16);
        check(mode.get() == Comparator.Mode.SUBTRACT, "comparator subtract mode was not applied");

        AtomicReference<Bisected.Half> half = new AtomicReference<>(Bisected.Half.BOTTOM);
        Bisected bisected = proxy(Bisected.class, (proxy, method, args) -> switch (method.getName()) {
            case "setHalf" -> {
                half.set((Bisected.Half) args[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        AccurateBlockPlacementManager.applyAdditionalState(bisected, 16);
        check(half.get() == Bisected.Half.TOP, "bisected top half was not applied");
    }

    private static void stairHalfIsAppliedBeforeShape() {
        AtomicReference<Bisected.Half> half = new AtomicReference<>(Bisected.Half.BOTTOM);
        AtomicReference<BlockFace> facing = new AtomicReference<>(BlockFace.SOUTH);
        AtomicReference<Stairs.Shape> shape = new AtomicReference<>(Stairs.Shape.STRAIGHT);
        Stairs placed = proxy(Stairs.class, (proxy, method, args) -> switch (method.getName()) {
            case "getHalf" -> half.get();
            case "setHalf" -> {
                half.set((Bisected.Half) args[0]);
                yield null;
            }
            case "getFacing" -> facing.get();
            case "setFacing" -> {
                facing.set((BlockFace) args[0]);
                yield null;
            }
            case "getFaces" -> Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
            case "setShape" -> {
                shape.set((Stairs.Shape) args[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        Stairs topWest = stair(Bisected.Half.TOP, BlockFace.WEST);
        Block empty = block((BlockData) null);
        Block north = block(topWest);
        Block placedBlock = proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
            case "getRelative" -> args[0] == BlockFace.NORTH ? north : empty;
            default -> defaultValue(method.getReturnType());
        });

        AccurateBlockPlacementManager.applyStairState(placedBlock, placed, 18);

        check(half.get() == Bisected.Half.TOP, "the requested stair half was not applied");
        check(facing.get() == BlockFace.NORTH, "the requested stair facing was not applied");
        check(shape.get() == Stairs.Shape.OUTER_LEFT,
                "stair shape was calculated before the requested top half");
    }

    private static void carpetPayloadsHaveTheExpectedWireShape() throws Exception {
        ByteArrayInputStream helloBytes = new ByteArrayInputStream(AccurateBlockPlacementManager.helloPayload());
        check(readVarInt(helloBytes) == 69, "Carpet hello protocol version changed");
        check(readString(helloBytes).equals("CRABUTILITIES-ABP"), "Carpet hello server id changed");
        check(helloBytes.available() == 0, "Carpet hello has trailing bytes");

        assertRulePayload(AccurateBlockPlacementManager.rulesPayload(true), "true");
        assertRulePayload(AccurateBlockPlacementManager.rulesPayload(false), "false");
    }

    private static void lifecycleKeepsScrubbingAndShutdownAdvertisementOrdered() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/crabcraft/net/crabUtilities/accurateplacement/AccurateBlockPlacementManager.java"));

        int packetRegistration = source.indexOf(
                "packetRegistration = AccurateBlockPlacementPacketEventsIntegration.register(this);");
        int disabledGuard = source.indexOf("if (!enabled)", packetRegistration);
        check(packetRegistration >= 0 && disabledGuard > packetRegistration,
                "disabled mode no longer registers the PacketEvents scrub listener");

        int shutdown = source.indexOf("public void shutdown()");
        int disabledAdvertisement = source.indexOf("advertiseRuleToOnlinePlayers(false);", shutdown);
        int deactivate = source.indexOf("active = false;", shutdown);
        int resetLifecycle = source.indexOf("started = false;", shutdown);
        int protocolUnregister = source.indexOf("unregisterProtocolState();", shutdown);
        int packetUnregister = source.indexOf("unregisterPacketListener();", shutdown);
        check(shutdown >= 0
                        && deactivate > shutdown
                        && disabledAdvertisement > deactivate
                        && resetLifecycle > disabledAdvertisement
                        && protocolUnregister > disabledAdvertisement
                        && packetUnregister > protocolUnregister,
                "shutdown no longer disables the Carpet rule before unregistering its channels and listener");
        check(resetLifecycle < protocolUnregister,
                "shutdown no longer permits a clean subsequent lifecycle");
    }

    private static void assertRulePayload(byte[] payload, String expectedValue) throws Exception {
        ByteArrayInputStream ruleBytes = new ByteArrayInputStream(payload);
        check(readVarInt(ruleBytes) == 1, "Carpet rules message type changed");
        DataInputStream data = new DataInputStream(ruleBytes);
        check(data.readUnsignedByte() == 10, "Carpet rules root is not an NBT compound");

        Map<String, String> tags = new LinkedHashMap<>();
        int type;
        while ((type = data.readUnsignedByte()) != 0) {
            check(type == 8, "Carpet rule contains a non-string NBT tag");
            tags.put(data.readUTF(), data.readUTF());
        }
        check(tags.equals(Map.of(
                        "Value", expectedValue,
                        "Manager", "carpet",
                        "Rule", "accurateBlockPlacement")),
                "Carpet accurateBlockPlacement rule payload changed");
        check(data.available() == 0, "Carpet rules payload has trailing bytes");
    }

    private static void configIsDisabledByDefault() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var input = AccurateBlockPlacementRegressionTest.class.getClassLoader()
                .getResourceAsStream("modules/integrations.yml")) {
            check(input != null, "bundled modules/integrations.yml is missing");
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        check(!config.getBoolean(
                        "mod-protocols.accurate-block-placement.enabled",
                        true),
                "accurate block placement is not disabled by default");
        check(!config.getBoolean(
                        "mod-protocols.accurate-block-placement.air-placement.candles",
                        true),
                "unsupported candle placement is not disabled by default");
    }

    private static int readVarInt(ByteArrayInputStream input) {
        int value = 0;
        int position = 0;
        int current;
        do {
            current = input.read();
            check(current >= 0 && position < 32, "invalid VarInt");
            value |= (current & 0x7F) << position;
            position += 7;
        } while ((current & 0x80) != 0);
        return value;
    }

    private static String readString(ByteArrayInputStream input) throws IOException {
        int length = readVarInt(input);
        byte[] bytes = input.readNBytes(length);
        check(bytes.length == length, "truncated string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Block block(int x, int y, int z) {
        return proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Block block(BlockData data) {
        return proxy(Block.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBlockData" -> data;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Stairs stair(Bisected.Half half, BlockFace facing) {
        return proxy(Stairs.class, (proxy, method, args) -> switch (method.getName()) {
            case "getHalf" -> half;
            case "getFacing" -> facing;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
