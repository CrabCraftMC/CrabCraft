package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.level.block.entity.CommandBlockEntity

enum class CommandBlockProvider : StreamServerDataProvider<BlockAccessor, String> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): String? {
        if (!accessor.getPlayer().canUseGameMasterBlocks()) return null
        val command = (accessor.getBlockEntity() as CommandBlockEntity).getCommandBlock().getCommand()
        return if (command.length > 40) command.substring(0, 37) + "..." else command
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, String> = ByteBufCodecs.STRING_UTF8.cast()

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("command_block")
    }
}
