package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class HopperLockProvider : StreamServerDataProvider<BlockAccessor, Boolean> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Boolean {
        return !accessor.getBlockState().getValue(BlockStateProperties.ENABLED)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Boolean> = ByteBufCodecs.BOOL.cast()

    override fun getUid(): Identifier = MC_HOPPER_LOCK

    override fun getDefaultPriority(): Int = BlockNameProvider.INSTANCE.getDefaultPriority() + 10

    companion object {
        private val MC_HOPPER_LOCK = JadeProtocol.mc_id("hopper_lock")
    }
}
