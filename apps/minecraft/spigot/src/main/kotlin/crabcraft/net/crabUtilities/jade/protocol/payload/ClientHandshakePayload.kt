package crabcraft.net.crabUtilities.jade.protocol.payload

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier

data class ClientHandshakePayload(private val protocolVersion: String) : LeavesCustomPayload {
    fun protocolVersion(): String = protocolVersion

    companion object {
        @LeavesCustomPayload.ID
        private val PACKET_CLIENT_HANDSHAKE: Identifier = JadeProtocol.id("client_handshake")
        @LeavesCustomPayload.Codec
        private val CODEC: StreamCodec<RegistryFriendlyByteBuf, ClientHandshakePayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, { value: ClientHandshakePayload -> value.protocolVersion() }, ::ClientHandshakePayload)
    }
}
