package crabcraft.net.crabUtilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a stored nickname into a styled {@link Component}, honouring
 * both MiniMessage-style nicks (e.g. {@code <#6dd5ed>} or gradients) and legacy
 * {@code &}-code nicks including hex ({@code &#RRGGBB}, {@code &x&R&R...} and
 * {@code §x§...}).
 *
 * <p>Shared by chat formatting and death/advancement rewriting so they all
 * render colours identically. Redis supplies the authoritative raw nicknames,
 * including offline players, without requiring EssentialsX.
 */
public final class NicknameComponentResolver {

    private static final Map<UUID, Component> NICKNAMES = new ConcurrentHashMap<>();
    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&[#]([0-9a-fA-F]{6})");

    /** Legacy serializer supporting &-codes and hex (both &#RRGGBB and &x&R&R&G&G&B&B). */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.reset()))
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private NicknameComponentResolver() {
    }

    /** Returns the cached styled nickname, or {@code null} when no nickname is set. */
    public static Component forPlayer(Player player) {
        return forUniqueId(player.getUniqueId());
    }

    /** Resolves cached nicknames for both online and offline players. */
    public static Component forUniqueId(UUID uuid) {
        return uuid == null ? null : NICKNAMES.get(uuid);
    }

    /** Returns the player's plain nickname, falling back to their account name. */
    public static String plainNicknameOrName(Player player) {
        String nickname = plain(forPlayer(player));
        return nickname == null ? player.getName() : nickname;
    }

    static void cacheNickname(UUID uuid, String raw) {
        Component nickname = fromRawNick(raw);
        if (nickname == null) {
            NICKNAMES.remove(uuid);
        } else {
            NICKNAMES.put(uuid, nickname);
        }
    }

    /**
     * Parses a raw stored nickname string into a styled component, or
     * {@code null} when the nick is missing/blank.
     */
    public static Component fromRawNick(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // Only enable formatting tags that are safe and useful in a nickname.
        try {
            if (!MINI_MESSAGE.stripTags(raw).equals(raw)) {
                return MINI_MESSAGE.deserialize(raw);
            }
        } catch (Exception ignored) {
            // Fall through to legacy parsing.
        }

        // Legacy parsing with hex support (&#RRGGBB, &x&..., §x§...).
        String processed = convertAmpersandHex(raw.replace('§', '&'));
        try {
            return LEGACY.deserialize(processed);
        } catch (Exception ex) {
            return Component.text(raw);
        }
    }

    private static String convertAmpersandHex(String input) {
        // Replace occurrences of &#RRGGBB with &x&R&R&G&G&B&B so the serializer can parse it.
        StringBuffer sb = new StringBuffer();
        Matcher m = AMP_HEX_PATTERN.matcher(input);
        while (m.find()) {
            String hex = m.group(1).toUpperCase(Locale.ROOT);
            String replacement = "&x&" + hex.charAt(0) + "&" + hex.charAt(1)
                    + "&" + hex.charAt(2) + "&" + hex.charAt(3)
                    + "&" + hex.charAt(4) + "&" + hex.charAt(5);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String plain(Component component) {
        if (component == null) {
            return null;
        }
        String plain = PLAIN.serialize(component).trim();
        return plain.isEmpty() ? null : plain;
    }
}
