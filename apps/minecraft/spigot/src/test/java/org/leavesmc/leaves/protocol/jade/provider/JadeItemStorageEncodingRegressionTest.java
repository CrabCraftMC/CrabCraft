package org.leavesmc.leaves.protocol.jade.provider;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.HexFormat;

final class JadeItemStorageEncodingRegressionTest {

    public static void main(String[] args) {
        check(ItemStorageProvider.selectEncoding(775, 775, false) == ItemStorageProvider.Encoding.NATIVE,
                "native clients should keep using Minecraft's item codec");
        check(ItemStorageProvider.selectEncoding(776, 775, true) == ItemStorageProvider.Encoding.VERSIONED,
                "cross-version clients should use the target-version item codec");
        check(ItemStorageProvider.selectEncoding(776, 775, false) == ItemStorageProvider.Encoding.UNAVAILABLE,
                "cross-version clients must not receive native registry IDs without a versioned codec");
        check(ItemStorageProvider.selectEncoding(775, 776, true) == ItemStorageProvider.Encoding.UNAVAILABLE,
                "older clients must not receive newer registry entries without a downgrade rewriter");
        check(ItemStorageProvider.selectEncoding(777, 775, true) == ItemStorageProvider.Encoding.UNAVAILABLE,
                "unverified multi-version jumps must not use the one-version encoder");

        PacketEvents.setAPI(new ProbeApi());
        verifyDiamondEncoding(ClientVersion.V_26_1, "2a83070000");
        verifyDiamondEncoding(ClientVersion.V_26_2, "2a9e070000");
        verifyComponentEncoding();
    }

    private static void verifyDiamondEncoding(ClientVersion version, String expectedHex) {
        ItemStack source = ItemStack.builder()
                .type(ItemTypes.DIAMOND)
                .amount(42)
                .version(ClientVersion.V_26_1)
                .build();
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            PacketEventsItemStackEncoder.writePacketEventsStack(buffer, version, source);
            check(HexFormat.of().formatHex(bytes(buffer)).equals(expectedHex),
                    version + " encoded the wrong diamond registry ID");

            ItemStack decoded = wrapper(version, buffer).readItemStack();
            check(decoded.getType() == ItemTypes.DIAMOND && decoded.getAmount() == 42,
                    version + " did not round-trip the diamond stack");
        } finally {
            buffer.release();
        }
    }

    private static void verifyComponentEncoding() {
        ItemStack source = ItemStack.builder()
                .type(ItemTypes.DIAMOND)
                .amount(1)
                .component(ComponentTypes.DAMAGE, 7)
                .version(ClientVersion.V_26_1)
                .build();
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            PacketEventsItemStackEncoder.writePacketEventsStack(buffer, ClientVersion.V_26_2, source);
            ItemStack decoded = wrapper(ClientVersion.V_26_2, buffer).readItemStack();
            check(decoded.getType() == ItemTypes.DIAMOND && decoded.getAmount() == 1,
                    "26.2 did not round-trip the component-bearing stack");
            check(decoded.getComponentOr(ComponentTypes.DAMAGE, -1) == 7,
                    "26.2 did not remap the stack's data components");
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), (RegistryAccess) null);
    }

    private static PacketWrapper<?> wrapper(ClientVersion version, ByteBuf buffer) {
        PacketWrapper<?> wrapper = PacketWrapper.createDummyWrapper(version);
        wrapper.setBuffer(buffer);
        return wrapper;
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ProbeApi extends PacketEventsAPI<Void> {
        private final NettyManager nettyManager = new NettyManagerImpl();
        private final ServerManager serverManager = () -> ServerVersion.V_26_1;

        @Override public boolean isLoaded() { return true; }
        @Override public void init() { }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean isTerminated() { return false; }
        @Override public Void getPlugin() { return null; }
        @Override public ServerManager getServerManager() { return serverManager; }
        @Override public ProtocolManager getProtocolManager() { return null; }
        @Override public PlayerManager getPlayerManager() { return null; }
        @Override public NettyManager getNettyManager() { return nettyManager; }
        @Override public ChannelInjector getInjector() { return null; }
    }
}
