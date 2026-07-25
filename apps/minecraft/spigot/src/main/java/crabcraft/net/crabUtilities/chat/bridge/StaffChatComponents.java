package crabcraft.net.crabUtilities.chat.bridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

final class StaffChatComponents {

    private static final Pattern PREFIX = Pattern.compile("^# ?");
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private StaffChatComponents() {}

    static boolean hasPrefix(Component message) {
        return PLAIN.serialize(message).startsWith("#");
    }

    static Component removePrefix(Component message) {
        return message.replaceText(config -> config
                .match(PREFIX)
                .replacement(Component.empty())
                .times(1));
    }

    static boolean isEmpty(Component message) {
        return PLAIN.serialize(message).isBlank();
    }
}
