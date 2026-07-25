package crabcraft.net.crabUtilities.jade.protocol.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.jade.protocol.payload.LeavesCustomPayload;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider;

import java.util.List;
import java.util.Objects;

import static crabcraft.net.crabUtilities.jade.protocol.JadeProtocol.entityDataProviders;

public record RequestEntityPayload(EntityAccessor.SyncData data, List<@Nullable ServerDataProvider<EntityAccessor>> dataProviders) implements LeavesCustomPayload {

    @ID
    private static final Identifier PACKET_REQUEST_ENTITY = JadeProtocol.id("request_entity");

    @Codec
    private static final StreamCodec<RegistryFriendlyByteBuf, RequestEntityPayload> CODEC = StreamCodec.composite(
        EntityAccessor.SyncData.STREAM_CODEC,
        RequestEntityPayload::data,
        ByteBufCodecs.<ByteBuf, ServerDataProvider<EntityAccessor>>list()
            .apply(ByteBufCodecs.idMapper(
                $ -> Objects.requireNonNull(entityDataProviders.idMapper()).byId($),
                $ -> Objects.requireNonNull(entityDataProviders.idMapper()).getIdOrThrow($)
            )),
        RequestEntityPayload::dataProviders,
        RequestEntityPayload::new);
}