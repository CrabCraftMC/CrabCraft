package crabcraft.net.crabUtilities.jade.protocol

import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.payload.LeavesCustomPayload
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** Encodes Jade payloads through their annotated codecs and plugin channels. */
object JadeMessenger {
    private val OUTGOING_CACHE = ConcurrentHashMap<Class<*>, OutgoingDesc<*>>()
    private val MALFORMED_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1)

    private data class OutgoingDesc<T : CustomPacketPayload>(
        val id: Identifier,
        val codec: StreamCodec<RegistryFriendlyByteBuf, T>,
    )

    @JvmStatic
    fun <T : CustomPacketPayload> registerIncoming(
        plugin: Plugin,
        payloadClass: Class<T>,
        handler: BiConsumer<ServerPlayer, T>,
    ) {
        @Suppress("UNCHECKED_CAST") val codec = findCodec(payloadClass) as StreamCodec<RegistryFriendlyByteBuf, T>
        Bukkit.getMessenger()
            .registerIncomingPluginChannel(plugin, findId(payloadClass).toString(), Dispatcher(codec, handler))
    }

    @JvmStatic
    fun registerOutgoing(plugin: Plugin, payloadClass: Class<out CustomPacketPayload>) {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, findId(payloadClass).toString())
    }

    @JvmStatic
    fun unregisterIncoming(plugin: Plugin, payloadClass: Class<out CustomPacketPayload>) {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, findId(payloadClass).toString())
    }

    @JvmStatic
    fun unregisterOutgoing(plugin: Plugin, payloadClass: Class<out CustomPacketPayload>) {
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, findId(payloadClass).toString())
    }

    @JvmStatic fun channel(payloadClass: Class<out CustomPacketPayload>) = findId(payloadClass).toString()

    /** DiscardedPayload avoids Paper's registry of built-in payload types. */
    @JvmStatic
    fun <T : CustomPacketPayload> send(player: ServerPlayer, payload: T) {
        @Suppress("UNCHECKED_CAST")
        val desc = OUTGOING_CACHE.computeIfAbsent(payload.javaClass, ::buildOutgoingDesc) as OutgoingDesc<T>
        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess())
        try {
            desc.codec.encode(buf, payload)
            player.connection.send(ClientboundCustomPayloadPacket(DiscardedPayload(desc.id, ByteBufUtil.getBytes(buf))))
        } finally {
            buf.release()
        }
    }

    @JvmStatic
    fun sendBytes(player: ServerPlayer, id: Identifier, data: ByteArray) {
        player.connection.send(ClientboundCustomPayloadPacket(DiscardedPayload(id, data)))
    }

    private fun buildOutgoingDesc(payloadClass: Class<*>): OutgoingDesc<*> {
        @Suppress("UNCHECKED_CAST")
        val codec = findCodec(payloadClass) as StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload>
        return OutgoingDesc(findId(payloadClass), codec)
    }

    @JvmStatic
    fun decorate(data: ByteArray) =
        RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), MinecraftServer.getServer().registryAccess())

    private fun findId(payloadClass: Class<*>): Identifier {
        for (field in payloadClass.declaredFields) {
            if (
                field.isAnnotationPresent(LeavesCustomPayload.ID::class.java) &&
                    Identifier::class.java.isAssignableFrom(field.type)
            ) {
                try {
                    field.isAccessible = true
                    (field.get(null) as Identifier?)?.let {
                        return it
                    }
                } catch (e: IllegalAccessException) {
                    throw RuntimeException("Cannot read @ID field on $payloadClass", e)
                }
            }
        }
        throw IllegalStateException("No @ID Identifier field found on $payloadClass")
    }

    private fun findCodec(payloadClass: Class<*>): StreamCodec<*, *> {
        for (field in payloadClass.declaredFields) {
            if (
                field.isAnnotationPresent(LeavesCustomPayload.Codec::class.java) &&
                    StreamCodec::class.java.isAssignableFrom(field.type)
            ) {
                try {
                    field.isAccessible = true
                    (field.get(null) as StreamCodec<*, *>?)?.let {
                        return it
                    }
                } catch (e: IllegalAccessException) {
                    throw RuntimeException("Cannot read @Codec field on $payloadClass", e)
                }
            }
        }
        throw IllegalStateException("No @Codec StreamCodec field found on $payloadClass")
    }

    private class Dispatcher<T : CustomPacketPayload>(
        private val codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        private val handler: BiConsumer<ServerPlayer, T>,
    ) : PluginMessageListener {
        private val malformedWarnings = WarningLimiter(MALFORMED_WARNING_INTERVAL_NANOS)

        override fun onPluginMessageReceived(channel: String, player: Player, data: ByteArray) {
            val buf = decorate(data)
            try {
                val payload = codec.decode(buf)
                handler.accept((player as CraftPlayer).handle, payload)
            } catch (e: Exception) {
                val suppressed = malformedWarnings.claim(System.nanoTime())
                if (suppressed == 0) {
                    JadeBootstrap.LOGGER.warn("Rejected malformed Jade payload on channel {}", channel)
                } else if (suppressed > 0) {
                    JadeBootstrap.LOGGER.warn(
                        "Rejected malformed Jade payload on channel {}; {} similar payloads were suppressed",
                        channel,
                        suppressed,
                    )
                }
            } finally {
                buf.release()
            }
        }
    }

    class WarningLimiter(private val intervalNanos: Long) {
        private var logged = false
        private var lastLoggedAt = 0L
        private var suppressed = 0

        init {
            require(intervalNanos > 0) { "intervalNanos must be positive" }
        }

        @Synchronized
        fun claim(now: Long): Int {
            if (!logged || now - lastLoggedAt >= intervalNanos) {
                val previousSuppressed = suppressed
                logged = true
                lastLoggedAt = now
                suppressed = 0
                return previousSuppressed
            }
            suppressed++
            return -1
        }
    }
}
