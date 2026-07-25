package crabcraft.net.crabUtilities.jade.protocol.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum HopperLockProvider implements StreamServerDataProvider<BlockAccessor, Boolean> {
    INSTANCE;

    private static final Identifier MC_HOPPER_LOCK = JadeProtocol.mc_id("hopper_lock");

    @Override
    public Boolean streamData(@NotNull BlockAccessor accessor) {
        return !accessor.getBlockState().getValue(BlockStateProperties.ENABLED);
    }

    @Override
    public @NotNull StreamCodec<RegistryFriendlyByteBuf, Boolean> streamCodec() {
        return ByteBufCodecs.BOOL.cast();
    }

    @Override
    public Identifier getUid() {
        return MC_HOPPER_LOCK;
    }

    @Override
    public int getDefaultPriority() {
        return BlockNameProvider.INSTANCE.getDefaultPriority() + 10;
    }
}