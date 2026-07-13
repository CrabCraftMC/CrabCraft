package crabcraft.net.crabUtilities.xaero;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

final class XaeroLifecycleRegressionTest {

    public static void main(String[] args) throws NoSuchMethodException {
        checkHandler("onPlayerJoin", PlayerJoinEvent.class);
        checkHandler("onPlayerChangedWorld", PlayerChangedWorldEvent.class);
        checkHandler("onPlayerPostRespawn", PlayerPostRespawnEvent.class);
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
