package crabcraft.net.crabUtilities.jade.protocol.util

import com.google.common.collect.Streams
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider
import net.minecraft.core.IdMapper
import net.minecraft.resources.Identifier
import java.util.Comparator
import java.util.stream.Stream

interface IHierarchyLookup<T : JadeProvider> {
    fun cast(): IHierarchyLookup<out T> = this

    fun idMapped()

    fun idMapper(): IdMapper<T>?

    fun mappedIds(): List<Identifier> =
        Streams.stream(idMapper()!!).map { it.getUid() }.toList()

    fun register(clazz: Class<*>, provider: T)

    fun isClassAcceptable(clazz: Class<*>): Boolean

    fun get(obj: Any?): List<T> = if (obj == null) emptyList() else get(obj.javaClass)

    fun get(clazz: Class<*>): List<T>

    fun isEmpty(): Boolean

    fun entries(): Stream<Map.Entry<Class<*>, Collection<T>>>

    fun invalidate()

    fun loadComplete(priorityStore: PriorityStore<Identifier, JadeProvider>)

    fun createIdMapper(): IdMapper<T> {
        val list = entries().flatMap { it.value.stream() }.toList()
        val mapper = idMapper() ?: IdMapper(list.size)
        for (provider in list) {
            if (mapper.getId(provider) == IdMapper.DEFAULT) mapper.add(provider)
        }
        return mapper
    }

    companion object {
        @JvmField
        val COMPARATOR: Comparator<JadeProvider> = Comparator.comparingInt { JadeProtocol.priorities.byValue(it) }
    }
}
