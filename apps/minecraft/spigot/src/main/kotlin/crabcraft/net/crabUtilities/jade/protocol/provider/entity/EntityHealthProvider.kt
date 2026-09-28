package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.entity.LivingEntity

enum class EntityHealthProvider : StreamServerDataProvider<EntityAccessor, Float> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Float? {
        val absorption = (accessor.getEntity() as LivingEntity).getAbsorptionAmount()
        return absorption.takeIf { it > 0 }
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Float> = ByteBufCodecs.FLOAT.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("entity_health")
    }
}
