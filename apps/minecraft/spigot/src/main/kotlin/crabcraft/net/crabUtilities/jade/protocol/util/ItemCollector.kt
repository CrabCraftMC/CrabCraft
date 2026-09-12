package crabcraft.net.crabUtilities.jade.protocol.util

import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate

open class ItemCollector<T>(private val iterator: ItemIterator<T>?) {
    private val items = Object2IntLinkedOpenHashMap<ItemDefinition>()
    @JvmField var version = 0L
    @JvmField var lastTimeFinished = 0L
    @JvmField var lastTimeIsEmpty = false
    @JvmField var mergedResult: List<ViewGroup<ItemStack>>? = null

    open fun update(request: Accessor<*>): List<ViewGroup<ItemStack>>? {
        val iterator = iterator ?: return null
        val container = iterator.find(request.getTarget()) ?: return null
        val currentVersion = iterator.getVersion(container)
        val gameTime = request.getLevel().server.tickCount.toLong()
        val previousResult = mergedResult
        if (previousResult != null && iterator.isFinished()) {
            if (version == currentVersion) return previousResult // content not changed
            if (lastTimeFinished + 5 > gameTime) return previousResult // avoid update too frequently
            iterator.reset()
        }
        val count = AtomicInteger()
        iterator.populate(container).forEach { stack ->
            count.incrementAndGet()
            if (SHOWN.test(stack)) items.addTo(ItemDefinition(stack), stack.count)
        }
        iterator.afterPopulate(container, count.get())
        val currentResult = mergedResult
        if (currentResult != null && !iterator.isFinished()) {
            updateCollectingProgress(currentResult.first())
            return currentResult
        }
        val partialResult = items.object2IntEntrySet().stream().limit(MAX_SIZE.toLong()).map { entry ->
            entry.key.toStack(entry.intValue)
        }.toList()
        val groups = listOf(updateCollectingProgress(ViewGroup(partialResult)))
        if (iterator.isFinished()) {
            mergedResult = groups
            lastTimeIsEmpty = groups.first().views.isEmpty()
            version = currentVersion
            lastTimeFinished = gameTime
            items.clear()
        }
        return groups
    }

    protected open fun updateCollectingProgress(group: ViewGroup<ItemStack>): ViewGroup<ItemStack> {
        if (lastTimeIsEmpty && group.views.isEmpty()) return group
        val progress = iterator!!.getCollectingProgress()
        val data = group.getExtraData()
        if (progress.isNaN() || progress >= 1) data.remove("Collecting") else data.putFloat("Collecting", progress)
        return group
    }

    data class ItemDefinition(private val item: Item, private val components: DataComponentPatch) {
        constructor(stack: ItemStack) : this(stack.item, stack.componentsPatch)

        fun item(): Item = item

        fun components(): DataComponentPatch = components

        fun toStack(count: Int): ItemStack {
            val itemStack = ItemStack(item, count)
            itemStack.applyComponents(components)
            return itemStack
        }
    }

    companion object {
        const val MAX_SIZE = 54
        @JvmField val EMPTY: ItemCollector<*> = ItemCollector<Any>(null)
        private val IGNORED_TAG = CompoundTag().apply { putBoolean("__JadeClear", true) }
        private val SHOWN = Predicate<ItemStack> { stack ->
            when {
                stack.isEmpty -> false
                stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT).hideTooltip() -> false
                stack.hasNonDefault(DataComponents.CUSTOM_MODEL_DATA) || stack.hasNonDefault(DataComponents.ITEM_MODEL) ->
                    !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).matchedBy(IGNORED_TAG)
                else -> true
            }
        }
    }
}
