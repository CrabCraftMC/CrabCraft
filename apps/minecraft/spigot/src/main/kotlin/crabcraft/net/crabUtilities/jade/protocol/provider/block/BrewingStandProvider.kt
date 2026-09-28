package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity

enum class BrewingStandProvider : StreamServerDataProvider<BlockAccessor, BrewingStandProvider.Data> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Data {
        val brewingStand = accessor.getBlockEntity() as BrewingStandBlockEntity
        return Data(brewingStand.fuel, brewingStand.brewTime)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Data> = Data.STREAM_CODEC.cast()

    override fun getUid() = UID

    @JvmRecord
    data class Data(val fuel: Int, val time: Int) {
        companion object {
            @JvmField
            val STREAM_CODEC: StreamCodec<ByteBuf, Data> =
                StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    Data::fuel,
                    ByteBufCodecs.VAR_INT,
                    Data::time,
                    ::Data,
                )
        }
    }

    companion object {
        private val UID = JadeProtocol.mc_id("brewing_stand")
    }
}
