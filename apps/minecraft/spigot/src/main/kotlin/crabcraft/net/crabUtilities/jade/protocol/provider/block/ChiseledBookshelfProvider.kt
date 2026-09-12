package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChiseledBookShelfBlock
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ItemStorageProvider
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.world.level.block.CampfireBlock.FACING

enum class ChiseledBookshelfProvider : StreamServerDataProvider<BlockAccessor, ItemStack> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): ItemStack? {
        val slot = (accessor.getBlock() as ChiseledBookShelfBlock)
            .getHitSlot(accessor.getHitResult(), accessor.getBlockState().getValue(FACING)).orElse(-1)
        if (slot == -1) {
            return null
        }
        return (accessor.getBlockEntity() as ChiseledBookShelfBlockEntity).getItem(slot)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, ItemStack> = ItemStack.OPTIONAL_STREAM_CODEC

    override fun getUid(): Identifier = MC_CHISELED_BOOKSHELF

    override fun getDefaultPriority(): Int = ItemStorageProvider.getBlock().getDefaultPriority() + 1

    companion object {
        private val MC_CHISELED_BOOKSHELF = JadeProtocol.mc_id("shelf")
    }
}
