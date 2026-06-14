package org.leavesmc.leaves.protocol.jade.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

public enum EntityHealthProvider implements StreamServerDataProvider<EntityAccessor, Float> {
    INSTANCE;

    private static final Identifier MC_ENTITY_HEALTH = JadeProtocol.mc_id("entity_health");

    @Override
    @Nullable
    public Float streamData(@NotNull EntityAccessor accessor) {
        float absorption = ((LivingEntity) accessor.getEntity()).getAbsorptionAmount();
        return absorption > 0 ? absorption : null;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Float> streamCodec() {
        return ByteBufCodecs.FLOAT.cast();
    }

    @Override
    public Identifier getUid() {
        return MC_ENTITY_HEALTH;
    }
}
