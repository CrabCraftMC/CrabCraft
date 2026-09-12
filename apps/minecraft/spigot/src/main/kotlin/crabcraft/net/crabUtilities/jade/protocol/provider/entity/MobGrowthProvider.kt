package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.animal.frog.Tadpole
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class MobGrowthProvider : StreamServerDataProvider<EntityAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Int? {
        var time = -1
        val entity = accessor.getEntity()
        if (entity is AgeableMob) {
            time = -entity.age
        } else if (entity is Tadpole) {
            time = crabcraft.net.crabUtilities.jade.protocol.WidenedFields.ticksLeftUntilAdult(entity)
        }
        return if (time > 0) time else null
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid(): Identifier = MC_MOB_GROWTH

    companion object {
        private val MC_MOB_GROWTH = JadeProtocol.mc_id("mob_growth")
    }
}
