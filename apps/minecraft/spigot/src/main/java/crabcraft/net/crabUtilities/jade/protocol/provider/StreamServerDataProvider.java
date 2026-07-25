package crabcraft.net.crabUtilities.jade.protocol.provider;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor;

public interface StreamServerDataProvider<T extends Accessor<?>, D> extends ServerDataProvider<T> {

    @Override
    default void appendServerData(CompoundTag data, T accessor) {
        D value = streamData(accessor);
        if (value != null) {
            data.put(getUid().toString(), accessor.encodeAsNbt(streamCodec(), value));
        }
    }

    @Nullable
    D streamData(T accessor);

    StreamCodec<RegistryFriendlyByteBuf, D> streamCodec();
}
