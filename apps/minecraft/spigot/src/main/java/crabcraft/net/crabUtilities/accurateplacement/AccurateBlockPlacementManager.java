package crabcraft.net.crabUtilities.accurateplacement;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCanBuildEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Implements Carpet's accurate block placement v2 protocol for Litematica and
 * Tweakeroo clients.
 *
 * <p>The client encodes the requested block state in an otherwise invalid X
 * cursor coordinate. PacketEvents captures that value and normalises the
 * coordinate before vanilla validates it; the matching {@link BlockPlaceEvent}
 * then applies the requested state through Bukkit's block-data API.
 */
public final class AccurateBlockPlacementManager implements Listener, PluginMessageListener {

    static final String CONFIG_ROOT = "mod-protocols.accurate-block-placement";
    static final String CARPET_CHANNEL = "carpet:hello";

    private static final int CARPET_PROTOCOL_VERSION = 69;
    private static final String SERVER_VERSION = "CRABUTILITIES-ABP";
    private static final int MAX_PENDING_PACKETS = 16;
    private static final long PENDING_PACKET_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final byte[] HELLO_PAYLOAD = encodeHelloPayload();
    private static final byte[] ENABLED_RULES_PAYLOAD = encodeRulesPayload(true);
    private static final byte[] DISABLED_RULES_PAYLOAD = encodeRulesPayload(false);

    private final CrabUtilities plugin;
    private final boolean candlesInAir;
    private final Map<UUID, PendingPackets> pendingPlacements = new ConcurrentHashMap<>();
    private final Map<UUID, PacketData> currentInteractions = new ConcurrentHashMap<>();
    private AutoCloseable packetRegistration;
    private boolean started;
    private volatile boolean active;

    public AccurateBlockPlacementManager(CrabUtilities plugin) {
        this.plugin = plugin;
        this.candlesInAir = plugin.getConfig().getBoolean(CONFIG_ROOT + ".air-placement.candles", false);
    }

    public boolean start(boolean enabled) {
        if (started) {
            throw new IllegalStateException("Accurate block placement is already running");
        }
        started = true;

        if (!plugin.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            if (enabled) {
                plugin.getLogger().warning(
                        "Accurate block placement requires PacketEvents; the protocol remains disabled.");
            }
            return false;
        }

        try {
            packetRegistration = AccurateBlockPlacementPacketEventsIntegration.register(this);
        } catch (LinkageError | RuntimeException exception) {
            plugin.getLogger().warning("PacketEvents could not initialise accurate block placement: "
                    + exception.getMessage());
            return false;
        }

        if (!enabled) {
            return false;
        }

        try {
            AccurateBlockPlacementPaperIntegration.verify();
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CARPET_CHANNEL);
            Bukkit.getPluginManager().registerEvents(this, plugin);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CARPET_CHANNEL, this);
            active = true;
        } catch (LinkageError | RuntimeException exception) {
            unregisterProtocolState();
            plugin.getLogger().warning("Accurate block placement could not initialise: "
                    + exception.getMessage());
            return false;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            advertiseTo(player);
        }
        return true;
    }

    public boolean isActive() {
        return active;
    }

    public void shutdown() {
        boolean wasActive = active;
        active = false;
        if (wasActive) {
            advertiseRuleToOnlinePlayers(false);
        }
        started = false;
        pendingPlacements.clear();
        currentInteractions.clear();
        unregisterProtocolState();
        unregisterPacketListener();
    }

    private void unregisterProtocolState() {
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CARPET_CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CARPET_CHANNEL);
    }

    private void unregisterPacketListener() {
        if (packetRegistration != null) {
            try {
                packetRegistration.close();
            } catch (Exception exception) {
                plugin.getLogger().warning("Accurate block placement listener shutdown failed: "
                        + exception.getMessage());
            }
            packetRegistration = null;
        }
    }

    /** Called from the isolated PacketEvents adapter on its packet thread. */
    boolean capture(Player player, int x, int y, int z, float cursorX, int sequence) {
        int protocolValue = decodeProtocolValue(cursorX);
        if (!active || protocolValue < 0) {
            return protocolValue >= 0;
        }

        UUID playerId = player.getUniqueId();
        PacketData packet = new PacketData(x, y, z, protocolValue, sequence, System.nanoTime());
        pendingPlacements.compute(playerId, (ignored, packets) -> {
            PendingPackets target = packets == null ? new PendingPackets() : packets;
            target.add(packet);
            return target;
        });
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        advertiseTo(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        clearPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!active || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        PendingPackets packets = pendingPlacements.get(playerId);
        PacketData packet = packets == null
                ? null
                : packets.takeMatching(
                        AccurateBlockPlacementPaperIntegration.currentSequence(event.getPlayer()),
                        event.getClickedBlock(),
                        System.nanoTime());
        if (packet == null) {
            currentInteractions.remove(playerId);
        } else {
            currentInteractions.put(playerId, packet);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockCanBuild(BlockCanBuildEvent event) {
        Player player = event.getPlayer();
        if (!active
                || event.isBuildable()
                || player == null) {
            return;
        }

        PacketData packet = currentInteractions.get(player.getUniqueId());
        if (shouldAllowCandleAirPlacement(
                candlesInAir,
                event.getBlockData().getMaterial(),
                packet,
                System.nanoTime())) {
            event.setBuildable(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlock();
        Block against = event.getBlockAgainst();
        PacketData packet = currentInteractions.remove(event.getPlayer().getUniqueId());
        if (packet == null
                || !packet.isAccurate()
                || !packet.isFresh(System.nanoTime())
                || (!packet.matches(placed) && !packet.matches(against))
                || event.isCancelled()
                || !event.canBuild()) {
            return;
        }

        applyProtocol(event, packet.protocolValue());
    }

    private void clearPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        pendingPlacements.remove(playerId);
        currentInteractions.remove(playerId);
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] message) {
        if (active && CARPET_CHANNEL.equals(channel)) {
            sendPayload(player, ENABLED_RULES_PAYLOAD);
        }
    }

    private void advertiseTo(Player player) {
        if (!active || !player.isOnline()) {
            return;
        }
        sendPayload(player, HELLO_PAYLOAD);
    }

    private void advertiseRuleToOnlinePlayers(boolean enabled) {
        byte[] payload = enabled ? ENABLED_RULES_PAYLOAD : DISABLED_RULES_PAYLOAD;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOnline()) {
                sendPayload(player, payload);
            }
        }
    }

    private void sendPayload(Player player, byte[] payload) {
        try {
            player.sendPluginMessage(plugin, CARPET_CHANNEL, payload);
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("Could not advertise accurate block placement to "
                    + player.getName() + ": " + exception.getMessage());
        }
    }

    private void applyProtocol(BlockPlaceEvent event, int protocolValue) {
        Block block = event.getBlock();
        BlockData data = block.getBlockData();
        if (data instanceof Bed) {
            return;
        }

        if (data instanceof Stairs stairs) {
            applyStairState(block, stairs, protocolValue);
        } else if (data instanceof Directional directional) {
            applyAdditionalState(data, protocolValue);
            applyDirection(directional, protocolValue, false);
            if (data instanceof Chest chest) {
                applyChestType(event, chest);
            }
        } else if (data instanceof Orientable orientable) {
            applyAdditionalState(data, protocolValue);
            Axis axis = axisFor(protocolValue);
            if (orientable.getAxes().contains(axis)) {
                orientable.setAxis(axis);
            }
        } else {
            applyAdditionalState(data, protocolValue);
        }

        boolean forceAirPlacement = candlesInAir && isCandle(data.getMaterial());
        if (!forceAirPlacement && !block.canPlace(data)) {
            event.setCancelled(true);
            return;
        }
        block.setBlockData(data, false);
    }

    static void applyDirection(Directional directional, int protocolValue, boolean stairs) {
        int facingIndex = protocolValue & 0xF;
        if (facingIndex == 6 || stairs && facingIndex > 6) {
            BlockFace reversed = directional.getFacing().getOppositeFace();
            if (directional.getFaces().contains(reversed)) {
                directional.setFacing(reversed);
            }
            return;
        }

        BlockFace requested = faceFor(facingIndex);
        if (requested != null && directional.getFaces().contains(requested)) {
            directional.setFacing(requested);
        }
    }

    static void applyStairState(Block block, Stairs stairs, int protocolValue) {
        applyAdditionalState(stairs, protocolValue);
        applyDirection(stairs, protocolValue, true);
        stairs.setShape(stairShape(block, stairs));
    }

    static void applyAdditionalState(BlockData data, int protocolValue) {
        int additional = protocolValue & 0xFFFFFFF0;
        if (data instanceof Repeater repeater) {
            int delay = additional / 16;
            if (delay >= repeater.getMinimumDelay() && delay <= repeater.getMaximumDelay()) {
                repeater.setDelay(delay);
            }
        } else if (additional == 16 && data instanceof Comparator comparator) {
            comparator.setMode(Comparator.Mode.SUBTRACT);
        } else if (additional == 16 && data instanceof Bisected bisected) {
            bisected.setHalf(Bisected.Half.TOP);
        }
    }

    private static void applyChestType(BlockPlaceEvent event, Chest chest) {
        Block block = event.getBlock();
        Block against = event.getBlockAgainst();
        chest.setType(Chest.Type.SINGLE);

        BlockFace left = rotateClockwise(chest.getFacing());
        BlockData againstData = against.getBlockData();
        if (!against.equals(block) && againstData.getMaterial() == chest.getMaterial()) {
            if (againstData instanceof Chest againstChest
                    && againstChest.getType() == Chest.Type.SINGLE
                    && againstChest.getFacing() == chest.getFacing()) {
                BlockFace relation = block.getFace(against);
                if (left == relation) {
                    chest.setType(Chest.Type.LEFT);
                } else if (left.getOppositeFace() == relation) {
                    chest.setType(Chest.Type.RIGHT);
                }
            }
            return;
        }

        if (event.getPlayer().isSneaking()) {
            return;
        }
        BlockData leftData = block.getRelative(left).getBlockData();
        BlockData rightData = block.getRelative(left.getOppositeFace()).getBlockData();
        if (isSingleMatchingChest(leftData, chest)) {
            chest.setType(Chest.Type.LEFT);
        } else if (isSingleMatchingChest(rightData, chest)) {
            chest.setType(Chest.Type.RIGHT);
        }
    }

    private static boolean isSingleMatchingChest(BlockData candidate, Chest placed) {
        return candidate instanceof Chest chest
                && chest.getMaterial() == placed.getMaterial()
                && chest.getType() == Chest.Type.SINGLE
                && chest.getFacing() == placed.getFacing();
    }

    private static Stairs.Shape stairShape(Block block, Stairs stairs) {
        Bisected.Half half = stairs.getHalf();
        BlockFace back = stairs.getFacing();
        BlockFace front = back.getOppositeFace();
        BlockFace right = rotateClockwise(back);
        BlockFace left = right.getOppositeFace();

        Stairs backStairs = stairsAt(block.getRelative(back));
        Stairs frontStairs = stairsAt(block.getRelative(front));
        Stairs leftStairs = stairsAt(block.getRelative(left));
        Stairs rightStairs = stairsAt(block.getRelative(right));

        if (matchesStair(backStairs, half, left) && !matchesStair(rightStairs, half, back)) {
            return Stairs.Shape.OUTER_LEFT;
        }
        if (matchesStair(backStairs, half, right) && !matchesStair(leftStairs, half, back)) {
            return Stairs.Shape.OUTER_RIGHT;
        }
        if (matchesStair(frontStairs, half, left) && !matchesStair(leftStairs, half, back)) {
            return Stairs.Shape.INNER_LEFT;
        }
        if (matchesStair(frontStairs, half, right) && !matchesStair(rightStairs, half, back)) {
            return Stairs.Shape.INNER_RIGHT;
        }
        return Stairs.Shape.STRAIGHT;
    }

    private static Stairs stairsAt(Block block) {
        return block.getBlockData() instanceof Stairs stairs ? stairs : null;
    }

    private static boolean matchesStair(Stairs stairs, Bisected.Half half, BlockFace facing) {
        return stairs != null && stairs.getHalf() == half && stairs.getFacing() == facing;
    }

    static int decodeProtocolValue(float cursorX) {
        if (!Float.isFinite(cursorX) || cursorX < 2.0F) {
            return -1;
        }
        return ((int) cursorX - 2) / 2;
    }

    static BlockFace faceFor(int facingIndex) {
        return switch (facingIndex) {
            case 0 -> BlockFace.DOWN;
            case 1 -> BlockFace.UP;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.WEST;
            case 5 -> BlockFace.EAST;
            default -> null;
        };
    }

    static Axis axisFor(int protocolValue) {
        return switch (protocolValue % 3) {
            case 0 -> Axis.X;
            case 1 -> Axis.Y;
            default -> Axis.Z;
        };
    }

    private static BlockFace rotateClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            case NORTH_EAST -> BlockFace.SOUTH_EAST;
            case SOUTH_EAST -> BlockFace.SOUTH_WEST;
            case SOUTH_WEST -> BlockFace.NORTH_WEST;
            case NORTH_WEST -> BlockFace.NORTH_EAST;
            default -> face;
        };
    }

    private static boolean isCandle(Material material) {
        return material == Material.CANDLE || material.name().endsWith("_CANDLE");
    }

    static boolean shouldAllowCandleAirPlacement(
            boolean configured,
            Material material,
            PacketData packet,
            long nowNanos) {
        return configured
                && isCandle(material)
                && packet != null
                && packet.isAccurate()
                && packet.isFresh(nowNanos);
    }

    static byte[] helloPayload() {
        return HELLO_PAYLOAD.clone();
    }

    static byte[] rulesPayload(boolean enabled) {
        return (enabled ? ENABLED_RULES_PAYLOAD : DISABLED_RULES_PAYLOAD).clone();
    }

    private static byte[] encodeHelloPayload() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, CARPET_PROTOCOL_VERSION);
        writeString(output, SERVER_VERSION);
        return output.toByteArray();
    }

    private static byte[] encodeRulesPayload(boolean enabled) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writeVarInt(output, 1);
            DataOutputStream data = new DataOutputStream(output);
            data.writeByte(10); // Root TAG_Compound.
            writeNbtString(data, "Value", Boolean.toString(enabled));
            writeNbtString(data, "Manager", "carpet");
            writeNbtString(data, "Rule", "accurateBlockPlacement");
            data.writeByte(0); // TAG_End.
            return output.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode in-memory Carpet rule payload", impossible);
        }
    }

    private static void writeNbtString(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(8); // TAG_String.
        output.writeUTF(name);
        output.writeUTF(value);
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        do {
            int next = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (value != 0);
    }

    record PacketData(int x, int y, int z, int protocolValue, int sequence, long capturedAtNanos) {
        boolean matches(Block block) {
            return x == block.getX()
                    && y == block.getY()
                    && z == block.getZ();
        }

        boolean isAccurate() {
            return protocolValue >= 0;
        }

        boolean isFresh(long nowNanos) {
            return nowNanos - capturedAtNanos <= PENDING_PACKET_LIFETIME_NANOS;
        }
    }

    static final class PendingPackets {
        private final ArrayDeque<PacketData> packets = new ArrayDeque<>();

        synchronized void add(PacketData packet) {
            packets.addLast(packet);
            while (packets.size() > MAX_PENDING_PACKETS) {
                packets.removeFirst();
            }
        }

        synchronized PacketData takeMatching(int sequence, Block target, long nowNanos) {
            pruneExpired(nowNanos);
            PacketData match = null;
            while (!packets.isEmpty() && packets.getFirst().sequence() <= sequence) {
                PacketData candidate = packets.removeFirst();
                if (candidate.sequence() == sequence) {
                    match = candidate;
                }
            }
            return match != null && match.matches(target) ? match : null;
        }

        private void pruneExpired(long nowNanos) {
            while (!packets.isEmpty()
                    && nowNanos - packets.getFirst().capturedAtNanos() > PENDING_PACKET_LIFETIME_NANOS) {
                packets.removeFirst();
            }
        }
    }
}
