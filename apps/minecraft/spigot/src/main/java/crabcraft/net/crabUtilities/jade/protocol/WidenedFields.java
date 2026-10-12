package crabcraft.net.crabUtilities.jade.protocol;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Reflection accessors for vanilla NMS fields/methods Leaf patches to public.
 * In a plugin we can't apply those patches, so we read them reflectively.
 *
 * <p>Field lookups are done by declared type rather than by name so they survive
 * minor mapping rotations within a Paper version range.
 */
public final class WidenedFields {

    private static final MethodHandle ARMADILLO_SCUTE_TIME = unreflectField(Armadillo.class, int.class, "scuteTime");
    private static final MethodHandle COPPER_GOLEM_NEXT_WEATHERING_TICK = unreflectField(CopperGolem.class, long.class, "nextWeatheringTick");
    private static final MethodHandle TADPOLE_TICKS_LEFT = unreflectMethod(Tadpole.class, "getTicksLeftUntilAdult", int.class);
    private static final MethodHandle LOOT_POOL_ENTRIES = unreflectField(LootPool.class, List.class, "entries");
    private static final MethodHandle LOOT_POOL_CONDITION = unreflectField(LootPool.class, Optional.class, "condition");
    private static final MethodHandle LOOT_TABLE_POOLS = unreflectField(LootTable.class, List.class, "pools");
    private static final MethodHandle COMPOSITE_CHILDREN = unreflectField(CompositeEntryBase.class, List.class, "children");
    private static final MethodHandle ENTRY_CONDITION = unreflectField(LootPoolEntryContainer.class, Optional.class, "condition");
    private static final MethodHandle NESTED_CONTENTS = unreflectField(NestedLootTable.class, HolderSet.class, "value");
    private static final MethodHandle COMPOSITE_CONDITION_TERMS = unreflectField(CompositeLootItemCondition.class, HolderSet.class, "terms");

    private WidenedFields() {
    }

    public static int scuteTime(Armadillo armadillo) {
        try {
            return (int) ARMADILLO_SCUTE_TIME.invoke(armadillo);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long nextWeatheringTick(CopperGolem golem) {
        try {
            return (long) COPPER_GOLEM_NEXT_WEATHERING_TICK.invoke(golem);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int ticksLeftUntilAdult(Tadpole tadpole) {
        try {
            return (int) TADPOLE_TICKS_LEFT.invoke(tadpole);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<LootPoolEntryContainer> entries(LootPool pool) {
        try {
            return (List<LootPoolEntryContainer>) LOOT_POOL_ENTRIES.invoke(pool);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<LootPool> pools(LootTable table) {
        try {
            return (List<LootPool>) LOOT_TABLE_POOLS.invoke(table);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<LootPoolEntryContainer> children(CompositeEntryBase entry) {
        try {
            return (List<LootPoolEntryContainer>) COMPOSITE_CHILDREN.invoke(entry);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static Optional<Holder<LootItemCondition>> condition(LootPoolEntryContainer entry) {
        try {
            return (Optional<Holder<LootItemCondition>>) ENTRY_CONDITION.invoke(entry);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static Optional<Holder<LootItemCondition>> condition(LootPool pool) {
        try {
            return (Optional<Holder<LootItemCondition>>) LOOT_POOL_CONDITION.invoke(pool);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static HolderSet<LootTable> contents(NestedLootTable nested) {
        try {
            return (HolderSet<LootTable>) NESTED_CONTENTS.invoke(nested);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    public static HolderSet<LootItemCondition> terms(CompositeLootItemCondition condition) {
        try {
            return (HolderSet<LootItemCondition>) COMPOSITE_CONDITION_TERMS.invoke(condition);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static MethodHandle unreflectField(Class<?> owner, Class<?> type, String preferredName) {
        Field field = findField(owner, type, preferredName);
        if (field == null) {
            throw new IllegalStateException("Cannot find field of type " + type.getSimpleName()
                + " on " + owner.getName() + " (looked for: " + preferredName + ")");
        }
        field.setAccessible(true);
        try {
            return MethodHandles.lookup().unreflectGetter(field);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot unreflect getter for " + owner.getName() + "#" + field.getName(), e);
        }
    }

    private static Field findField(Class<?> owner, Class<?> type, String preferredName) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(preferredName);
                if (type.isAssignableFrom(f.getType())) return f;
            } catch (NoSuchFieldException ignored) {
            }
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) return f;
            }
        }
        return null;
    }

    private static MethodHandle unreflectMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return MethodHandles.lookup().unreflect(m);
        } catch (NoSuchMethodException e) {
            try {
                return MethodHandles.lookup().findVirtual(owner, name, MethodType.methodType(returnType, params));
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                throw new IllegalStateException("Cannot find method " + owner.getName() + "#" + name, ex);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot unreflect " + owner.getName() + "#" + name, e);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }
}
