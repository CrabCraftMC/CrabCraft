package crabcraft.net.crabUtilities.jade.protocol.payload;


import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import crabcraft.net.crabUtilities.jade.protocol.payload.LeavesCustomPayload;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;

import java.util.List;
import java.util.Map;

import static crabcraft.net.crabUtilities.jade.protocol.util.JadeCodec.PRIMITIVE_STREAM_CODEC;

public record ServerHandshakePayload(
    Map<Identifier, Object> serverConfig,
    List<Block> shearableBlocks,
    List<Identifier> blockProviderIds,
    List<Identifier> entityProviderIds
) implements LeavesCustomPayload {

    @ID
    private static final Identifier PACKET_SERVER_HANDSHAKE = JadeProtocol.id("server_handshake");

    @Codec
    private static final StreamCodec<RegistryFriendlyByteBuf, ServerHandshakePayload> CODEC = StreamCodec.composite(
        ByteBufCodecs.map(Maps::newHashMapWithExpectedSize, Identifier.STREAM_CODEC, PRIMITIVE_STREAM_CODEC),
        ServerHandshakePayload::serverConfig,
        ByteBufCodecs.registry(Registries.BLOCK).apply(ByteBufCodecs.list()),
        ServerHandshakePayload::shearableBlocks,
        ByteBufCodecs.<ByteBuf, Identifier>list().apply(Identifier.STREAM_CODEC),
        ServerHandshakePayload::blockProviderIds,
        ByteBufCodecs.<ByteBuf, Identifier>list().apply(Identifier.STREAM_CODEC),
        ServerHandshakePayload::entityProviderIds,
        ServerHandshakePayload::new
    );
}