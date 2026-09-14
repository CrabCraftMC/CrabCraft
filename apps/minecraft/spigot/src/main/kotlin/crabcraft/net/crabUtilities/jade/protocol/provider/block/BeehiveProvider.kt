package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.entity.BeehiveBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class BeehiveProvider : StreamServerDataProvider<BlockAccessor, Byte> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Byte {
        val beehive = accessor.getBlockEntity() as BeehiveBlockEntity
        val bees = beehive.occupantCount
        return (if (beehive.isFull) bees else -bees).toByte()
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Byte> = ByteBufCodecs.BYTE.cast()

    override fun getUid(): Identifier = MC_BEEHIVE

    companion object {
        private val MC_BEEHIVE = JadeProtocol.mc_id("beehive")
    }
}
