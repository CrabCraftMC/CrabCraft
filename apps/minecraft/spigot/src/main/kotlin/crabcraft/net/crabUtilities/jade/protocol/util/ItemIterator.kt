package crabcraft.net.crabUtilities.jade.protocol.util

import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function
import java.util.stream.IntStream
import java.util.stream.Stream

abstract class ItemIterator<T> protected constructor(
    @JvmField protected val containerFinder: Function<Any?, T?>,
    @JvmField protected val fromIndex: Int
) {
    @JvmField protected var finished = false
    @JvmField protected var currentIndex = fromIndex
    @JvmField protected var progress = 0f

    open fun find(target: Any?): T? = containerFinder.apply(target)

    fun isFinished(): Boolean = finished

    open fun getVersion(container: T): Long = version.getAndIncrement()

    abstract fun populate(container: T): Stream<ItemStack>

    protected abstract fun getSlotCount(container: T): Int

    open fun reset() {
        currentIndex = fromIndex
        finished = false
    }

    open fun afterPopulate(container: T, count: Int) {
        currentIndex += count
        if (count == 0 || currentIndex >= 10000) finished = true
        progress = (currentIndex - fromIndex).toFloat() / (getSlotCount(container) - fromIndex)
    }

    open fun getCollectingProgress(): Float = Float.NaN

    abstract class SlottedItemIterator<T>(containerFinder: Function<Any?, T?>, fromIndex: Int) :
        ItemIterator<T>(containerFinder, fromIndex) {
        protected abstract fun getItemInSlot(container: T, slot: Int): ItemStack

        override fun populate(container: T): Stream<ItemStack> {
            val slotCount = getSlotCount(container)
            var toIndex = currentIndex + ItemCollector.MAX_SIZE * 2
            if (toIndex >= slotCount) {
                toIndex = slotCount
                finished = true
            }
            return IntStream.range(currentIndex, toIndex).mapToObj { getItemInSlot(container, it) }
        }

        override fun getCollectingProgress(): Float = progress
    }

    open class ContainerItemIterator(containerFinder: Function<Any?, Container?>, fromIndex: Int) :
        SlottedItemIterator<Container>(containerFinder, fromIndex) {
        constructor(fromIndex: Int) : this(Function { Container::class.java.cast(it) }, fromIndex)

        override fun getSlotCount(container: Container): Int = container.containerSize

        override fun getItemInSlot(container: Container, slot: Int): ItemStack = container.getItem(slot)
    }

    companion object {
        @JvmField
        val version = AtomicLong()
    }
}
