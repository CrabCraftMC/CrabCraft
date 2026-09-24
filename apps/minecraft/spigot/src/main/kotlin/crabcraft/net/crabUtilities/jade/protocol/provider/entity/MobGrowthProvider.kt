package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.WidenedFields
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.animal.frog.Tadpole

enum class MobGrowthProvider : StreamServerDataProvider<EntityAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Int? {
        val time =
            when (val entity = accessor.getEntity()) {
                is AgeableMob -> -entity.getAge()
                is Tadpole -> WidenedFields.ticksLeftUntilAdult(entity)
                else -> -1
            }
        return time.takeIf { it > 0 }
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("mob_growth")
    }
}
