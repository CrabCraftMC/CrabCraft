package org.jadepaper;

import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Send/receive helpers for Jade plugin-channel payloads.
 *
 * <p>Decodes incoming bytes through each payload's {@link StreamCodec} (discovered
 * reflectively via the {@code @ID}/{@code @Codec} marker annotations carried over
 * from Leaf's payload framework), and writes outgoing payloads as
 * {@link ClientboundCustomPayloadPacket} via the player's NMS connection.
 */
public final class JadeMessenger {

    private static final Map<Class<?>, OutgoingDesc<?>> OUTGOING_CACHE = new ConcurrentHashMap<>();

    private record OutgoingDesc<T extends CustomPacketPayload>(Identifier id, StreamCodec<RegistryFriendlyByteBuf, T> codec) {
    }

    private JadeMessenger() {
    }

    public static <T extends CustomPacketPayload> void registerIncoming(
        @NotNull Plugin plugin,
        @NotNull Class<T> payloadClass,
        @NotNull BiConsumer<ServerPlayer, T> handler
    ) {
        Identifier id = findId(payloadClass);
        @SuppressWarnings("unchecked")
        StreamCodec<RegistryFriendlyByteBuf, T> codec = (StreamCodec<RegistryFriendlyByteBuf, T>) findCodec(payloadClass);
        String channel = id.toString();
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel, new Dispatcher<>(codec, handler));
    }

    public static void registerOutgoing(@NotNull Plugin plugin, @NotNull Class<? extends CustomPacketPayload> payloadClass) {
        Identifier id = findId(payloadClass);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, id.toString());
    }

    /**
     * Serializes the payload through its {@code @Codec} {@link StreamCodec} and sends the
     * resulting bytes wrapped in a {@link DiscardedPayload}. Sending the typed payload
     * directly would route through Paper's {@code IdDispatchCodec}, which only knows the
     * built-in custom-payload registry and would fail to encode our plugin-defined types.
     */
    public static <T extends CustomPacketPayload> void send(@NotNull ServerPlayer player, @NotNull T payload) {
        @SuppressWarnings("unchecked")
        OutgoingDesc<T> desc = (OutgoingDesc<T>) OUTGOING_CACHE.computeIfAbsent(payload.getClass(), JadeMessenger::buildOutgoingDesc);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess());
        try {
            desc.codec().encode(buf, payload);
            byte[] bytes = ByteBufUtil.getBytes(buf);
            player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(desc.id(), bytes)));
        } finally {
            buf.release();
        }
    }

    public static void sendBytes(@NotNull ServerPlayer player, @NotNull Identifier id, byte @NotNull [] data) {
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, data)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OutgoingDesc<?> buildOutgoingDesc(Class<?> payloadClass) {
        Identifier id = findId(payloadClass);
        StreamCodec<RegistryFriendlyByteBuf, ?> codec = (StreamCodec<RegistryFriendlyByteBuf, ?>) findCodec(payloadClass);
        return new OutgoingDesc(id, codec);
    }

    static RegistryFriendlyByteBuf decorate(byte[] data) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), MinecraftServer.getServer().registryAccess());
    }

    private static @NotNull Identifier findId(@NotNull Class<?> payloadClass) {
        for (Field f : payloadClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(org.jadepaper.protocol.LeavesCustomPayload.ID.class)
                && Identifier.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Identifier id = (Identifier) f.get(null);
                    if (id != null) return id;
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Cannot read @ID field on " + payloadClass, e);
                }
            }
        }
        throw new IllegalStateException("No @ID Identifier field found on " + payloadClass);
    }

    private static @NotNull StreamCodec<?, ?> findCodec(@NotNull Class<?> payloadClass) {
        for (Field f : payloadClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(org.jadepaper.protocol.LeavesCustomPayload.Codec.class)
                && StreamCodec.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    StreamCodec<?, ?> codec = (StreamCodec<?, ?>) f.get(null);
                    if (codec != null) return codec;
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Cannot read @Codec field on " + payloadClass, e);
                }
            }
        }
        throw new IllegalStateException("No @Codec StreamCodec field found on " + payloadClass);
    }

    private static final class Dispatcher<T extends CustomPacketPayload> implements PluginMessageListener {
        private final StreamCodec<RegistryFriendlyByteBuf, T> codec;
        private final BiConsumer<ServerPlayer, T> handler;

        Dispatcher(StreamCodec<RegistryFriendlyByteBuf, T> codec, BiConsumer<ServerPlayer, T> handler) {
            this.codec = codec;
            this.handler = handler;
        }

        @Override
        public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] data) {
            RegistryFriendlyByteBuf buf = decorate(data);
            try {
                T payload = codec.decode(buf);
                handler.accept(((CraftPlayer) player).getHandle(), payload);
            } catch (Exception e) {
                JadeBootstrap.LOGGER.warn("Failed to decode payload on channel {}: {}", channel, e.toString());
            } finally {
                buf.release();
            }
        }
    }
}
