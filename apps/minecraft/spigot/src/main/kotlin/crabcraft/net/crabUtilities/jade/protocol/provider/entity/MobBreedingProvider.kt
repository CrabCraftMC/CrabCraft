package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.animal.allay.Allay

enum class MobBreedingProvider : StreamServerDataProvider<EntityAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Int? {
        val entity = accessor.getEntity()
        val time =
            if (entity is Allay) {
                if (entity.duplicationCooldown > 0 && entity.duplicationCooldown < Int.MAX_VALUE) {
                    entity.duplicationCooldown.toInt()
                } else 0
            } else (entity as Animal).getAge()
        return time.takeIf { it > 0 }
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("mob_breeding")
    }
}
