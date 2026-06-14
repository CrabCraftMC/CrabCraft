package org.leavesmc.leaves.protocol.jade.provider.entity;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.jadepaper.WidenedFields;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.accessor.EntityAccessor;
import org.leavesmc.leaves.protocol.jade.provider.StreamServerDataProvider;

public enum WaxedProvider implements StreamServerDataProvider<EntityAccessor, Unit> {
    INSTANCE;

    // Mirrors CopperGolem.IGNORE_WEATHERING_TICK: a waxed golem's oxidation is suspended,
    // so its next weathering tick is parked at this sentinel instead of a real game time.
    private static final long IGNORE_WEATHERING_TICK = -2L;
    private static final StreamCodec<RegistryFriendlyByteBuf, Unit> STREAM_CODEC = StreamCodec.unit(Unit.INSTANCE);
    private static final Identifier MC_WAXED = JadeProtocol.mc_id("waxed");

    @Override
    @Nullable
    public Unit streamData(@NotNull EntityAccessor accessor) {
        CopperGolem golem = (CopperGolem) accessor.getEntity();
        return WidenedFields.nextWeatheringTick(golem) == IGNORE_WEATHERING_TICK ? Unit.INSTANCE : null;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, Unit> streamCodec() {
        return STREAM_CODEC;
    }

    @Override
    public Identifier getUid() {
        return MC_WAXED;
    }
}
