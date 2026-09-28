package crabcraft.net.crabUtilities.jade.protocol.provider.entity

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.WidenedFields
import crabcraft.net.crabUtilities.jade.protocol.accessor.EntityAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.animal.armadillo.Armadillo
import net.minecraft.world.entity.animal.chicken.Chicken
import net.minecraft.world.entity.animal.sniffer.Sniffer

enum class NextEntityDropProvider : ServerDataProvider<EntityAccessor> {
    INSTANCE;

    override fun appendServerData(data: CompoundTag, accessor: EntityAccessor) {
        val max = 24000 * 2
        when (val entity = accessor.getEntity()) {
            is Chicken ->
                if (!entity.isBaby() && entity.eggTime < max) {
                    data.putInt("NextEggIn", entity.eggTime)
                }
            is Armadillo -> {
                val scuteTime = WidenedFields.scuteTime(entity)
                if (!entity.isBaby() && scuteTime < max) data.putInt("NextScuteIn", scuteTime)
            }
            is Sniffer -> {
                val time = entity.getBrain().getTimeUntilExpiry(MemoryModuleType.SNIFF_COOLDOWN)
                if (time > 0 && time < max) data.putInt("NextSniffIn", time.toInt())
            }
        }
    }

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("next_entity_drop")
    }
}
