package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import java.util.function.IntFunction

data class RequestEntityPayload(private val data: EntityAccessor.SyncData, private val dataProviders: List<ServerDataProvider<EntityAccessor>?>) : LeavesCustomPayload {
    fun data(): EntityAccessor.SyncData = data
    fun dataProviders(): List<ServerDataProvider<EntityAccessor>?> = dataProviders

    companion object {
        @LeavesCustomPayload.ID
        private val PACKET_REQUEST_ENTITY: Identifier = JadeProtocol.id("request_entity")

        @Suppress("UNCHECKED_CAST")
        private val PROVIDERS_CODEC: StreamCodec<ByteBuf, List<ServerDataProvider<EntityAccessor>?>> =
            ByteBufCodecs.list<ByteBuf, ServerDataProvider<EntityAccessor>>().apply(ByteBufCodecs.idMapper(
                IntFunction<ServerDataProvider<EntityAccessor>?> { id -> JadeProtocol.entityDataProviders.idMapper()!!.byId(id) } as IntFunction<ServerDataProvider<EntityAccessor>>,
                { provider -> JadeProtocol.entityDataProviders.idMapper()!!.getIdOrThrow(provider) }
            )) as StreamCodec<ByteBuf, List<ServerDataProvider<EntityAccessor>?>>

        @LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestEntityPayload> = StreamCodec.composite(
            EntityAccessor.SyncData.STREAM_CODEC, { value: RequestEntityPayload -> value.data() },
            PROVIDERS_CODEC, { value: RequestEntityPayload -> value.dataProviders() },
            ::RequestEntityPayload)
    }
}
