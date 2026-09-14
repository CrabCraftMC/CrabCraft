package crabcraft.net.crabUtilities.jade.protocol.util

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider
import net.minecraft.core.IdMapper
import net.minecraft.resources.Identifier
import org.apache.commons.lang3.tuple.Pair
import java.util.concurrent.ExecutionException
import java.util.stream.Stream

open class PairHierarchyLookup<T : JadeProvider>(
    @JvmField val first: IHierarchyLookup<T>,
    @JvmField val second: IHierarchyLookup<T>
) : IHierarchyLookup<T> {
    private val mergedCache: Cache<Pair<Class<*>, Class<*>>, List<T>> = CacheBuilder.newBuilder().build()
    @JvmField
    protected var idMapped = false
    @JvmField
    protected var idMapper: IdMapper<T>? = null

    @Suppress("UNCHECKED_CAST")
    open fun <ANY> getMerged(first: Any, second: Any): List<ANY> {
        try {
            return mergedCache.get(Pair.of(first.javaClass, second.javaClass)) {
                val firstList = this.first.get(first)
                val secondList = this.second.get(second)
                when {
                    firstList.isEmpty() -> secondList
                    secondList.isEmpty() -> firstList
                    else -> ImmutableList.sortedCopyOf(IHierarchyLookup.COMPARATOR, Iterables.concat(firstList, secondList))
                }
            } as List<ANY>
        } catch (e: ExecutionException) {
            JadeBootstrap.LOGGER.error(e.toString())
        }
        return emptyList()
    }

    override fun idMapped() {
        idMapped = true
    }

    override fun idMapper(): IdMapper<T>? = idMapper

    override fun register(clazz: Class<*>, provider: T) {
        when {
            first.isClassAcceptable(clazz) -> first.register(clazz, provider)
            second.isClassAcceptable(clazz) -> second.register(clazz, provider)
            else -> throw IllegalArgumentException("Class $clazz is not acceptable")
        }
    }

    override fun isClassAcceptable(clazz: Class<*>): Boolean =
        first.isClassAcceptable(clazz) || second.isClassAcceptable(clazz)

    override fun get(clazz: Class<*>): List<T> {
        val result = first.get(clazz)
        return if (result.isEmpty()) second.get(clazz) else result
    }

    override fun isEmpty(): Boolean = first.isEmpty() && second.isEmpty()

    override fun entries(): Stream<Map.Entry<Class<*>, Collection<T>>> = Stream.concat(first.entries(), second.entries())

    override fun invalidate() {
        first.invalidate()
        second.invalidate()
        mergedCache.invalidateAll()
    }

    override fun loadComplete(priorityStore: PriorityStore<Identifier, JadeProvider>) {
        first.loadComplete(priorityStore)
        second.loadComplete(priorityStore)
        if (idMapped) idMapper = createIdMapper()
    }
}
