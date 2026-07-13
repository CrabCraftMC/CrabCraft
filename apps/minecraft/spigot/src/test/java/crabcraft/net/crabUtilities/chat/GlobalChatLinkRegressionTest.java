package crabcraft.net.crabUtilities.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class GlobalChatLinkRegressionTest {

    public static void main(String[] args) {
        String text = "See https://example.com/docs?q=chat, HTTP://example.org/path. "
                + "ftp://example.net https://[bad";
        Component rendered = SafeChatMiniMessage.deserialize(text);

        check(PlainTextComponentSerializer.plainText().serialize(rendered).equals(text),
                "plain URLs changed the visible message");
        for (Component child : rendered.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            check(child.clickEvent() == null, "plain URL became an interactive click target");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
