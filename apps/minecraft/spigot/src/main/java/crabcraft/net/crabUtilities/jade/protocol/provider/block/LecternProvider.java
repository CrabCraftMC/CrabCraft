package crabcraft.net.crabUtilities.jade.protocol.provider.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum LecternProvider implements StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    private static final Identifier MC_LECTERN = JadeProtocol.mc_id("lectern");

    @Override
    public @NotNull ItemStack streamData(@NotNull BlockAccessor accessor) {
        return ((LecternBlockEntity) accessor.getBlockEntity()).getBook();
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ItemStack> streamCodec() {
        return ItemStack.OPTIONAL_STREAM_CODEC;
    }


    @Override
    public Identifier getUid() {
        return MC_LECTERN;
    }
}
