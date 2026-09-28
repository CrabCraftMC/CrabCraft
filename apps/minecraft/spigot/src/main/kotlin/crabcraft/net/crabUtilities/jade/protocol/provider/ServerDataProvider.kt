package crabcraft.net.crabUtilities.jade.protocol.provider

import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import net.minecraft.nbt.CompoundTag

interface ServerDataProvider<T : Accessor<*>> : JadeProvider {
    fun appendServerData(data: CompoundTag, accessor: T)
}
