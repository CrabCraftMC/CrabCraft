package crabcraft.net.crabUtilities.jade.protocol.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum PetArmorProvider implements StreamServerDataProvider<EntityAccessor, ItemStack> {
    INSTANCE;

    private static final Identifier MC_PET_ARMOR = JadeProtocol.mc_id("pet_armor");

    @Nullable
    @Override
    public ItemStack streamData(@NotNull EntityAccessor accessor) {
        ItemStack armor = ((Mob) accessor.getEntity()).getBodyArmorItem();
        return armor.isEmpty() ? null : armor;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, ItemStack> streamCodec() {
        return ItemStack.OPTIONAL_STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return MC_PET_ARMOR;
    }
}
