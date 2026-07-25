package crabcraft.net.crabUtilities.jade.protocol.provider.block;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.CalibratedSculkSensorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CalibratedSculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor;
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerDataProvider;

public enum RedstoneProvider implements ServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final Identifier MC_REDSTONE = JadeProtocol.mc_id("redstone");

    @Override
    public void appendServerData(CompoundTag data, @NotNull BlockAccessor accessor) {
        BlockEntity blockEntity = accessor.getBlockEntity();
        if (blockEntity instanceof ComparatorBlockEntity comparator) {
            data.putInt("Signal", comparator.getOutputSignal());
        } else if (blockEntity instanceof CalibratedSculkSensorBlockEntity) {
            Direction direction = accessor.getBlockState().getValue(CalibratedSculkSensorBlock.FACING).getOpposite();
            int signal = accessor.getLevel().getSignal(accessor.getPosition().relative(direction), direction);
            data.putInt("Signal", signal);
        }
    }

    @Override
    public Identifier getUid() {
        return MC_REDSTONE;
    }
}
