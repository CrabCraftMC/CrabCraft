package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

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

    override fun getUid(): Identifier = MC_FURNACE

    data class Data(private val progress: Int, private val total: Int, private val inventory: List<ItemStack>) {
        fun progress(): Int = progress
        fun total(): Int = total
        fun inventory(): List<ItemStack> = inventory

        companion object {
            @JvmField
            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, Data> = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                { data: Data -> data.progress() },
                ByteBufCodecs.VAR_INT,
                { data: Data -> data.total() },
                ItemStack.OPTIONAL_LIST_STREAM_CODEC,
                { data: Data -> data.inventory() },
                ::Data,
            )
        }
    }

    companion object {
        private val MC_FURNACE = JadeProtocol.mc_id("furnace")
    }
}
