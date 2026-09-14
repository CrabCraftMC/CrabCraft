package crabcraft.net.crabUtilities.jade.protocol.util

import com.google.common.collect.ImmutableList
import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor
import crabcraft.net.crabUtilities.jade.protocol.accessor.BlockAccessor
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import org.apache.commons.lang3.tuple.Pair
import java.util.function.Function
import java.util.stream.Stream

open class WrappedHierarchyLookup<T : JadeProvider> : HierarchyLookup<T>(Any::class.java) {
    @JvmField
    val overrides: MutableList<Pair<IHierarchyLookup<T>, Function<Accessor<*>, Any?>>> = ArrayList()
    private var empty = true

    open fun wrappedGet(accessor: Accessor<*>): List<T> {
        val set = LinkedHashSet<T>()
        for (override in overrides) {
            val obj = override.right.apply(accessor)
            if (obj != null) set.addAll(override.left.get(obj))
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

    override fun isClassAcceptable(clazz: Class<*>): Boolean {
        for (override in overrides) {
            if (override.left.isClassAcceptable(clazz)) return true
        }
        return super.isClassAcceptable(clazz)
    }

    override fun invalidate() {
        for (override in overrides) override.left.invalidate()
        super.invalidate()
    }

    override fun loadComplete(priorityStore: PriorityStore<Identifier, JadeProvider>) {
        for (override in overrides) override.left.loadComplete(priorityStore)
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
        fun <T : JadeProvider> forAccessor(): WrappedHierarchyLookup<T> {
            val lookup = WrappedHierarchyLookup<T>()
            lookup.overrides.add(Pair.of(
                HierarchyLookup<T>(Block::class.java),
                Function<Accessor<*>, Any?> { accessor ->
                    if (accessor is BlockAccessor) accessor.getBlock() else null
                }
            ))
            return lookup
        }
    }
}
