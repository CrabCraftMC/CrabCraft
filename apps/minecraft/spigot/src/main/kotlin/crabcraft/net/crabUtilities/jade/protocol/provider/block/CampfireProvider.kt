package crabcraft.net.crabUtilities.jade.protocol.provider.block

import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerExtensionProvider
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup

enum class CampfireProvider : ServerExtensionProvider<ItemStack> {
    INSTANCE;

    override fun getGroups(request: Accessor<*>): List<ViewGroup<ItemStack>>? {
        val campfire = request.getTarget()
        if (campfire is CampfireBlockEntity) {
            val list = mutableListOf<ItemStack>()
            for (i in campfire.cookingTime.indices) {
                var stack = campfire.items[i]
                if (stack.isEmpty) {
                    continue
                }
                stack = stack.copy()
                val time = campfire.cookingTime[i] - campfire.cookingProgress[i]
                val customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .update { tag -> tag.putInt("jade:cooking", time) }
                stack.set(DataComponents.CUSTOM_DATA, customData)
                list.add(stack)
            }
            return listOf(ViewGroup(list))
        }
        return null
    }

    override fun getUid(): Identifier = MC_CAMPFIRE

    companion object {
        private val MC_CAMPFIRE = JadeProtocol.mc_id("campfire")
    }
}
