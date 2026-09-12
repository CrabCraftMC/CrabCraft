package crabcraft.net.crabUtilities.jade.protocol.provider

import net.minecraft.nbt.CompoundTag
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor

interface ServerDataProvider<T : Accessor<*>> : JadeProvider {
    fun appendServerData(data: CompoundTag, accessor: T)
}
