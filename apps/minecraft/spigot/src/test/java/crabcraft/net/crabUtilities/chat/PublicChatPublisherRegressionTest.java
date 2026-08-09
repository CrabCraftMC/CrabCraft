package crabcraft.net.crabUtilities.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PublicChatPublisherRegressionTest {

    public static void main(String[] args) throws Exception {
        String rawMessage = "<#12abef>Hello</#12abef> <bold>CrabCraft</bold>";
        check(GlobalChatService.visiblePlainText(rawMessage).equals("Hello CrabCraft"),
                "public chat did not strip visual MiniMessage formatting");

        UUID uuid = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        Map<String, String> fields = GlobalChatService.publicChatFields(uuid, "MaxMoon", rawMessage);
        check(new ArrayList<>(fields.keySet()).equals(List.of("uuid", "username", "message")),
                "public chat stream fields changed");
        check(fields.get("uuid").equals(uuid.toString()), "public chat UUID changed");
        check(fields.get("username").equals("MaxMoon"), "public chat username changed");
        check(fields.get("message").equals("Hello CrabCraft"), "public chat message was not visible text");

        Method acceptedChat = GlobalChatListener.class
                .getDeclaredMethod("onAcceptedChat", AsyncChatEvent.class);
        EventHandler handler = acceptedChat.getAnnotation(EventHandler.class);
        check(handler != null, "accepted-chat handler lost its event handler annotation");
        check(handler.priority() == EventPriority.MONITOR,
                "global chat delivery or public publication can run before final moderation");
        check(handler.ignoreCancelled(),
                "cancelled chat could be delivered or reach the public stream");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
