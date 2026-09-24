package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity

enum class MobSpawnerCooldownProvider : StreamServerDataProvider<BlockAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Int? {
        val spawner = accessor.getBlockEntity() as TrialSpawnerBlockEntity
        val spawnerData = spawner.getTrialSpawner().getStateData()
        val level = accessor.getLevel()
        return if (
            spawner.getTrialSpawner().canSpawnInLevel(level) && level.getGameTime() < spawnerData.cooldownEndsAt
        ) {
            (spawnerData.cooldownEndsAt - level.getGameTime()).toInt()
        } else null
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("mob_spawner.cooldown")
    }
}
