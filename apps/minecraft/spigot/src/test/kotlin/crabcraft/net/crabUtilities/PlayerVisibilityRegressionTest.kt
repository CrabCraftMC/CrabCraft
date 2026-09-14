package crabcraft.net.crabUtilities

import org.bukkit.entity.Player
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.UUID

object PlayerVisibilityRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val visible = player("VisiblePlayer", emptySet())
        val hidden = player("HiddenPlayer", emptySet())
        val viewer = player("Viewer", setOf(hidden.getUniqueId()))

        val filtered = PlayerVisibility.visibleTo(viewer, listOf(visible, hidden))

        check(filtered == listOf(visible),
            "viewer-specific visibility did not remove the hidden player")
    }

    private fun player(name: String, hidden: Set<UUID>): Player {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8))
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getName" -> name
                "getUniqueId" -> uuid
                "canSee" -> !hidden.contains((args!![0] as Player).getUniqueId())
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

    private fun defaultValue(type: Class<*>): Any? {
        if (!type.isPrimitive) return null
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0F
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> throw IllegalArgumentException("Unsupported primitive: $type")
        }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
