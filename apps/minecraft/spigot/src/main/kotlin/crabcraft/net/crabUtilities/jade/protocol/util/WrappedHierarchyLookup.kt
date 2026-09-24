package crabcraft.net.crabUtilities.jade.protocol.util

import com.google.common.collect.ImmutableList
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider
import java.util.function.Function
import java.util.stream.Stream
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import org.apache.commons.lang3.tuple.Pair

open class WrappedHierarchyLookup<T : JadeProvider> : HierarchyLookup<T>(Any::class.java) {
    @JvmField val overrides: MutableList<Pair<IHierarchyLookup<T>, Function<Accessor<*>, Any?>>> = ArrayList()
    private var empty = true

    open fun wrappedGet(accessor: Accessor<*>): List<T> {
        val set = LinkedHashSet<T>()
        for (override in overrides) {
            val target = override.right.apply(accessor)
            if (target != null) set.addAll(override.left.get(target))
        }
        set.addAll(get(accessor.getTarget()))
        return ImmutableList.sortedCopyOf(IHierarchyLookup.COMPARATOR, set)
    }

    override fun register(clazz: Class<*>, provider: T) {
        for (override in overrides) {
            if (override.left.isClassAcceptable(clazz)) {
                override.left.register(clazz, provider)
                empty = false
                return
            }
        }
        super.register(clazz, provider)
        empty = false
    }

    override fun isClassAcceptable(clazz: Class<*>): Boolean =
        overrides.any { it.left.isClassAcceptable(clazz) } || super.isClassAcceptable(clazz)

    override fun invalidate() {
        overrides.forEach { it.left.invalidate() }
        super.invalidate()
    }

    override fun loadComplete(priorityStore: PriorityStore<Identifier, JadeProvider>) {
        overrides.forEach { it.left.loadComplete(priorityStore) }
        super.loadComplete(priorityStore)
    }

    override fun isEmpty(): Boolean = empty

    override fun entries(): Stream<Map.Entry<Class<*>, Collection<T>>> {
        var stream = super.entries()
        for (override in overrides) stream = Stream.concat(stream, override.left.entries())
        return stream
    }

    companion object {
        @JvmStatic
        fun <T : JadeProvider> forAccessor(): WrappedHierarchyLookup<T> =
            WrappedHierarchyLookup<T>().apply {
                overrides.add(
                    Pair.of(
                        HierarchyLookup<T>(Block::class.java),
                        Function { accessor ->
                            (accessor as? BlockAccessor)?.getBlock()
                        },
                    )
                )
            }
    }
}
