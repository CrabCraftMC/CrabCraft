package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

data class ClientHandshakePayload(val protocolVersion: String) : LeavesCustomPayload {
    fun protocolVersion() = protocolVersion

    companion object {
        @field:LeavesCustomPayload.ID private val PACKET_CLIENT_HANDSHAKE = JadeProtocol.id("client_handshake")
        @field:LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, ClientHandshakePayload> =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                { value: ClientHandshakePayload -> value.protocolVersion },
                ::ClientHandshakePayload,
            )
    }
}
