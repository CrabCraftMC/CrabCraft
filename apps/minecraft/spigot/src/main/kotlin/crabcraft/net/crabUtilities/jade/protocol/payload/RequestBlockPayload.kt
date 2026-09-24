package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

data class RequestBlockPayload(
    val data: BlockAccessor.SyncData,
    val dataProviders: List<ServerDataProvider<BlockAccessor>?>,
) : LeavesCustomPayload {
    fun data() = data

    fun dataProviders() = dataProviders

    companion object {
        @field:LeavesCustomPayload.ID private val PACKET_REQUEST_BLOCK = JadeProtocol.id("request_block")
        @Suppress("UNCHECKED_CAST")
        private val PROVIDERS_CODEC =
            ByteBufCodecs.list<ByteBuf, ServerDataProvider<BlockAccessor>>()
                .apply(
                    ByteBufCodecs.idMapper<ServerDataProvider<BlockAccessor>>(
                        { id -> nullableProvider(requireNotNull(JadeProtocol.blockDataProviders.idMapper()).byId(id)) },
                        { provider ->
                            requireNotNull(JadeProtocol.blockDataProviders.idMapper()).getIdOrThrow(provider)
                        },
                    )
                ) as StreamCodec<ByteBuf, List<ServerDataProvider<BlockAccessor>?>>

        // Preserve the protocol's nullable result for unknown provider IDs despite NMS's non-null generic bound.
        @Suppress("UNCHECKED_CAST") private fun <T> nullableProvider(value: T?): T = value as T

        @field:LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestBlockPayload> =
            StreamCodec.composite(
                BlockAccessor.SyncData.STREAM_CODEC,
                { value: RequestBlockPayload -> value.data },
                PROVIDERS_CODEC,
                { value: RequestBlockPayload -> value.dataProviders },
                ::RequestBlockPayload,
            )
    }
}
