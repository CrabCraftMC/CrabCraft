package crabcraft.net.crabUtilities.jade.protocol.provider.block

import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.provider.ServerExtensionProvider
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.entity.CampfireBlockEntity

enum class CampfireProvider : ServerExtensionProvider<ItemStack> {
    INSTANCE;

    override fun getGroups(request: Accessor<*>): List<ViewGroup<ItemStack>>? {
        val campfire = request.getTarget() as? CampfireBlockEntity ?: return null
        val items = ArrayList<ItemStack>()
        for (i in campfire.cookingTime.indices) {
            val original = campfire.getItems()[i]
            if (original.isEmpty()) continue
            val stack = original.copy()
            val time = campfire.cookingTime[i] - campfire.cookingProgress[i]
            val customData =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).update {
                    it.putInt("jade:cooking", time)
                }
            stack.set(DataComponents.CUSTOM_DATA, customData)
            items.add(stack)
        }
        return listOf(ViewGroup(items))
    }

    override fun getUid() = UID

    companion object {
        private val UID = JadeProtocol.mc_id("campfire")
    }
}
