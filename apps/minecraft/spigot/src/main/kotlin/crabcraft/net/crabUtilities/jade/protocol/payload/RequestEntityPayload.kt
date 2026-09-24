package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

data class RequestEntityPayload(
    val data: EntityAccessor.SyncData,
    val dataProviders: List<ServerDataProvider<EntityAccessor>?>,
) : LeavesCustomPayload {
    fun data() = data

    fun dataProviders() = dataProviders

    companion object {
        @field:LeavesCustomPayload.ID private val PACKET_REQUEST_ENTITY = JadeProtocol.id("request_entity")
        @Suppress("UNCHECKED_CAST")
        private val PROVIDERS_CODEC =
            ByteBufCodecs.list<ByteBuf, ServerDataProvider<EntityAccessor>>()
                .apply(
                    ByteBufCodecs.idMapper<ServerDataProvider<EntityAccessor>>(
                        { id ->
                            nullableProvider(requireNotNull(JadeProtocol.entityDataProviders.idMapper()).byId(id))
                        },
                        { provider ->
                            requireNotNull(JadeProtocol.entityDataProviders.idMapper()).getIdOrThrow(provider)
                        },
                    )
                ) as StreamCodec<ByteBuf, List<ServerDataProvider<EntityAccessor>?>>

        // Preserve the protocol's nullable result for unknown provider IDs despite NMS's non-null generic bound.
        @Suppress("UNCHECKED_CAST") private fun <T> nullableProvider(value: T?): T = value as T

        @field:LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestEntityPayload> =
            StreamCodec.composite(
                EntityAccessor.SyncData.STREAM_CODEC,
                { value: RequestEntityPayload -> value.data },
                PROVIDERS_CODEC,
                { value: RequestEntityPayload -> value.dataProviders },
                ::RequestEntityPayload,
            )
    }
}
