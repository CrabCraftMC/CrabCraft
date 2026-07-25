package crabcraft.net.crabUtilities.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.function.Predicate;

final class ChatMiniMessageRegressionTest {
    public static void main(String[] args) {
        Component allowed = SafeChatMiniMessage.deserialize(
                "<red><bold>Bold</bold> <italic>italic</italic> "
                        + "<underlined>underlined</underlined> <strikethrough>struck</strikethrough></red>");
        check(plain(allowed).equals("Bold italic underlined struck"),
                "allowed visual tags changed the message text");
        check(anyStyle(allowed, style -> NamedTextColor.RED.equals(style.color())),
                "named colour tag was not applied");
        check(anyStyle(allowed, style -> style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE),
                "bold tag was not applied");
        check(anyStyle(allowed, style -> style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE),
                "italic tag was not applied");
        check(anyStyle(allowed, style -> style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE),
                "underline tag was not applied");
        check(anyStyle(allowed, style -> style.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE),
                "strikethrough tag was not applied");

        Component hex = SafeChatMiniMessage.deserialize("<#12abef>hex</#12abef>");
        check(anyStyle(hex, style -> style.color() != null && style.color().value() == 0x12ABEF),
                "hex colour tag was not applied");

        Component rejected = SafeChatMiniMessage.deserialize(
                "<click:run_command:'/op'><hover:show_text:'secret'><insertion:test>"
                        + "<font:minecraft:uniform><obfuscated><rainbow>unsafe</rainbow></obfuscated>"
                        + "</font></insertion></hover></click><newline>tail");
        check(!hasInteractiveStyle(rejected), "interactive MiniMessage tag was applied");
        check(!anyStyle(rejected,
                        style -> style.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE),
                "obfuscated formatting was applied");
        check(!plain(rejected).contains("\n"), "newline tag was applied");
        check(plain(rejected).contains("unsafe") && plain(rejected).contains("tail"),
                "rejected tags swallowed player text");

        String malformedInput = "<color:not-a-colour>still sent</color>";
        Component malformed = SafeChatMiniMessage.deserialize(malformedInput);
        check(plain(malformed).contains("still sent"), "malformed colour tag cancelled the message");
    }

    private static boolean hasInteractiveStyle(Component component) {
        return anyStyle(component, style -> style.clickEvent() != null
                || style.hoverEvent() != null
                || style.insertion() != null
                || style.font() != null);
    }

    private static boolean anyStyle(Component component, Predicate<Style> predicate) {
        if (predicate.test(component.style())) return true;
        for (Component child : component.children()) {
            if (anyStyle(child, predicate)) return true;
        }
        return false;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
