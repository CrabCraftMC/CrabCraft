package crabcraft.net.crabUtilities.villagers

import com.destroystokyo.paper.entity.villager.Reputation
import com.destroystokyo.paper.entity.villager.ReputationType
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Villager

object SharedVillagerDiscountListenerRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        cureDiscountMatchesVanillaReputation()
        radiusIsSphericalAndExcludesSpectators()
    }

    private fun cureDiscountMatchesVanillaReputation() {
        val playerId = UUID.randomUUID()
        val original = Reputation()
        original.setReputation(ReputationType.MAJOR_POSITIVE, 12)
        original.setReputation(ReputationType.TRADING, 7)
        val saved = AtomicReference<Reputation>()
        val villager =
            proxy(Villager::class.java) { proxy, method, args ->
                when (method.name) {
                    "getReputation" -> original
                    "setReputation" -> {
                        check(playerId == args!![0], "discount was saved under the wrong player")
                        saved.set(args[1] as Reputation)
                        null
                    }
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> defaultValue(method.returnType)
                }
            }
        SharedVillagerDiscountListener.applyCureDiscount(villager, playerId)
        val result = saved.get()
        check(result != null, "updated cure reputation was not saved")
        check(
            result!!.getReputation(ReputationType.MAJOR_POSITIVE) == 20,
            "major-positive cure gossip does not match vanilla",
        )
        check(
            result.getReputation(ReputationType.MINOR_POSITIVE) == 25,
            "minor-positive cure gossip does not match vanilla",
        )
        check(result.getReputation(ReputationType.TRADING) == 7, "unrelated trading reputation was changed")
    }

    private fun radiusIsSphericalAndExcludesSpectators() {
        val queriedRadius = AtomicReference<Double>()
        val candidates = AtomicReference<Collection<Player>>()
        val world =
            proxy(World::class.java) { proxy, method, args ->
                when (method.name) {
                    "getNearbyPlayers" -> {
                        queriedRadius.set(args!![1] as Double)
                        candidates.get()
                    }
                    "equals" -> proxy === args!![0]
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> defaultValue(method.returnType)
                }
            }
        val origin = Location(world, 0.0, 0.0, 0.0)
        val inside = player(world, 3.0, 4.0, 0.0, GameMode.SURVIVAL)
        val cubeCorner = player(world, 4.0, 4.0, 0.0, GameMode.SURVIVAL)
        val spectator = player(world, 1.0, 0.0, 0.0, GameMode.SPECTATOR)
        candidates.set(listOf(inside, cubeCorner, spectator))
        val nearby = SharedVillagerDiscountListener.nearbyPlayers(world, origin, 5.0)
        check(queriedRadius.get() == 5.0, "configured radius was not used for the player query")
        check(nearby == listOf(inside), "radius was not a non-spectator 3D sphere")
    }

    private fun player(world: World, x: Double, y: Double, z: Double, gameMode: GameMode): Player =
        proxy(Player::class.java) { proxy, method, args ->
            when (method.name) {
                "getLocation" -> Location(world, x, y, z)
                "getGameMode" -> gameMode
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> defaultValue(method.returnType)
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
