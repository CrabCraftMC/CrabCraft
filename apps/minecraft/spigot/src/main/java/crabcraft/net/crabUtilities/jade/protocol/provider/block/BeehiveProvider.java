package crabcraft.net.crabUtilities.jade.protocol.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum BeehiveProvider implements StreamServerDataProvider<BlockAccessor, Byte> {
    INSTANCE;

    private static final Identifier MC_BEEHIVE = JadeProtocol.mc_id("beehive");

    @Override
    public @NotNull StreamCodec<RegistryFriendlyByteBuf, Byte> streamCodec() {
        return ByteBufCodecs.BYTE.cast();
    }

    @Override
    public Byte streamData(@NotNull BlockAccessor accessor) {
        BeehiveBlockEntity beehive = (BeehiveBlockEntity) accessor.getBlockEntity();
        int bees = beehive.getOccupantCount();
        return (byte) (beehive.isFull() ? bees : -bees);
    }

    @Override
    public Identifier getUid() {
        return MC_BEEHIVE;
    }
}