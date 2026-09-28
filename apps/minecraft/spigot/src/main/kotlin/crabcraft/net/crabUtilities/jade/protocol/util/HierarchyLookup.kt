package crabcraft.net.crabUtilities.jade.protocol.util

import com.google.common.base.Preconditions
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableListMultimap
import com.google.common.collect.ListMultimap
import com.google.common.collect.Sets
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider
import java.util.concurrent.ExecutionException
import java.util.stream.Stream
import net.minecraft.core.IdMapper
import net.minecraft.resources.Identifier

open class HierarchyLookup<T : JadeProvider>
@JvmOverloads
constructor(
    private val baseClass: Class<*>,
    private val singleton: Boolean = false,
) : IHierarchyLookup<T> {
    private val resultCache: Cache<Class<*>, List<T>> = CacheBuilder.newBuilder().build()
    @JvmField protected var idMapped = false
    @JvmField protected var idMapper: IdMapper<T>? = null
    private var objects: ListMultimap<Class<*>, T> = ArrayListMultimap.create()

    override fun idMapped() {
        idMapped = true
    }

    override fun idMapper(): IdMapper<T>? = idMapper

    override fun register(clazz: Class<*>, provider: T) {
        Preconditions.checkArgument(isClassAcceptable(clazz), "Class %s is not acceptable", clazz)
        java.util.Objects.requireNonNull(provider.getUid())
        JadeProtocol.priorities.put(provider)
        objects.put(clazz, provider)
    }

    override fun isClassAcceptable(clazz: Class<*>): Boolean = baseClass.isAssignableFrom(clazz)

    override fun get(clazz: Class<*>): List<T> {
        try {
            return resultCache.get(clazz) {
                val matches = ArrayList<T>()
                getInternal(clazz, matches)
                val sorted = ImmutableList.sortedCopyOf(IHierarchyLookup.COMPARATOR, matches)
                if (singleton && sorted.isNotEmpty()) ImmutableList.of(sorted.first()) else sorted
            }
        } catch (e: ExecutionException) {
            JadeBootstrap.LOGGER.error("HierarchyLookup error", e)
        }
        return emptyList()
    }

    private fun getInternal(clazz: Class<*>, list: MutableList<T>) {
        if (clazz != baseClass && clazz != Any::class.java) getInternal(clazz.superclass, list)
        list.addAll(objects.get(clazz))
    }

    override fun isEmpty(): Boolean = objects.isEmpty

    override fun entries(): Stream<Map.Entry<Class<*>, Collection<T>>> = objects.asMap().entries.stream().map { it }

    override fun invalidate() = resultCache.invalidateAll()

    override fun loadComplete(priorityStore: PriorityStore<Identifier, JadeProvider>) {
        objects.asMap().forEach { (_, list) ->
            if (list.size >= 2) {
                val seen = Sets.newHashSetWithExpectedSize<Identifier>(list.size)
                for (provider in list) {
                    if (provider.getUid() in seen) {
                        throw IllegalStateException(
                            String.format(
                                "Duplicate UID: %s for %s",
                                provider.getUid(),
                                list.filter { it.getUid() == provider.getUid() }.map { it.javaClass.name },
                            )
                        )
                    }
                    seen.add(provider.getUid())
                }
            }
        }
        objects =
            ImmutableListMultimap.builder<Class<*>, T>()
                .orderValuesBy(Comparator.comparingInt { priorityStore.byValue(it) })
                .putAll(objects)
                .build()
        if (idMapped) idMapper = createIdMapper()
    }
}
