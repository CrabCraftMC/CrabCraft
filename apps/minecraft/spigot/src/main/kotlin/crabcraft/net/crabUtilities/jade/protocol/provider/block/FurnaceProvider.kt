package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity

enum class FurnaceProvider : StreamServerDataProvider<BlockAccessor, FurnaceProvider.Data> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Data {
        val furnace = accessor.getBlockEntity() as AbstractFurnaceBlockEntity
        return Data(
            furnace.cookingTimer,
            furnace.cookingTotalTime,
            listOf(furnace.getItem(0), furnace.getItem(1), furnace.getItem(2)),
        )
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Data> = Data.STREAM_CODEC

    override fun getUid() = UID

    @JvmRecord
    data class Data(val progress: Int, val total: Int, val inventory: List<ItemStack>) {
        companion object {
            @JvmField
            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, Data> =
                StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    Data::progress,
                    ByteBufCodecs.VAR_INT,
                    Data::total,
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC,
                    Data::inventory,
                    ::Data,
                )
        }
    }

    companion object {
        private val UID = JadeProtocol.mc_id("furnace")
    }
}
