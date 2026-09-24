package crabcraft.net.crabUtilities.jade.protocol

import com.mojang.datafixers.util.Either
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
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

/** Reflection accessors for NMS members that Leaf widens; names fall back to declared types. */
object WidenedFields {
    private val ARMADILLO_SCUTE_TIME =
        unreflectField(Armadillo::class.java, Int::class.javaPrimitiveType!!, "scuteTime")
    private val COPPER_GOLEM_NEXT_WEATHERING_TICK =
        unreflectField(CopperGolem::class.java, Long::class.javaPrimitiveType!!, "nextWeatheringTick")
    private val TADPOLE_TICKS_LEFT =
        unreflectMethod(Tadpole::class.java, "getTicksLeftUntilAdult", Int::class.javaPrimitiveType!!)
    private val LOOT_POOL_ENTRIES = unreflectField(LootPool::class.java, List::class.java, "entries")
    private val LOOT_TABLE_POOLS = unreflectField(LootTable::class.java, List::class.java, "pools")
    private val COMPOSITE_CHILDREN = unreflectField(CompositeEntryBase::class.java, List::class.java, "children")
    private val ENTRY_CONDITIONS = unreflectField(LootPoolEntryContainer::class.java, List::class.java, "conditions")
    private val NESTED_CONTENTS = unreflectField(NestedLootTable::class.java, Either::class.java, "contents")
    private val COMPOSITE_CONDITION_TERMS =
        unreflectField(CompositeLootItemCondition::class.java, List::class.java, "terms")

    @JvmStatic fun scuteTime(armadillo: Armadillo): Int = read(ARMADILLO_SCUTE_TIME, armadillo)

    @JvmStatic fun nextWeatheringTick(golem: CopperGolem): Long = read(COPPER_GOLEM_NEXT_WEATHERING_TICK, golem)

    @JvmStatic fun ticksLeftUntilAdult(tadpole: Tadpole): Int = read(TADPOLE_TICKS_LEFT, tadpole)

    @JvmStatic fun entries(pool: LootPool): List<LootPoolEntryContainer> = read(LOOT_POOL_ENTRIES, pool)

    @JvmStatic fun pools(table: LootTable): List<LootPool> = read(LOOT_TABLE_POOLS, table)

    @JvmStatic fun children(entry: CompositeEntryBase): List<LootPoolEntryContainer> = read(COMPOSITE_CHILDREN, entry)

    @JvmStatic fun conditions(entry: LootPoolEntryContainer): List<LootItemCondition> = read(ENTRY_CONDITIONS, entry)

    @JvmStatic
    fun contents(nested: NestedLootTable): Either<ResourceKey<LootTable>, LootTable> = read(NESTED_CONTENTS, nested)

    @JvmStatic
    fun terms(condition: CompositeLootItemCondition): List<LootItemCondition> =
        read(COMPOSITE_CONDITION_TERMS, condition)

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(handle: MethodHandle, target: Any): T =
        try {
            handle.invoke(target) as T
        } catch (t: Throwable) {
            throw rethrow(t)
        }

    private fun unreflectField(owner: Class<*>, type: Class<*>, preferredName: String): MethodHandle {
        val field =
            findField(owner, type, preferredName)
                ?: throw IllegalStateException(
                    "Cannot find field of type ${type.simpleName} on ${owner.name} (looked for: $preferredName)"
                )
        field.isAccessible = true
        return try {
            MethodHandles.lookup().unreflectGetter(field)
        } catch (e: IllegalAccessException) {
            throw IllegalStateException("Cannot unreflect getter for ${owner.name}#${field.name}", e)
        }
    }

    private fun findField(owner: Class<*>, type: Class<*>, preferredName: String): Field? {
        var current: Class<*>? = owner
        while (current != null && current != Any::class.java) {
            try {
                val field = current.getDeclaredField(preferredName)
                if (type.isAssignableFrom(field.type)) return field
            } catch (_: NoSuchFieldException) {}
            for (field in current.declaredFields) if (type.isAssignableFrom(field.type)) return field
            current = current.superclass
        }
        return null
    }

    private fun unreflectMethod(
        owner: Class<*>,
        name: String,
        returnType: Class<*>,
        vararg params: Class<*>,
    ): MethodHandle {
        try {
            val method = owner.getDeclaredMethod(name, *params)
            method.isAccessible = true
            return MethodHandles.lookup().unreflect(method)
        } catch (e: NoSuchMethodException) {
            try {
                return MethodHandles.lookup()
                    .findVirtual(owner, name, MethodType.methodType(returnType, params.toList()))
            } catch (ex: ReflectiveOperationException) {
                throw IllegalStateException("Cannot find method ${owner.name}#$name", ex)
            }
        } catch (e: IllegalAccessException) {
            throw IllegalStateException("Cannot unreflect ${owner.name}#$name", e)
        }
    }

    private fun rethrow(t: Throwable): RuntimeException =
        when (t) {
            is RuntimeException -> t
            is Error -> throw t
            else -> RuntimeException(t)
        }
}
