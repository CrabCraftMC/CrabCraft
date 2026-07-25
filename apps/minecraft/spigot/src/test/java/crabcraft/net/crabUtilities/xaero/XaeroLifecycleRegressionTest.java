package crabcraft.net.crabUtilities.xaero;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class XaeroLifecycleRegressionTest {

    public static void main(String[] args) throws NoSuchMethodException {
        XaeroIntegration.class.getDeclaredConstructor(JavaPlugin.class, int.class);
        checkHandler("onPlayerJoin", PlayerJoinEvent.class);
        checkHandler("onPlayerRegisterChannel", PlayerRegisterChannelEvent.class);
        checkHandler("onPlayerChangedWorld", PlayerChangedWorldEvent.class);
        checkHandler("onPlayerPostRespawn", PlayerPostRespawnEvent.class);
        check(XaeroIntegration.JOIN_SEND_DELAYS_TICKS.equals(List.of(0L, 20L, 40L)),
                "join packets must be sent immediately, then after one and two seconds");
        checkChannels();
        checkEncoding();
        checkJoinRetryGuards();
    }

    private static void checkChannels() {
        check(XaeroIntegration.CHANNELS.equals(List.of(
                        "xaerominimap:main",
                        "xaeroworldmap:main")),
                "Xaero channel names changed");
        for (String channel : XaeroIntegration.CHANNELS) {
            check(XaeroIntegration.isXaeroChannel(channel),
                    "registered Xaero channel was not recognised");
        }
        check(!XaeroIntegration.isXaeroChannel("xaerolib:main"),
                "unrelated XaeroLib channel was accepted");
    }

    private static void checkEncoding() {
        check(Arrays.equals(
                        XaeroIntegration.encodeServerId(0x01020304),
                        new byte[]{0, 1, 2, 3, 4}),
                "positive server id was not encoded as a type byte and big-endian int");
        check(Arrays.equals(
                        XaeroIntegration.encodeServerId(-1),
                        new byte[]{0, -1, -1, -1, -1}),
                "negative server id was not encoded as a signed 32-bit value");
    }

    private static void checkJoinRetryGuards() {
        UUID expectedWorldId = new UUID(0L, 1L);
        Player joinedPlayer = player(true, expectedWorldId);

        check(!XaeroIntegration.canRetryJoinSend(null, joinedPlayer, expectedWorldId),
                "a disconnected session received a join retry");
        check(!XaeroIntegration.canRetryJoinSend(player(true, expectedWorldId), joinedPlayer, expectedWorldId),
                "a replacement session received an old join retry");

        Player offlinePlayer = player(false, expectedWorldId);
        check(!XaeroIntegration.canRetryJoinSend(offlinePlayer, offlinePlayer, expectedWorldId),
                "an offline player received a join retry");

        Player changedWorldPlayer = player(true, new UUID(0L, 2L));
        check(!XaeroIntegration.canRetryJoinSend(changedWorldPlayer, changedWorldPlayer, expectedWorldId),
                "a player in a different world received a stale join retry");
        check(XaeroIntegration.canRetryJoinSend(joinedPlayer, joinedPlayer, expectedWorldId),
                "the current online join session did not receive its retry");
    }

    private static Player player(boolean online, UUID worldId) {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return worldId;
                    throw new UnsupportedOperationException(method.getName());
                });

        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> online;
                    case "getWorld" -> world;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void checkHandler(String methodName, Class<?> eventType) throws NoSuchMethodException {
        EventHandler handler = XaeroIntegration.class
                .getDeclaredMethod(methodName, eventType)
                .getAnnotation(EventHandler.class);

        check(handler != null, methodName + " is not registered as an event handler");
        check(handler.priority() == EventPriority.MONITOR,
                methodName + " must run at MONITOR priority");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
