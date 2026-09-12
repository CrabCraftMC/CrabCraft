package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class MobSpawnerCooldownProvider : StreamServerDataProvider<BlockAccessor, Int> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Int? {
        val spawner = accessor.getBlockEntity() as TrialSpawnerBlockEntity
        val spawnerData = spawner.trialSpawner.stateData
        val level = accessor.getLevel()
        if (spawner.trialSpawner.canSpawnInLevel(level) && level.gameTime < spawnerData.cooldownEndsAt) {
            return (spawnerData.cooldownEndsAt - level.gameTime).toInt()
        }
        return null
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Int> = ByteBufCodecs.VAR_INT.cast()

    override fun getUid(): Identifier = MC_MOB_SPAWNER_COOLDOWN

    companion object {
        private val MC_MOB_SPAWNER_COOLDOWN = JadeProtocol.mc_id("mob_spawner.cooldown")
    }
}
