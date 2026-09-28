package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.CalibratedSculkSensorBlock
import net.minecraft.world.level.block.entity.CalibratedSculkSensorBlockEntity
import net.minecraft.world.level.block.entity.ComparatorBlockEntity

enum class RedstoneProvider : ServerDataProvider<BlockAccessor> {
    INSTANCE;

    override fun appendServerData(data: CompoundTag, accessor: BlockAccessor) {
        when (val blockEntity = accessor.getBlockEntity()) {
            is ComparatorBlockEntity -> data.putInt("Signal", blockEntity.getOutputSignal())
            is CalibratedSculkSensorBlockEntity -> {
                val direction = accessor.getBlockState().getValue(CalibratedSculkSensorBlock.FACING).getOpposite()
                val signal = accessor.getLevel().getSignal(accessor.getPosition().relative(direction), direction)
                data.putInt("Signal", signal)
            }
        }
    }

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("redstone")
    }
}
