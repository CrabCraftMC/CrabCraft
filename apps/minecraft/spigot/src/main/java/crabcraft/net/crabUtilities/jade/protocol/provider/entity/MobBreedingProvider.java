package crabcraft.net.crabUtilities.jade.protocol.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum MobBreedingProvider implements StreamServerDataProvider<EntityAccessor, Integer> {
    INSTANCE;

    private static final Identifier MC_MOB_BREEDING = JadeProtocol.mc_id("mob_breeding");

    @Override
    public @Nullable Integer streamData(@NotNull EntityAccessor accessor) {
        int time = 0;
        Entity entity = accessor.getEntity();
        if (entity instanceof Allay allay) {
            if (allay.duplicationCooldown > 0 && allay.duplicationCooldown < Integer.MAX_VALUE) {
                time = (int) allay.duplicationCooldown;
            }
        } else {
            time = ((Animal) entity).getAge();
        }
        return time > 0 ? time : null;
    }

    @Override
    public @NotNull StreamCodec<RegistryFriendlyByteBuf, Integer> streamCodec() {
        return ByteBufCodecs.VAR_INT.cast();
    }

    @Override
    public Identifier getUid() {
        return MC_MOB_BREEDING;
    }
}
