package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.CalibratedSculkSensorBlock
import net.minecraft.world.level.block.entity.CalibratedSculkSensorBlockEntity
import net.minecraft.world.level.block.entity.ComparatorBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider

enum class RedstoneProvider : ServerDataProvider<BlockAccessor> {
    INSTANCE;

    override fun appendServerData(data: CompoundTag, accessor: BlockAccessor) {
        val blockEntity = accessor.getBlockEntity()
        if (blockEntity is ComparatorBlockEntity) {
            data.putInt("Signal", blockEntity.outputSignal)
        } else if (blockEntity is CalibratedSculkSensorBlockEntity) {
            val direction = accessor.getBlockState().getValue(CalibratedSculkSensorBlock.FACING).opposite
            val signal = accessor.getLevel().getSignal(accessor.getPosition().relative(direction), direction)
            data.putInt("Signal", signal)
        }
    }

    override fun getUid(): Identifier = MC_REDSTONE

    companion object {
        private val MC_REDSTONE = JadeProtocol.mc_id("redstone")
    }
}
