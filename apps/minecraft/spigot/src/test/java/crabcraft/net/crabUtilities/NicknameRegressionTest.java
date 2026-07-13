package crabcraft.net.crabUtilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;

final class NicknameRegressionTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static void main(String[] args) {
        check(!NicknameSync.hasAuthoritativeRedisValue(null),
                "a missing Redis field was treated as an authoritative clear");
        check(NicknameSync.hasAuthoritativeRedisValue(""),
                "the explicit empty-string clear marker was ignored");

        String raw = "<gradient:#ff0000:#0000ff>Crabby</gradient>";
        Component decorated = NicknameSync.decoratedNickname(Component.text("[" + raw + "]"), raw);
        String plain = PLAIN.serialize(decorated);
        check(plain.equals("[Crabby]"),
                "MiniMessage tags leaked into the Essentials display name: " + plain);
        Component afk = NicknameSync.decoratedNickname(Component.text("[AFK] " + raw), raw);
        check(PLAIN.serialize(afk).equals("[AFK] Crabby"),
                "AFK display refresh reintroduced MiniMessage tags");

        Component suffix = NicknameMessageListener.appendNonNameChildren(
                Component.text("Crabby"),
                Component.text("AccountName").append(Component.text(" left the game")),
                "AccountName");
        check(PLAIN.serialize(suffix).equals("Crabby left the game"),
                "lifecycle message suffix was discarded");

        Component perCharacterName = Component.text("AccountName").children(List.of(
                Component.text("A"), Component.text("c"), Component.text("c"),
                Component.text("o"), Component.text("u"), Component.text("n"),
                Component.text("t"), Component.text("N"), Component.text("a"),
                Component.text("m"), Component.text("e"), Component.text(" left the game")));
        Component deduplicated = NicknameMessageListener.appendNonNameChildren(
                Component.text("Crabby"), perCharacterName, "AccountName");
        check(PLAIN.serialize(deduplicated).equals("Crabby left the game"),
                "per-character account name children were duplicated");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
