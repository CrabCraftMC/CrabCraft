package crabcraft.net.crabUtilities.tags;

import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.Set;

final class TagsDialogLifecycleRegressionTest {
    public static void main(String[] args) throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, values) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "isOnline" -> true;
                    default -> throw new AssertionError("Unexpected player access: " + method.getName());
                });
        TagsDialog dialog = new TagsDialog(null, ignored -> {});
        Method begin = TagsDialog.class.getDeclaredMethod("begin", Player.class);
        Method current = TagsDialog.class.getDeclaredMethod("current", Player.class, long.class);
        begin.setAccessible(true);
        current.setAccessible(true);
        long first = (long) begin.invoke(dialog, player);
        check((boolean) current.invoke(dialog, player, first), "new dialog was not active");
        long second = (long) begin.invoke(dialog, player);
        check(!(boolean) current.invoke(dialog, player, first), "old menu callback can replace a newer menu");
        check((boolean) current.invoke(dialog, player, second), "new menu generation was lost");
        dialog.onCommand(new PlayerCommandPreprocessEvent(player, "/halloween", Set.of()));
        check(!(boolean) current.invoke(dialog, player, second), "closed/back navigation can reopen after an acknowledgement");
        long third = (long) begin.invoke(dialog, player);
        dialog.onQuit(new PlayerQuitEvent(player, (Component) null));
        check(!(boolean) current.invoke(dialog, player, third), "quit retained an active dialog callback");
        long fourth = (long) begin.invoke(dialog, player);
        check(fourth != third, "rejoining reused an old callback generation");
        dialog.shutdown();
        check(!(boolean) current.invoke(dialog, player, fourth), "reload retained old dialog callbacks");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
