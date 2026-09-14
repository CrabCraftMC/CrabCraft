package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.LecternBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class LecternProvider : StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): ItemStack {
        return (accessor.getBlockEntity() as LecternBlockEntity).book
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, ItemStack> = ItemStack.OPTIONAL_STREAM_CODEC

    override fun getUid(): Identifier = MC_LECTERN

    companion object {
        private val MC_LECTERN = JadeProtocol.mc_id("lectern")
    }
}
