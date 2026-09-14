package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import java.util.function.IntFunction

data class RequestBlockPayload(private val data: BlockAccessor.SyncData, private val dataProviders: List<ServerDataProvider<BlockAccessor>?>) : LeavesCustomPayload {
    fun data(): BlockAccessor.SyncData = data
    fun dataProviders(): List<ServerDataProvider<BlockAccessor>?> = dataProviders

    companion object {
        @LeavesCustomPayload.ID
        private val PACKET_REQUEST_BLOCK: Identifier = JadeProtocol.id("request_block")

        @Suppress("UNCHECKED_CAST")
        private val PROVIDERS_CODEC: StreamCodec<ByteBuf, List<ServerDataProvider<BlockAccessor>?>> =
            ByteBufCodecs.list<ByteBuf, ServerDataProvider<BlockAccessor>>().apply(ByteBufCodecs.idMapper(
                IntFunction<ServerDataProvider<BlockAccessor>?> { id -> JadeProtocol.blockDataProviders.idMapper()!!.byId(id) } as IntFunction<ServerDataProvider<BlockAccessor>>,
                { provider -> JadeProtocol.blockDataProviders.idMapper()!!.getIdOrThrow(provider) }
            )) as StreamCodec<ByteBuf, List<ServerDataProvider<BlockAccessor>?>>

        @LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestBlockPayload> = StreamCodec.composite(
            BlockAccessor.SyncData.STREAM_CODEC, { value: RequestBlockPayload -> value.data() },
            PROVIDERS_CODEC, { value: RequestBlockPayload -> value.dataProviders() },
            ::RequestBlockPayload)
    }
}
