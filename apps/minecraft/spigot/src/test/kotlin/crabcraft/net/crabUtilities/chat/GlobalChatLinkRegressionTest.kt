package crabcraft.net.crabUtilities.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.Locale
import java.util.UUID

object GlobalChatLinkRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val token = UUID.randomUUID().toString().take(8)
        val username = "Fict$token"
        val text = "See https://example.com/$token?q=chat, HTTP://example.org/$token. " +
            "ftp://example.net https://[bad"
        val mention = Component.text("@$username")
            .clickEvent(ClickEvent.suggestCommand("/msg $username "))
        val input = mention.append(Component.space())
            .append(SafeChatMiniMessage.deserialize(text))
        val rendered = GlobalChatService.linkifyUrls(input)
        val clicks = clickEvents(rendered)

        check(PlainTextComponentSerializer.plainText().serialize(rendered) == "@$username $text",
            "linkification changed the visible message")
        check(clicks.size == 3, "expected one mention and two URL click events")
        check(clicks[0].action() == ClickEvent.Action.SUGGEST_COMMAND,
            "existing mention click event was not preserved")
        checkOpenUrl(clicks[1], "https://example.com/$token?q=chat")
        checkOpenUrl(clicks[2], "HTTP://example.org/$token")

        checkMentionAliases()
    }

    private fun clickEvents(component: Component): List<ClickEvent<*>> {
        val events = ArrayList<ClickEvent<*>>()
        for (child in component.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            child.clickEvent()?.let(events::add)
        }
        return events
    }

    private fun checkOpenUrl(event: ClickEvent<*>, expectedUrl: String) {
        check(event.action() == ClickEvent.Action.OPEN_URL, "URL did not use OPEN_URL")
        val payload = event.payload()
        check(payload is ClickEvent.Payload.Text && payload.value() == expectedUrl,
            "URL included trailing punctuation or changed case")
    }

    private fun checkMentionAliases() {
        val spaced = UUID.randomUUID()
        val hyphenated = UUID.randomUUID()
        val unicode = UUID.randomUUID()
        val ambiguousOne = UUID.randomUUID()
        val ambiguousTwo = UUID.randomUUID()
        val canonical = UUID.randomUUID()
        val collidingNickname = UUID.randomUUID()
        val token = UUID.randomUUID().toString().take(8)
        val spacedName = "Fict $token"
        val hyphenatedName = "Fict-$token"
        val unicodeName = "虚构 $token"
        val sharedName = "Shared $token"
        val canonicalName = "Fict$token"
        val firstUsername = "First$token"
        val secondUsername = "Second$token"
        val collisionUsername = "Collide$token"

        val aliases = MentionProcessor.buildAliasIndex(listOf(
            MentionProcessor.MentionIdentity(spaced, "Spaced$token", spacedName),
            MentionProcessor.MentionIdentity(hyphenated, "Hyphen$token", hyphenatedName),
            MentionProcessor.MentionIdentity(unicode, "Unicode$token", unicodeName),
            MentionProcessor.MentionIdentity(ambiguousOne, firstUsername, sharedName),
            MentionProcessor.MentionIdentity(ambiguousTwo, secondUsername, sharedName.lowercase(Locale.ROOT)),
            MentionProcessor.MentionIdentity(canonical, canonicalName, null),
            MentionProcessor.MentionIdentity(collidingNickname, collisionUsername, canonicalName.lowercase(Locale.ROOT))
        ))

        check(aliases.target(spacedName)!!.uuid() == spaced,
            "nickname containing spaces was not resolvable")
        check(aliases.target(hyphenatedName)!!.uuid() == hyphenated,
            "hyphenated nickname was not resolvable")
        check(aliases.target(unicodeName)!!.uuid() == unicode,
            "Unicode nickname was not resolvable")
        check(aliases.target(sharedName) == null,
            "ambiguous nickname resolved to an arbitrary player")
        check(aliases.target(canonicalName)!!.uuid() == canonical,
            "nickname collision overrode an exact account name")

        val pattern = MentionProcessor.mentionPattern("@", aliases.aliases())
        val matcher = pattern.matcher(
            "@$spacedName, @$hyphenatedName; @$unicodeName @$sharedName @$canonicalName"
        )
        val matches = ArrayList<String>()
        while (matcher.find()) matches.add(matcher.group(1))
        check(matches == listOf(spacedName, hyphenatedName, unicodeName, sharedName, canonicalName),
            "mention matcher did not preserve complete nickname tokens: $matches")
        check(!pattern.matcher("@${canonicalName}Extra").find(),
            "canonical account name matched only a prefix of a longer token")

        for ((uuid, completionName) in aliases.completionNames()) {
            val target = aliases.target(completionName)
            check(target != null && target.uuid() == uuid,
                "autocomplete suggested an ambiguous or misdirected nickname: $completionName")
        }
        check(aliases.completionNames()[ambiguousOne] == firstUsername &&
            aliases.completionNames()[ambiguousTwo] == secondUsername,
            "ambiguous nicknames were still offered by autocomplete")
        check(aliases.completionNames()[collidingNickname] == collisionUsername,
            "nickname colliding with an account name was still offered by autocomplete")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
