package crabcraft.net.crabUtilities.endportals

import org.bukkit.Location
import org.bukkit.PortalType
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPortalEnterEvent
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

object EndPortalBlockerListenerRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val preventEntry = AtomicBoolean()
        val listener = EndPortalBlockerListener(java.util.function.BooleanSupplier { preventEntry.get() })
        assertNotCancelled(listener, portalEvent(Player::class.java, PortalType.ENDER), "End portal entry was blocked while the tweak was disabled")
        preventEntry.set(true)
        assertCancelled(listener, portalEvent(Player::class.java, PortalType.ENDER), "player End portal entry was not blocked")
        assertCancelled(listener, portalEvent(Entity::class.java, PortalType.ENDER), "non-player End portal entry was not blocked")
        assertNotCancelled(listener, portalEvent(Player::class.java, PortalType.NETHER), "Nether portal entry was blocked")
        assertNotCancelled(listener, portalEvent(Player::class.java, PortalType.END_GATEWAY), "End gateway entry was blocked")
        preventEntry.set(false)
        assertNotCancelled(listener, portalEvent(Player::class.java, PortalType.ENDER), "End portal entry remained blocked after the live toggle was disabled")
        val config = YamlConfiguration()
        EndPortalBlockerListenerRegressionTest::class.java.classLoader.getResourceAsStream("modules/tweaks.yml").use { input ->
            check(input != null, "bundled tweaks.yml is missing")
            config.loadFromString(String(input!!.readAllBytes(), StandardCharsets.UTF_8))
        }
        check(config.contains(EndPortalBlockerListener.CONFIG_PATH), "End portal blocker config is missing")
        check(!config.getBoolean(EndPortalBlockerListener.CONFIG_PATH, true), "End portal blocker is not disabled by default")
    }
    private fun <T : Entity> portalEvent(entityType: Class<T>, portalType: PortalType): EntityPortalEnterEvent =
        EntityPortalEnterEvent(proxy(entityType), Location(null, 0.0, 0.0, 0.0), portalType)
    private fun assertCancelled(listener: EndPortalBlockerListener, event: EntityPortalEnterEvent, message: String) {
        listener.onEntityPortalEnter(event); check(event.isCancelled, message)
    }
    private fun assertNotCancelled(listener: EndPortalBlockerListener, event: EntityPortalEnterEvent, message: String) {
        listener.onEntityPortalEnter(event); check(!event.isCancelled, message)
    }
    private fun <T> proxy(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> type.simpleName + " proxy"
            else -> defaultValue(method.returnType)
        }
    })

    private fun defaultValue(type: Class<*>): Any? {
        if (!type.isPrimitive) return null
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Char::class.javaPrimitiveType -> '\u0000'
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0F
            else -> 0.0
        }
    }

    private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
