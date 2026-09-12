package crabcraft.net.crabUtilities.jade.protocol.provider

import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor

interface StreamServerDataProvider<T : Accessor<*>, D : Any> : ServerDataProvider<T> {
    override fun appendServerData(data: CompoundTag, accessor: T) {
        val value = streamData(accessor)
        if (value != null) {
            data.put(getUid().toString(), accessor.encodeAsNbt(streamCodec(), value))
        }
    }

    fun streamData(accessor: T): D?

    fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, D>
}
