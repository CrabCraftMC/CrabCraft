package crabcraft.net.crabUtilities.jade.protocol.provider

import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec

interface StreamServerDataProvider<T : Accessor<*>, D : Any> : ServerDataProvider<T> {
    override fun appendServerData(data: CompoundTag, accessor: T) {
        val value = streamData(accessor) ?: return
        data.put(getUid().toString(), accessor.encodeAsNbt(streamCodec(), value))
    }

    fun streamData(accessor: T): D?

    fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, D>
}
