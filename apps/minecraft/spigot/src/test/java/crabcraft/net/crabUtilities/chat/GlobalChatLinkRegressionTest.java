package crabcraft.net.crabUtilities.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

final class GlobalChatLinkRegressionTest {

    public static void main(String[] args) {
        String text = "See https://example.com/docs?q=chat, HTTP://example.org/path. "
                + "ftp://example.net https://[bad";
        Component mention = Component.text("@Alex")
                .clickEvent(ClickEvent.suggestCommand("/msg Alex "));
        Component input = mention.append(Component.space()).append(Component.text(text));

        Component linked = GlobalChatService.linkifyUrls(input);
        List<ClickEvent> clicks = clickEvents(linked);

        check(PlainTextComponentSerializer.plainText().serialize(linked)
                        .equals("@Alex " + text),
                "linkification changed the visible message");
        check(clicks.size() == 3, "expected one mention and two URL click events");
        check(clicks.get(0).action() == ClickEvent.Action.SUGGEST_COMMAND,
                "existing mention click event was not preserved");
        checkOpenUrl(clicks.get(1), "https://example.com/docs?q=chat");
        checkOpenUrl(clicks.get(2), "HTTP://example.org/path");
    }

    private static List<ClickEvent> clickEvents(Component component) {
        List<ClickEvent> events = new ArrayList<>();
        for (Component child : component.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (child.clickEvent() != null) events.add(child.clickEvent());
        }
        return events;
    }

    private static void checkOpenUrl(ClickEvent event, String expectedUrl) {
        check(event.action() == ClickEvent.Action.OPEN_URL, "URL did not use OPEN_URL");
        check(event.payload() instanceof ClickEvent.Payload.Text payload
                        && payload.value().equals(expectedUrl),
                "URL included trailing punctuation or changed case");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
