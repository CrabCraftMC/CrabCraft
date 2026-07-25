package crabcraft.net.crabUtilities.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GlobalChatLinkRegressionTest {

    public static void main(String[] args) {
        String text = "See https://example.com/docs?q=chat, HTTP://example.org/path. "
                + "ftp://example.net https://[bad";
        Component mention = Component.text("@Alex")
                .clickEvent(ClickEvent.suggestCommand("/msg Alex "));
        Component input = mention.append(Component.space())
                .append(SafeChatMiniMessage.deserialize(text));
        Component rendered = GlobalChatService.linkifyUrls(input);
        List<ClickEvent> clicks = clickEvents(rendered);

        check(PlainTextComponentSerializer.plainText().serialize(rendered).equals("@Alex " + text),
                "linkification changed the visible message");
        check(clicks.size() == 3, "expected one mention and two URL click events");
        check(clicks.get(0).action() == ClickEvent.Action.SUGGEST_COMMAND,
                "existing mention click event was not preserved");
        checkOpenUrl(clicks.get(1), "https://example.com/docs?q=chat");
        checkOpenUrl(clicks.get(2), "HTTP://example.org/path");

        checkMentionAliases();
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

    private static void checkMentionAliases() {
        UUID spaced = UUID.randomUUID();
        UUID hyphenated = UUID.randomUUID();
        UUID unicode = UUID.randomUUID();
        UUID ambiguousOne = UUID.randomUUID();
        UUID ambiguousTwo = UUID.randomUUID();
        UUID canonical = UUID.randomUUID();
        UUID collidingNickname = UUID.randomUUID();

        MentionProcessor.AliasIndex aliases = MentionProcessor.buildAliasIndex(List.of(
                new MentionProcessor.MentionIdentity(spaced, "SpacedUser", "Crab Person"),
                new MentionProcessor.MentionIdentity(hyphenated, "HyphenUser", "Crab-Person"),
                new MentionProcessor.MentionIdentity(unicode, "UnicodeUser", "螃蟹 玩家"),
                new MentionProcessor.MentionIdentity(ambiguousOne, "FirstUser", "Shared Nick"),
                new MentionProcessor.MentionIdentity(ambiguousTwo, "SecondUser", "shared nick"),
                new MentionProcessor.MentionIdentity(canonical, "CrabPerson", null),
                new MentionProcessor.MentionIdentity(
                        collidingNickname, "CollisionUser", "crabperson")));

        check(aliases.target("Crab Person").uuid().equals(spaced),
                "nickname containing spaces was not resolvable");
        check(aliases.target("Crab-Person").uuid().equals(hyphenated),
                "hyphenated nickname was not resolvable");
        check(aliases.target("螃蟹 玩家").uuid().equals(unicode),
                "Unicode nickname was not resolvable");
        check(aliases.target("Shared Nick") == null,
                "ambiguous nickname resolved to an arbitrary player");
        check(aliases.target("CrabPerson").uuid().equals(canonical),
                "nickname collision overrode an exact account name");

        Pattern pattern = MentionProcessor.mentionPattern("@", aliases.aliases());
        Matcher matcher = pattern.matcher(
                "@Crab Person, @Crab-Person; @螃蟹 玩家 @Shared Nick @CrabPerson");
        List<String> matches = new ArrayList<>();
        while (matcher.find()) matches.add(matcher.group(1));
        check(matches.equals(List.of(
                        "Crab Person", "Crab-Person", "螃蟹 玩家", "Shared Nick", "CrabPerson")),
                "mention matcher did not preserve complete nickname tokens: " + matches);
        check(!pattern.matcher("@CrabPersonExtra").find(),
                "canonical account name matched only a prefix of a longer token");

        for (Map.Entry<UUID, String> completion : aliases.completionNames().entrySet()) {
            MentionProcessor.MentionTarget target = aliases.target(completion.getValue());
            check(target != null && target.uuid().equals(completion.getKey()),
                    "autocomplete suggested an ambiguous or misdirected nickname: "
                            + completion.getValue());
        }
        check(aliases.completionNames().get(ambiguousOne).equals("FirstUser")
                        && aliases.completionNames().get(ambiguousTwo).equals("SecondUser"),
                "ambiguous nicknames were still offered by autocomplete");
        check(aliases.completionNames().get(collidingNickname).equals("CollisionUser"),
                "nickname colliding with an account name was still offered by autocomplete");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
