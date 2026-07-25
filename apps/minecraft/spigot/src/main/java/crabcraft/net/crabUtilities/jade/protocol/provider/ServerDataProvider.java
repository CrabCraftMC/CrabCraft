package crabcraft.net.crabUtilities.jade.protocol.provider;

import net.minecraft.nbt.CompoundTag;
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor;

public interface ServerDataProvider<T extends Accessor<?>> extends JadeProvider {
    void appendServerData(CompoundTag data, T accessor);
}
