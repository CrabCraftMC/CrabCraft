package crabcraft.net.crabUtilities.jade.protocol.tool

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState

open class ShearsToolHandler {
    private val tools: List<ItemStack> = listOf(Items.SHEARS.defaultInstance)

    open fun test(state: BlockState): ItemStack {
        for (toolItem in tools) {
            if (toolItem.isCorrectToolForDrops(state)) {
                return toolItem
            }
            val tool = toolItem.get(DataComponents.TOOL)
            if (tool != null && tool.getMiningSpeed(state) > tool.defaultMiningSpeed()) {
                return toolItem
            }
        }
        return ItemStack.EMPTY
    }

    companion object {
        private val INSTANCE = ShearsToolHandler()

        @JvmStatic
        fun getInstance(): ShearsToolHandler = INSTANCE
    }
}
