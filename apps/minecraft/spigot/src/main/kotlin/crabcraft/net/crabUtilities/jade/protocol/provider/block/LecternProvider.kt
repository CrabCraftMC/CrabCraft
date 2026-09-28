package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.LecternBlockEntity

enum class LecternProvider : StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): ItemStack? {
        return (accessor.getBlockEntity() as LecternBlockEntity).getBook()
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, ItemStack> = ItemStack.OPTIONAL_STREAM_CODEC

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("lectern")
    }
}
