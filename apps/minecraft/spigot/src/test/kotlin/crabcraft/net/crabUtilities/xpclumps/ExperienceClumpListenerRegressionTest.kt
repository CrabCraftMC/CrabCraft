package crabcraft.net.crabUtilities.xpclumps

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.bukkit.entity.Entity
import org.bukkit.entity.ExperienceOrb

object ExperienceClumpListenerRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val destination = OrbStub(7)
        val nearby = OrbStub(5)
        val unrelatedRemoved = AtomicBoolean()
        val unrelated =
            proxy(Entity::class.java) { proxy, method, args ->
                when (method.name) {
                    "remove" -> {
                        unrelatedRemoved.set(true)
                        null
                    }
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "unrelated entity"
                    else -> defaultValue(method.returnType)
                }
            }
        destination.nearby = listOf(nearby.orb, unrelated, destination.orb)
        ExperienceClumpListener.mergeNearbyOrbs(destination.orb)
        check(destination.experience.get() == 12, "nearby XP was not added to the spawned orb")
        check(nearby.removed.get(), "absorbed XP orb was not removed")
        check(!destination.removed.get(), "spawned XP orb removed itself")
        check(!unrelatedRemoved.get(), "non-XP entity was removed")
        check(destination.searches.get() == 1, "nearby entities were queried more than once")
        check(destination.lastRadius == 3.0, "PVPClumps three-block search radius changed")
    }

    private class OrbStub(initialExperience: Int) {
        val experience = AtomicInteger(initialExperience)
        val removed = AtomicBoolean()
        val searches = AtomicInteger()
        var nearby: List<Entity> = emptyList()
        var lastRadius = 0.0
        val orb =
            proxy(ExperienceOrb::class.java) { proxy, method, args ->
                when (method.name) {
                    "getExperience" -> experience.get()
                    "setExperience" -> {
                        experience.set(args!![0] as Int)
                        null
                    }
                    "getNearbyEntities" -> {
                        searches.incrementAndGet()
                        val x = args!![0] as Double
                        val y = args[1] as Double
                        val z = args[2] as Double
                        check(x == y && y == z, "XP clump search is not symmetrical")
                        lastRadius = x
                        nearby
                    }
                    "remove" -> {
                        removed.set(true)
                        null
                    }
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "XP orb"
                    else -> defaultValue(method.returnType)
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Char::class.javaPrimitiveType -> '\u0000'
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            else -> if (type.isPrimitive) 0.0 else null
        }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
