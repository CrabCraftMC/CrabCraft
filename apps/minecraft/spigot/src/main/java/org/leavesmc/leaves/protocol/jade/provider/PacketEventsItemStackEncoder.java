package org.leavesmc.leaves.protocol.jade.provider;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.leavesmc.leaves.protocol.jade.accessor.Accessor;
import org.leavesmc.leaves.protocol.jade.util.ViewGroup;

import java.util.List;
import java.util.Map;

final class PacketEventsItemStackEncoder {

    private PacketEventsItemStackEncoder() {
    }

    static Tag encode(
            Accessor<?> accessor,
            Map.Entry<Identifier, List<ViewGroup<ItemStack>>> entry,
            int clientProtocol) {
        ClientVersion clientVersion = ClientVersion.getById(clientProtocol);
        if (clientVersion.getProtocolVersion() != clientProtocol) {
            throw new UnsupportedClientProtocolException(clientProtocol);
        }
        return accessor.encodeAsNbt(ViewGroup.listCodec(itemCodec(clientVersion)), entry);
    }

    private static StreamCodec<RegistryFriendlyByteBuf, ItemStack> itemCodec(ClientVersion clientVersion) {
        return new StreamCodec<>() {
            @Override
            public ItemStack decode(RegistryFriendlyByteBuf buffer) {
                throw new UnsupportedOperationException("Jade's server item codec is encode-only");
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, ItemStack stack) {
                var packetEventsStack = SpigotConversionUtil.fromBukkitItemStack(
                        CraftItemStack.asBukkitCopy(stack));
                writePacketEventsStack(buffer, clientVersion, packetEventsStack);
            }
        };
    }

    static void writePacketEventsStack(
            RegistryFriendlyByteBuf buffer,
            ClientVersion clientVersion,
            com.github.retrooper.packetevents.protocol.item.ItemStack stack) {
        PacketWrapper<?> wrapper = PacketWrapper.createDummyWrapper(clientVersion);
        wrapper.setBuffer(buffer);
        // Jade uses ItemStack.OPTIONAL_STREAM_CODEC, not the distinct ItemStackTemplate codec.
        wrapper.writeItemStack(stack);
    }

    static final class UnsupportedClientProtocolException extends RuntimeException {
        private UnsupportedClientProtocolException(int protocol) {
            super("PacketEvents does not support client protocol " + protocol);
        }
    }
}
