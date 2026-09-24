package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.level.block.entity.BeehiveBlockEntity

enum class BeehiveProvider : StreamServerDataProvider<BlockAccessor, Byte> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Byte? {
        val beehive = accessor.getBlockEntity() as BeehiveBlockEntity
        val bees = beehive.getOccupantCount()
        return (if (beehive.isFull()) bees else -bees).toByte()
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Byte> = ByteBufCodecs.BYTE.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("beehive")
    }
}
