package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class EntityHealthProvider : StreamServerDataProvider<EntityAccessor, Float> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Float? {
        val absorption = (accessor.getEntity() as LivingEntity).absorptionAmount
        return if (absorption > 0) absorption else null
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Float> = ByteBufCodecs.FLOAT.cast()

    override fun getUid(): Identifier = MC_ENTITY_HEALTH

    companion object {
        private val MC_ENTITY_HEALTH = JadeProtocol.mc_id("entity_health")
    }
}
