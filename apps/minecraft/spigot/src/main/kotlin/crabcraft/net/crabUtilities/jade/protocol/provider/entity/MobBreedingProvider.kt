package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.allay.Allay
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class MobBreedingProvider : StreamServerDataProvider<EntityAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Int? {
        var time = 0
        val entity = accessor.getEntity()
        if (entity is Allay) {
            if (entity.duplicationCooldown > 0 && entity.duplicationCooldown < Int.MAX_VALUE) {
                time = entity.duplicationCooldown.toInt()
            }
        } else {
            time = (entity as Animal).age
        }
        return if (time > 0) time else null
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid(): Identifier = MC_MOB_BREEDING

    companion object {
        private val MC_MOB_BREEDING = JadeProtocol.mc_id("mob_breeding")
    }
}
