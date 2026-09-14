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
    private val serverConfig: Map<Identifier, Any>,
    private val shearableBlocks: List<Block>,
    private val blockProviderIds: List<Identifier>,
    private val entityProviderIds: List<Identifier>
) : LeavesCustomPayload {
    fun serverConfig(): Map<Identifier, Any> = serverConfig
    fun shearableBlocks(): List<Block> = shearableBlocks
    fun blockProviderIds(): List<Identifier> = blockProviderIds
    fun entityProviderIds(): List<Identifier> = entityProviderIds

    companion object {
        @LeavesCustomPayload.ID
        private val PACKET_SERVER_HANDSHAKE: Identifier = JadeProtocol.id("server_handshake")
        @LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, ServerHandshakePayload> = StreamCodec.composite(
            ByteBufCodecs.map(Maps::newHashMapWithExpectedSize, Identifier.STREAM_CODEC, JadeCodec.PRIMITIVE_STREAM_CODEC), { value: ServerHandshakePayload -> value.serverConfig() },
            ByteBufCodecs.registry(Registries.BLOCK).apply(ByteBufCodecs.list()), { value: ServerHandshakePayload -> value.shearableBlocks() },
            ByteBufCodecs.list<ByteBuf, Identifier>().apply(Identifier.STREAM_CODEC), { value: ServerHandshakePayload -> value.blockProviderIds() },
            ByteBufCodecs.list<ByteBuf, Identifier>().apply(Identifier.STREAM_CODEC), { value: ServerHandshakePayload -> value.entityProviderIds() },
            ::ServerHandshakePayload)
    }
}
