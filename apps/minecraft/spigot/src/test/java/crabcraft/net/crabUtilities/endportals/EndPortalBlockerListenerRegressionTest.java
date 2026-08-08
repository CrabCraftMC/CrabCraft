package crabcraft.net.crabUtilities.endportals;

import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPortalEnterEvent;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EndPortalBlockerListenerRegressionTest {

    private EndPortalBlockerListenerRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicBoolean preventEntry = new AtomicBoolean();
        EndPortalBlockerListener listener = new EndPortalBlockerListener(preventEntry::get);

        assertNotCancelled(listener, portalEvent(Player.class, PortalType.ENDER),
                "End portal entry was blocked while the tweak was disabled");

        preventEntry.set(true);
        assertCancelled(listener, portalEvent(Player.class, PortalType.ENDER),
                "player End portal entry was not blocked");
        assertCancelled(listener, portalEvent(Entity.class, PortalType.ENDER),
                "non-player End portal entry was not blocked");
        assertNotCancelled(listener, portalEvent(Player.class, PortalType.NETHER),
                "Nether portal entry was blocked");
        assertNotCancelled(listener, portalEvent(Player.class, PortalType.END_GATEWAY),
                "End gateway entry was blocked");

        preventEntry.set(false);
        assertNotCancelled(listener, portalEvent(Player.class, PortalType.ENDER),
                "End portal entry remained blocked after the live toggle was disabled");

        YamlConfiguration config = new YamlConfiguration();
        try (var input = EndPortalBlockerListenerRegressionTest.class.getClassLoader()
                .getResourceAsStream("modules/tweaks.yml")) {
            check(input != null, "bundled tweaks.yml is missing");
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        check(config.contains(EndPortalBlockerListener.CONFIG_PATH),
                "End portal blocker config is missing");
        check(!config.getBoolean(EndPortalBlockerListener.CONFIG_PATH, true),
                "End portal blocker is not disabled by default");
    }

    private static <T extends Entity> EntityPortalEnterEvent portalEvent(
            final Class<T> entityType,
            final PortalType portalType
    ) {
        return new EntityPortalEnterEvent(proxy(entityType), new Location(null, 0D, 0D, 0D), portalType);
    }

    private static void assertCancelled(
            final EndPortalBlockerListener listener,
            final EntityPortalEnterEvent event,
            final String message
    ) {
        listener.onEntityPortalEnter(event);
        check(event.isCancelled(), message);
    }

    private static void assertNotCancelled(
            final EndPortalBlockerListener listener,
            final EntityPortalEnterEvent event,
            final String message
    ) {
        listener.onEntityPortalEnter(event);
        check(!event.isCancelled(), message);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " proxy";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
