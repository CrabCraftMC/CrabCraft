package crabcraft.net.crabUtilities.velocity.restrictedarea;

import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RestrictedAreaProxyRegressionTest {

    private RestrictedAreaProxyRegressionTest() {
    }

    public static void main(String[] args) {
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicBoolean hasPermission = new AtomicBoolean(false);
        RestrictedAreaProxyListener listener = new RestrictedAreaProxyListener(
                enabled::get,
                () -> "crabutilities.restricted-area.bypass");
        Player player = player(hasPermission);

        CommandExecuteEvent command = new CommandExecuteEvent(player, "msg somebody hello");
        listener.onCommand(command);
        check(!command.getResult().isAllowed(), "proxy command was not denied");

        PlayerChatEvent chat = new PlayerChatEvent(player, "hello");
        listener.onChat(chat);
        check(!chat.getResult().isAllowed(), "proxy chat was not denied");

        RegisteredServer initial = proxy(RegisteredServer.class);
        ServerPreConnectEvent initialConnect = new ServerPreConnectEvent(player, initial);
        listener.onServerPreConnect(initialConnect);
        check(initialConnect.getResult().isAllowed(), "initial backend connection was denied");

        ServerPreConnectEvent switchServer = new ServerPreConnectEvent(
                player, proxy(RegisteredServer.class), initial);
        listener.onServerPreConnect(switchServer);
        check(!switchServer.getResult().isAllowed(), "backend switch was not denied");

        hasPermission.set(true);
        CommandExecuteEvent permittedCommand = new CommandExecuteEvent(player, "msg somebody hello");
        listener.onCommand(permittedCommand);
        check(permittedCommand.getResult().isAllowed(), "permitted command was denied");

        hasPermission.set(false);
        enabled.set(false);
        CommandExecuteEvent disabledCommand = new CommandExecuteEvent(player, "msg somebody hello");
        listener.onCommand(disabledCommand);
        check(disabledCommand.getResult().isAllowed(), "disabled policy denied a command");
    }

    private static Player player(AtomicBoolean hasPermission) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> hasPermission.get();
                    case "getCurrentServer" -> Optional.empty();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
