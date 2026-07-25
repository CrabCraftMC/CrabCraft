package crabcraft.net.crabUtilities.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses trusted nickname styling without enabling interactive MiniMessage tags. */
public final class NicknameComponentParser {

    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&[#]([0-9a-fA-F]{6})");
    private static final TagResolver SAFE_TAGS = TagResolver.builder()
            .resolver(StandardTags.color())
            .resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient())
            .resolver(StandardTags.rainbow())
            .resolver(StandardTags.reset())
            .build();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder().tags(SAFE_TAGS).build();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private NicknameComponentParser() {}

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        try {
            if (!MINI_MESSAGE.stripTags(raw).equals(raw)) {
                return MINI_MESSAGE.deserialize(raw);
            }
        } catch (Exception ignored) {
            // Fall through to the legacy parser.
        }
        String processed = convertAmpersandHex(raw.replace('§', '&'));
        try {
            return LEGACY.deserialize(processed);
        } catch (Exception ignored) {
            return Component.text(raw);
        }
    }

    public static String plain(String raw) {
        return raw == null ? null : PLAIN.serialize(parse(raw));
    }

    private static String convertAmpersandHex(String input) {
        StringBuffer result = new StringBuffer();
        Matcher matcher = AMP_HEX_PATTERN.matcher(input);
        while (matcher.find()) {
            String hex = matcher.group(1).toUpperCase(Locale.ROOT);
            String replacement = "&x&" + hex.charAt(0) + "&" + hex.charAt(1)
                    + "&" + hex.charAt(2) + "&" + hex.charAt(3)
                    + "&" + hex.charAt(4) + "&" + hex.charAt(5);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
