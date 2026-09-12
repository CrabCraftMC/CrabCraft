package crabcraft.net.crabUtilities.xaero

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Proxy
import java.util.Arrays
import java.util.UUID

internal object XaeroLifecycleRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        XaeroIntegration::class.java.getDeclaredConstructor(JavaPlugin::class.java, Int::class.javaPrimitiveType)
        checkHandler("onPlayerJoin", PlayerJoinEvent::class.java)
        checkHandler("onPlayerRegisterChannel", PlayerRegisterChannelEvent::class.java)
        checkHandler("onPlayerChangedWorld", PlayerChangedWorldEvent::class.java)
        checkHandler("onPlayerPostRespawn", PlayerPostRespawnEvent::class.java)
        check(XaeroIntegration.JOIN_SEND_DELAYS_TICKS == listOf(0L, 20L, 40L), "join packets must be sent immediately, then after one and two seconds")
        checkChannels(); checkEncoding(); checkJoinRetryGuards()
    }
    private fun checkChannels() {
        check(XaeroIntegration.CHANNELS == listOf("xaerominimap:main", "xaeroworldmap:main"), "Xaero channel names changed")
        for (channel in XaeroIntegration.CHANNELS) check(XaeroIntegration.isXaeroChannel(channel), "registered Xaero channel was not recognised")
        check(!XaeroIntegration.isXaeroChannel("xaerolib:main"), "unrelated XaeroLib channel was accepted")
    }
    private fun checkEncoding() {
        check(Arrays.equals(XaeroIntegration.encodeServerId(0x01020304), byteArrayOf(0, 1, 2, 3, 4)), "positive server id was not encoded as a type byte and big-endian int")
        check(Arrays.equals(XaeroIntegration.encodeServerId(-1), byteArrayOf(0, -1, -1, -1, -1)), "negative server id was not encoded as a signed 32-bit value")
    }
    private fun checkJoinRetryGuards() {
        val expectedWorldId = UUID(0L, 1L)
        val joinedPlayer = player(true, expectedWorldId)
        check(!XaeroIntegration.canRetryJoinSend(null, joinedPlayer, expectedWorldId), "a disconnected session received a join retry")
        check(!XaeroIntegration.canRetryJoinSend(player(true, expectedWorldId), joinedPlayer, expectedWorldId), "a replacement session received an old join retry")
        val offlinePlayer = player(false, expectedWorldId)
        check(!XaeroIntegration.canRetryJoinSend(offlinePlayer, offlinePlayer, expectedWorldId), "an offline player received a join retry")
        val changedWorldPlayer = player(true, UUID(0L, 2L))
        check(!XaeroIntegration.canRetryJoinSend(changedWorldPlayer, changedWorldPlayer, expectedWorldId), "a player in a different world received a stale join retry")
        check(XaeroIntegration.canRetryJoinSend(joinedPlayer, joinedPlayer, expectedWorldId), "the current online join session did not receive its retry")
    }
    private fun player(online: Boolean, worldId: UUID): Player {
        val world = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
            if (method.name == "getUID") worldId else throw UnsupportedOperationException(method.name)
        } as World
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) { "isOnline" -> online; "getWorld" -> world; else -> throw UnsupportedOperationException(method.name) }
        } as Player
    }
    private fun checkHandler(methodName: String, eventType: Class<*>) {
        val handler = XaeroIntegration::class.java.getDeclaredMethod(methodName, eventType).getAnnotation(EventHandler::class.java)
        check(handler != null, "$methodName is not registered as an event handler")
        check(handler.priority == EventPriority.MONITOR, "$methodName must run at MONITOR priority")
    }

    private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
