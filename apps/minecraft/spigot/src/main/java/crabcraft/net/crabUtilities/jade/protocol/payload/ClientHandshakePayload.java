package crabcraft.net.crabUtilities.jade.protocol.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import crabcraft.net.crabUtilities.jade.protocol.payload.LeavesCustomPayload;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;

public record ClientHandshakePayload(String protocolVersion) implements LeavesCustomPayload {

    @ID
    private static final Identifier PACKET_CLIENT_HANDSHAKE = JadeProtocol.id("client_handshake");

    @Codec
    private static final StreamCodec<RegistryFriendlyByteBuf, ClientHandshakePayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, ClientHandshakePayload::protocolVersion, ClientHandshakePayload::new
    );
}