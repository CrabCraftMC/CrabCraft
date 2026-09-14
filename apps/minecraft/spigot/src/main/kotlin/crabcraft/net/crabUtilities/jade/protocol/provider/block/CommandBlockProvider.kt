package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.entity.CommandBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider

enum class CommandBlockProvider : StreamServerDataProvider<BlockAccessor, String> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): String? {
        if (!accessor.getPlayer().canUseGameMasterBlocks()) {
            return null
        }
        var command = (accessor.getBlockEntity() as CommandBlockEntity).commandBlock.command
        if (command.length > 40) {
            command = command.substring(0, 37) + "..."
        }
        return command
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, String> = ByteBufCodecs.STRING_UTF8.cast()

    override fun getUid(): Identifier = MC_COMMAND_BLOCK

    companion object {
        private val MC_COMMAND_BLOCK = JadeProtocol.mc_id("command_block")
    }
}
