package crabcraft.net.crabUtilities.jade.protocol.provider.block

import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class BrewingStandProvider : StreamServerDataProvider<BlockAccessor, BrewingStandProvider.Data> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Data {
        val brewingStand = accessor.getBlockEntity() as BrewingStandBlockEntity
        return Data(brewingStand.fuel, brewingStand.brewTime)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Data> = Data.STREAM_CODEC.cast()

    override fun getUid(): Identifier = MC_BREWING_STAND

    data class Data(private val fuel: Int, private val time: Int) {
        fun fuel(): Int = fuel
        fun time(): Int = time

        companion object {
            @JvmField
            val STREAM_CODEC: StreamCodec<ByteBuf, Data> = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                { data: Data -> data.fuel() },
                ByteBufCodecs.VAR_INT,
                { data: Data -> data.time() },
                ::Data,
            )
        }
    }

    companion object {
        private val MC_BREWING_STAND = JadeProtocol.mc_id("brewing_stand")
    }
}
