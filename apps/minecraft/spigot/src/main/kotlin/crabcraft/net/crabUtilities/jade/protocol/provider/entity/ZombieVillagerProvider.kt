package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.entity.monster.zombie.ZombieVillager

enum class ZombieVillagerProvider : StreamServerDataProvider<EntityAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: EntityAccessor): Int? {
        val time = (accessor.getEntity() as ZombieVillager).villagerConversionTime
        return time.takeIf { it > 0 }
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("zombie_villager")
    }
}
