package crabcraft.net.crabUtilities.jade.protocol.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum JukeboxProvider implements StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    private static final Identifier MC_JUKEBOX = JadeProtocol.mc_id("jukebox");

    @Override
    public @NotNull ItemStack streamData(BlockAccessor accessor) {
        return ((JukeboxBlockEntity) accessor.getBlockEntity()).getTheItem();
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ItemStack> streamCodec() {
        return ItemStack.OPTIONAL_STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return MC_JUKEBOX;
    }
}
