package crabcraft.net.crabUtilities.jade.protocol.provider

import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import net.minecraft.nbt.Tag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import org.bukkit.craftbukkit.inventory.CraftItemStack

object PacketEventsItemStackEncoder {
    @JvmStatic
    fun encode(
        accessor: Accessor<*>,
        entry: Map.Entry<Identifier, List<ViewGroup<ItemStack>>>,
        clientProtocol: Int,
    ): Tag {
        val clientVersion = ClientVersion.getById(clientProtocol)
        if (clientVersion.getProtocolVersion() != clientProtocol) {
            throw UnsupportedClientProtocolException(clientProtocol)
        }
        return accessor.encodeAsNbt(ViewGroup.listCodec(itemCodec(clientVersion)), entry)
    }

    private fun itemCodec(clientVersion: ClientVersion): StreamCodec<RegistryFriendlyByteBuf, ItemStack> =
        object : StreamCodec<RegistryFriendlyByteBuf, ItemStack> {
            override fun decode(buffer: RegistryFriendlyByteBuf): ItemStack =
                throw UnsupportedOperationException("Jade's server item codec is encode-only")

            override fun encode(buffer: RegistryFriendlyByteBuf, stack: ItemStack) {
                val packetEventsStack = SpigotConversionUtil.fromBukkitItemStack(CraftItemStack.asBukkitCopy(stack))
                writePacketEventsStack(buffer, clientVersion, packetEventsStack)
            }
        }

    @JvmStatic
    fun writePacketEventsStack(
        buffer: RegistryFriendlyByteBuf,
        clientVersion: ClientVersion,
        stack: com.github.retrooper.packetevents.protocol.item.ItemStack,
    ) {
        val wrapper = PacketWrapper.createDummyWrapper(clientVersion)
        wrapper.setBuffer(buffer)
        // Jade uses ItemStack.OPTIONAL_STREAM_CODEC, not the distinct ItemStackTemplate codec.
        wrapper.writeItemStack(stack)
    }

    class UnsupportedClientProtocolException(protocol: Int) :
        RuntimeException("PacketEvents does not support client protocol $protocol")
}
