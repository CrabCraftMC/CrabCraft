package crabcraft.net.crabUtilities.xaero;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jadepaper.JadeMessenger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Server-side companion for Xaero's Minimap and World Map client mods.
 *
 * <p>Ported from Leaves' {@code XaeroMapProtocol}. Unlike Jade there is no
 * handshake or request/response — on join the server sends a single
 * "world id" packet to each map channel. The packet is a byte {@code 0}
 * (the packet type) followed by an int server id. Xaero clients use that id
 * to namespace their saved map tiles per world instead of per connection
 * address, which matters behind a proxy where every backend shares one IP.
 *
 * <p>Payloads are written straight to the player's connection via
 * {@link JadeMessenger#sendBytes} (the same NMS path the Jade port uses),
 * so no codec/registration machinery is required.
 */
public final class XaeroMapProtocol {

    public static final String PROTOCOL_ID_MINI = "xaerominimap";
    public static final String PROTOCOL_ID_WORLD = "xaeroworldmap";

    private static final Identifier MINIMAP_KEY = idMini("main");
    private static final Identifier WORLDMAP_KEY = idWorld("main");

    private static boolean enabled;
    private static int serverId;

    private XaeroMapProtocol() {
    }

    @Contract("_ -> new")
    public static @NotNull Identifier idMini(String path) {
        return Identifier.fromNamespaceAndPath(PROTOCOL_ID_MINI, path);
    }

    @Contract("_ -> new")
    public static @NotNull Identifier idWorld(String path) {
        return Identifier.fromNamespaceAndPath(PROTOCOL_ID_WORLD, path);
    }

    static void configure(boolean enabled, int serverId) {
        XaeroMapProtocol.enabled = enabled;
        XaeroMapProtocol.serverId = serverId;
    }

    public static boolean isActive() {
        return enabled;
    }

    public static void onSendWorldInfo(@NotNull ServerPlayer player) {
        if (!enabled) {
            return;
        }
        byte[] payload = encode(serverId);
        JadeMessenger.sendBytes(player, MINIMAP_KEY, payload);
        JadeMessenger.sendBytes(player, WORLDMAP_KEY, payload);
    }

    /**
     * Mirrors Leaves' {@code buf.writeByte(0); buf.writeInt(id)} — a type byte
     * followed by a big-endian 32-bit id (Netty {@code ByteBuf.writeInt} order).
     */
    private static byte @NotNull [] encode(int id) {
        return new byte[]{
            0,
            (byte) (id >>> 24),
            (byte) (id >>> 16),
            (byte) (id >>> 8),
            (byte) id
        };
    }
}
