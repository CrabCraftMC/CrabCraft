package crabcraft.net.crabUtilities.jade.protocol.payload

import com.google.common.collect.Maps
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.util.JadeCodec
import io.netty.buffer.ByteBuf
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block

data class ServerHandshakePayload(
    val serverConfig: Map<Identifier, Any>,
    val shearableBlocks: List<Block>,
    val blockProviderIds: List<Identifier>,
    val entityProviderIds: List<Identifier>,
) : LeavesCustomPayload {
    fun serverConfig() = serverConfig

    fun shearableBlocks() = shearableBlocks

    fun blockProviderIds() = blockProviderIds

    fun entityProviderIds() = entityProviderIds

    companion object {
        @field:LeavesCustomPayload.ID private val PACKET_SERVER_HANDSHAKE = JadeProtocol.id("server_handshake")
        @field:LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, ServerHandshakePayload> =
            StreamCodec.composite(
                ByteBufCodecs.map(
                    { size -> Maps.newHashMapWithExpectedSize<Identifier, Any>(size) },
                    Identifier.STREAM_CODEC,
                    JadeCodec.PRIMITIVE_STREAM_CODEC,
                ),
                { value: ServerHandshakePayload -> value.serverConfig },
                ByteBufCodecs.registry(Registries.BLOCK).apply(ByteBufCodecs.list()),
                { value: ServerHandshakePayload -> value.shearableBlocks },
                ByteBufCodecs.list<ByteBuf, Identifier>().apply(Identifier.STREAM_CODEC),
                { value: ServerHandshakePayload -> value.blockProviderIds },
                ByteBufCodecs.list<ByteBuf, Identifier>().apply(Identifier.STREAM_CODEC),
                { value: ServerHandshakePayload -> value.entityProviderIds },
                ::ServerHandshakePayload,
            )
    }
}
