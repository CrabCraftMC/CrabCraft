package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.StreamServerDataProvider
import net.minecraft.core.component.DataComponents
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.Nameable
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType

enum class BlockNameProvider : StreamServerDataProvider<BlockAccessor, Component> {
    INSTANCE;

    override fun streamData(accessor: BlockAccessor): Component? {
        val blockEntity = accessor.getBlockEntity()
        val nameable = blockEntity as? Nameable ?: return null
        if (
            nameable is ChestBlockEntity &&
                accessor.getBlock() is ChestBlock &&
                accessor.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE
        ) {
            val menuProvider = accessor.getBlockState().getMenuProvider(accessor.getLevel(), accessor.getPosition())
            if (menuProvider != null) {
                val name = menuProvider.getDisplayName()
                val contents = name.getContents()
                if (contents !is TranslatableContents || contents.getKey() != "container.chestDouble") {
                    return name
                }
            }
        } else if (nameable.hasCustomName()) {
            return nameable.getDisplayName()
        }
        return blockEntity!!.components().get(DataComponents.ITEM_NAME)
    }

    override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, Component> = ComponentSerialization.STREAM_CODEC

    override fun getUid() = UID

    override fun getDefaultPriority(): Int = -10100

    companion object {
        private val UID = JadeProtocol.id("object_name")
    }
}
