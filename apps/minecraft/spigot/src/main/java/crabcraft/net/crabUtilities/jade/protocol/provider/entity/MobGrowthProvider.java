package crabcraft.net.crabUtilities.jade.protocol.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Tadpole;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider;

public enum MobGrowthProvider implements StreamServerDataProvider<EntityAccessor, Integer> {
    INSTANCE;

    private static final Identifier MC_MOB_GROWTH = JadeProtocol.mc_id("mob_growth");

    @Override
    public @Nullable Integer streamData(@NotNull EntityAccessor accessor) {
        int time = -1;
        Entity entity = accessor.getEntity();
        if (entity instanceof AgeableMob ageable) {
            time = -ageable.getAge();
        } else if (entity instanceof Tadpole tadpole) {
            time = crabcraft.net.crabUtilities.jade.protocol.WidenedFields.ticksLeftUntilAdult(tadpole);
        }
        return time > 0 ? time : null;
    }

    @Override
    public @NotNull StreamCodec<RegistryFriendlyByteBuf, Integer> streamCodec() {
        return ByteBufCodecs.VAR_INT.cast();
    }


    @Override
    public Identifier getUid() {
        return MC_MOB_GROWTH;
    }
}
