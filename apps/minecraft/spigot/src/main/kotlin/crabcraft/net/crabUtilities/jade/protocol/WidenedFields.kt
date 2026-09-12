package crabcraft.net.crabUtilities.jade.protocol

import com.mojang.datafixers.util.Either
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.animal.armadillo.Armadillo
import net.minecraft.world.entity.animal.frog.Tadpole
import net.minecraft.world.entity.animal.golem.CopperGolem
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer
import net.minecraft.world.level.storage.loot.entries.NestedLootTable
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection accessors for vanilla NMS fields/methods Leaf patches to public.
 * Fields are found by declared type as a fallback when mapping names change.
 */
object WidenedFields {
    private val ARMADILLO_SCUTE_TIME: MethodHandle = unreflectField(Armadillo::class.java, Int::class.javaPrimitiveType!!, "scuteTime")
    private val COPPER_GOLEM_NEXT_WEATHERING_TICK: MethodHandle = unreflectField(CopperGolem::class.java, Long::class.javaPrimitiveType!!, "nextWeatheringTick")
    private val TADPOLE_TICKS_LEFT: MethodHandle = unreflectMethod(Tadpole::class.java, "getTicksLeftUntilAdult", Int::class.javaPrimitiveType!!)
    private val LOOT_POOL_ENTRIES: MethodHandle = unreflectField(LootPool::class.java, List::class.java, "entries")
    private val LOOT_TABLE_POOLS: MethodHandle = unreflectField(LootTable::class.java, List::class.java, "pools")
    private val COMPOSITE_CHILDREN: MethodHandle = unreflectField(CompositeEntryBase::class.java, List::class.java, "children")
    private val ENTRY_CONDITIONS: MethodHandle = unreflectField(LootPoolEntryContainer::class.java, List::class.java, "conditions")
    private val NESTED_CONTENTS: MethodHandle = unreflectField(NestedLootTable::class.java, Either::class.java, "contents")
    private val COMPOSITE_CONDITION_TERMS: MethodHandle = unreflectField(CompositeLootItemCondition::class.java, List::class.java, "terms")

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun scuteTime(armadillo: Armadillo): Int {
        try {
            return ARMADILLO_SCUTE_TIME.invoke(armadillo) as Int
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun nextWeatheringTick(golem: CopperGolem): Long {
        try {
            return COPPER_GOLEM_NEXT_WEATHERING_TICK.invoke(golem) as Long
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun ticksLeftUntilAdult(tadpole: Tadpole): Int {
        try {
            return TADPOLE_TICKS_LEFT.invoke(tadpole) as Int
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun entries(pool: LootPool): List<LootPoolEntryContainer> {
        try {
            return LOOT_POOL_ENTRIES.invoke(pool) as List<LootPoolEntryContainer>
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun pools(table: LootTable): List<LootPool> {
        try {
            return LOOT_TABLE_POOLS.invoke(table) as List<LootPool>
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun children(entry: CompositeEntryBase): List<LootPoolEntryContainer> {
        try {
            return COMPOSITE_CHILDREN.invoke(entry) as List<LootPoolEntryContainer>
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun conditions(entry: LootPoolEntryContainer): List<LootItemCondition> {
        try {
            return ENTRY_CONDITIONS.invoke(entry) as List<LootItemCondition>
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun contents(nested: NestedLootTable): Either<ResourceKey<LootTable>, LootTable> {
        try {
            return NESTED_CONTENTS.invoke(nested) as Either<ResourceKey<LootTable>, LootTable>
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun terms(condition: CompositeLootItemCondition): List<LootItemCondition> {
        try {
            return COMPOSITE_CONDITION_TERMS.invoke(condition) as List<LootItemCondition>
        } catch (t: Throwable) {
            throw rethrow(t)
        }
    }

    private fun unreflectField(owner: Class<*>, type: Class<*>, preferredName: String): MethodHandle {
        val field = findField(owner, type, preferredName)
            ?: throw IllegalStateException("Cannot find field of type " + type.simpleName + " on " + owner.name + " (looked for: " + preferredName + ")")
        field.isAccessible = true
        try {
            return MethodHandles.lookup().unreflectGetter(field)
        } catch (e: IllegalAccessException) {
            throw IllegalStateException("Cannot unreflect getter for " + owner.name + "#" + field.name, e)
        }
    }

    private fun findField(owner: Class<*>, type: Class<*>, preferredName: String): Field? {
        var current: Class<*>? = owner
        while (current != null && current != Any::class.java) {
            try {
                val field = current.getDeclaredField(preferredName)
                if (type.isAssignableFrom(field.type)) return field
            } catch (ignored: NoSuchFieldException) {
            }
            for (field in current.declaredFields) {
                if (type.isAssignableFrom(field.type)) return field
            }
            current = current.superclass
        }
        return null
    }

    private fun unreflectMethod(owner: Class<*>, name: String, returnType: Class<*>, vararg params: Class<*>): MethodHandle {
        try {
            val method = owner.getDeclaredMethod(name, *params)
            method.isAccessible = true
            return MethodHandles.lookup().unreflect(method)
        } catch (e: NoSuchMethodException) {
            try {
                return MethodHandles.lookup().findVirtual(owner, name, MethodType.methodType(returnType, params))
            } catch (ex: NoSuchMethodException) {
                throw IllegalStateException("Cannot find method " + owner.name + "#" + name, ex)
            } catch (ex: IllegalAccessException) {
                throw IllegalStateException("Cannot find method " + owner.name + "#" + name, ex)
            }
        } catch (e: IllegalAccessException) {
            throw IllegalStateException("Cannot unreflect " + owner.name + "#" + name, e)
        }
    }

    private fun rethrow(t: Throwable): RuntimeException {
        if (t is RuntimeException) return t
        if (t is Error) throw t
        return RuntimeException(t)
    }
}
