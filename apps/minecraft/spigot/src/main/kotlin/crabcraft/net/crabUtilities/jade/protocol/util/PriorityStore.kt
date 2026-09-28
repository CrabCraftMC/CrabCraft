package crabcraft.net.crabUtilities.jade.protocol.util

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntMap
import java.util.function.Function
import java.util.function.ToIntFunction

open class PriorityStore<K : Any, V : Any>(
    private val defaultPriorityGetter: ToIntFunction<V>,
    private val keyGetter: Function<V, K>,
) {
    private val priorities: Object2IntMap<K> = Object2IntLinkedOpenHashMap()

    open fun put(provider: V) = put(provider, defaultPriorityGetter.applyAsInt(provider))

    open fun put(provider: V, priority: Int) {
        val uid = java.util.Objects.requireNonNull(keyGetter.apply(provider))
        priorities.put(uid, priority)
    }

    open fun byValue(value: V): Int = byKey(keyGetter.apply(value))

    open fun byKey(id: K): Int = priorities.getInt(id)
}
