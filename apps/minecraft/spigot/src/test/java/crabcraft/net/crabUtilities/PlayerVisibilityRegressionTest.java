package crabcraft.net.crabUtilities;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PlayerVisibilityRegressionTest {

    private PlayerVisibilityRegressionTest() {
    }

    public static void main(String[] args) {
        Player visible = player("VisiblePlayer", Set.of());
        Player hidden = player("HiddenPlayer", Set.of());
        Player viewer = player("Viewer", Set.of(hidden.getUniqueId()));

        List<Player> filtered = PlayerVisibility.visibleTo(
                viewer, List.of(visible, hidden));

        check(filtered.equals(List.of(visible)),
                "viewer-specific visibility did not remove the hidden player");
    }

    private static Player player(String name, Set<UUID> hidden) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getUniqueId" -> uuid;
                    case "canSee" -> !hidden.contains(((Player) args[0]).getUniqueId());
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive: " + type);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
