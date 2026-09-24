package crabcraft.net.crabUtilities.chat

import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

object GlobalChatLinkRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val text = "See https://example.com/docs?q=chat, HTTP://example.org/path. " + "ftp://example.net https://[bad"
        val mention = Component.text("@Alex").clickEvent(ClickEvent.suggestCommand("/msg Alex "))
        val input = mention.append(Component.space()).append(SafeChatMiniMessage.deserialize(text))
        val rendered = GlobalChatService.linkifyUrls(input)
        val clicks = clickEvents(rendered)
        check(
            PlainTextComponentSerializer.plainText().serialize(rendered) == "@Alex $text",
            "linkification changed the visible message",
        )
        check(clicks.size == 3, "expected one mention and two URL click events")
        check(clicks[0].action() == ClickEvent.Action.SUGGEST_COMMAND, "existing mention click event was not preserved")
        checkOpenUrl(clicks[1], "https://example.com/docs?q=chat")
        checkOpenUrl(clicks[2], "HTTP://example.org/path")
        checkMentionAliases()
    }

    private fun clickEvents(component: Component): List<ClickEvent<*>> =
        component.iterable(ComponentIteratorType.DEPTH_FIRST).mapNotNull { it.clickEvent() }

    private fun checkOpenUrl(event: ClickEvent<*>, expectedUrl: String) {
        check(event.action() == ClickEvent.Action.OPEN_URL, "URL did not use OPEN_URL")
        val payload = event.payload()
        check(
            payload is ClickEvent.Payload.Text && payload.value() == expectedUrl,
            "URL included trailing punctuation or changed case",
        )
    }

    private fun checkMentionAliases() {
        val spaced = UUID.randomUUID()
        val hyphenated = UUID.randomUUID()
        val unicode = UUID.randomUUID()
        val ambiguousOne = UUID.randomUUID()
        val ambiguousTwo = UUID.randomUUID()
        val canonical = UUID.randomUUID()
        val collidingNickname = UUID.randomUUID()
        val aliases =
            MentionProcessor.buildAliasIndex(
                listOf(
                    MentionProcessor.MentionIdentity(spaced, "SpacedUser", "Crab Person"),
                    MentionProcessor.MentionIdentity(hyphenated, "HyphenUser", "Crab-Person"),
                    MentionProcessor.MentionIdentity(unicode, "UnicodeUser", "螃蟹 玩家"),
                    MentionProcessor.MentionIdentity(ambiguousOne, "FirstUser", "Shared Nick"),
                    MentionProcessor.MentionIdentity(ambiguousTwo, "SecondUser", "shared nick"),
                    MentionProcessor.MentionIdentity(canonical, "CrabPerson", null),
                    MentionProcessor.MentionIdentity(collidingNickname, "CollisionUser", "crabperson"),
                )
            )
        check(aliases.target("Crab Person")?.uuid() == spaced, "nickname containing spaces was not resolvable")
        check(aliases.target("Crab-Person")?.uuid() == hyphenated, "hyphenated nickname was not resolvable")
        check(aliases.target("螃蟹 玩家")?.uuid() == unicode, "Unicode nickname was not resolvable")
        check(aliases.target("Shared Nick") == null, "ambiguous nickname resolved to an arbitrary player")
        check(aliases.target("CrabPerson")?.uuid() == canonical, "nickname collision overrode an exact account name")
        val pattern = MentionProcessor.mentionPattern("@", aliases.aliases())
        val matcher = pattern.matcher("@Crab Person, @Crab-Person; @螃蟹 玩家 @Shared Nick @CrabPerson")
        val matches = ArrayList<String>()
        while (matcher.find()) matches.add(matcher.group(1))
        check(
            matches == listOf("Crab Person", "Crab-Person", "螃蟹 玩家", "Shared Nick", "CrabPerson"),
            "mention matcher did not preserve complete nickname tokens: $matches",
        )
        check(
            !pattern.matcher("@CrabPersonExtra").find(),
            "canonical account name matched only a prefix of a longer token",
        )
        for ((uuid, name) in aliases.completionNames()) {
            val target = aliases.target(name)
            check(
                target != null && target.uuid() == uuid,
                "autocomplete suggested an ambiguous or misdirected nickname: $name",
            )
        }
        check(
            aliases.completionNames()[ambiguousOne] == "FirstUser" &&
                aliases.completionNames()[ambiguousTwo] == "SecondUser",
            "ambiguous nicknames were still offered by autocomplete",
        )
        check(
            aliases.completionNames()[collidingNickname] == "CollisionUser",
            "nickname colliding with an account name was still offered by autocomplete",
        )
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
