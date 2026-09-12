package crabcraft.net.crabUtilities.jade.protocol.provider

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.NettyManager
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import java.util.HexFormat

object JadeItemStorageEncodingRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(ItemStorageProvider.selectEncoding(775, 775, false) == ItemStorageProvider.Encoding.NATIVE,
            "native clients should keep using Minecraft's item codec")
        check(ItemStorageProvider.selectEncoding(776, 775, true) == ItemStorageProvider.Encoding.VERSIONED,
            "cross-version clients should use the target-version item codec")
        check(ItemStorageProvider.selectEncoding(776, 775, false) == ItemStorageProvider.Encoding.UNAVAILABLE,
            "cross-version clients must not receive native registry IDs without a versioned codec")
        check(ItemStorageProvider.selectEncoding(775, 776, true) == ItemStorageProvider.Encoding.UNAVAILABLE,
            "older clients must not receive newer registry entries without a downgrade rewriter")
        check(ItemStorageProvider.selectEncoding(777, 775, true) == ItemStorageProvider.Encoding.UNAVAILABLE,
            "unverified multi-version jumps must not use the one-version encoder")

        PacketEvents.setAPI(ProbeApi())
        verifyDiamondEncoding(ClientVersion.V_26_1, "2a83070000")
        verifyDiamondEncoding(ClientVersion.V_26_2, "2a9e070000")
        verifyComponentEncoding()
    }

    private fun verifyDiamondEncoding(version: ClientVersion, expectedHex: String) {
        val source = ItemStack.builder()
            .type(ItemTypes.DIAMOND)
            .amount(42)
            .version(ClientVersion.V_26_1)
            .build()
        val buffer = buffer()
        try {
            PacketEventsItemStackEncoder.writePacketEventsStack(buffer, version, source)
            check(HexFormat.of().formatHex(bytes(buffer)) == expectedHex,
                "$version encoded the wrong diamond registry ID")

            val decoded = wrapper(version, buffer).readItemStack()
            check(decoded.type == ItemTypes.DIAMOND && decoded.amount == 42,
                "$version did not round-trip the diamond stack")
        } finally {
            buffer.release()
        }
    }

    private fun verifyComponentEncoding() {
        val source = ItemStack.builder()
            .type(ItemTypes.DIAMOND)
            .amount(1)
            .component(ComponentTypes.DAMAGE, 7)
            .version(ClientVersion.V_26_1)
            .build()
        val buffer = buffer()
        try {
            PacketEventsItemStackEncoder.writePacketEventsStack(buffer, ClientVersion.V_26_2, source)
            val decoded = wrapper(ClientVersion.V_26_2, buffer).readItemStack()
            check(decoded.type == ItemTypes.DIAMOND && decoded.amount == 1,
                "26.2 did not round-trip the component-bearing stack")
            check(decoded.getComponentOr(ComponentTypes.DAMAGE, -1) == 7,
                "26.2 did not remap the stack's data components")
        } finally {
            buffer.release()
        }
    }

    private fun buffer(): RegistryFriendlyByteBuf {
        // Keep the registry absent to verify that PacketEvents encoding does not consult it.
        return RegistryFriendlyByteBuf::class.java
            .getConstructor(ByteBuf::class.java, RegistryAccess::class.java)
            .newInstance(Unpooled.buffer(), null)
    }

    private fun wrapper(version: ClientVersion, buffer: ByteBuf): PacketWrapper<*> {
        val wrapper = PacketWrapper.createDummyWrapper(version)
        wrapper.buffer = buffer
        return wrapper
    }

    private fun bytes(buffer: ByteBuf): ByteArray {
        val bytes = ByteArray(buffer.readableBytes())
        buffer.getBytes(buffer.readerIndex(), bytes)
        return bytes
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private class ProbeApi : PacketEventsAPI<Void>() {
        private val nettyManager: NettyManager = NettyManagerImpl()
        private val serverManager = ServerManager { ServerVersion.V_26_1 }

        override fun isLoaded(): Boolean = true
        override fun init() {}
        override fun isInitialized(): Boolean = true
        override fun isTerminated(): Boolean = false
        override fun getPlugin(): Void? = null
        override fun getServerManager(): ServerManager = serverManager
        override fun getProtocolManager(): ProtocolManager? = null
        override fun getPlayerManager(): PlayerManager? = null
        override fun getNettyManager(): NettyManager = nettyManager
        override fun getInjector(): ChannelInjector? = null
    }
}
