package crabcraft.net.crabUtilities

import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.bukkit.entity.Player

object PlayerVisibilityRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val visible = player("VisiblePlayer", emptySet())
        val hidden = player("HiddenPlayer", emptySet())
        val viewer = player("Viewer", setOf(hidden.uniqueId))
        check(
            PlayerVisibility.visibleTo(viewer, listOf(visible, hidden)) == listOf(visible),
            "viewer-specific visibility did not remove the hidden player",
        )
    }

    private fun player(name: String, hidden: Set<UUID>): Player {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8))
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args
            ->
            when (method.name) {
                "getName" -> name
                "getUniqueId" -> uuid
                "canSee" -> (args!![0] as Player).uniqueId !in hidden
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> if (!type.isPrimitive) null else throw IllegalArgumentException("Unsupported primitive: $type")
        }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
