package crabcraft.net.crabUtilities

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import java.util.HashMap
import java.util.UUID

object NicknameRegressionTest {

    private val PLAIN = PlainTextComponentSerializer.plainText()
    private val PLAYER_TYPE = Key.key("minecraft:player")
    private val ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val KILLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @JvmStatic
    fun main(args: Array<String>) {
        check(!NicknameSync.hasAuthoritativeRedisValue(null),
                "a missing Redis field was treated as an authoritative clear")
        check(NicknameSync.hasAuthoritativeRedisValue(""),
                "the explicit empty-string clear marker was ignored")

        val raw = "<gradient:#ff0000:#0000ff>Crabby</gradient>"
        val decorated = NicknameSync.decoratedNickname(Component.text("[" + raw + "]"), raw)
        val plain = PLAIN.serialize(decorated)
        check(plain.equals("[Crabby]"),
                "MiniMessage tags leaked into the Essentials display name: " + plain)
        val afk = NicknameSync.decoratedNickname(Component.text("[AFK] " + raw), raw)
        check(PLAIN.serialize(afk).equals("[AFK] Crabby"),
                "AFK display refresh reintroduced MiniMessage tags")
        verifyLegacyDisplayRepair()

        verifyAdvancement(Component.text("Crabby"), "simple")
        verifyAdvancement(NicknameComponentResolver.fromRawNick(raw)!!, "gradient")
        verifyTwoPlayerDeath()
        verifySuffixPreservation()
    }

    private fun verifyLegacyDisplayRepair() {
        val prefix = Component.text("[VIP] ")
        val suffix = Component.text("!")
        val visibleDisplay = Component.empty()
                .append(prefix)
                .append(Component.text("Crabby"))
                .append(suffix)
        for (raw in listOf("&aCrabby", "§aCrabby")) {
            val repaired = NicknameSync.decoratedNickname(visibleDisplay, raw)
            checkPlain(repaired, "[VIP] Crabby!",
                    "legacy component display lost its prefix or suffix")
            check(hasStyledText(repaired, "Crabby", NamedTextColor.GREEN),
                    "legacy component display did not apply the parsed nickname for " + raw)
            check(repaired.children().get(0).equals(prefix)
                            && repaired.children().get(2).equals(suffix),
                    "legacy component display changed its prefix or suffix component")
        }

        val afkPrefix = Component.text("[AFK] ")
        val shortDisplay = Component.empty()
                .append(afkPrefix)
                .append(Component.text("A"))
                .append(suffix)
        val shortRepair = NicknameSync.decoratedNickname(shortDisplay, "&aA")
        checkPlain(shortRepair, "[AFK] A!",
                "short nickname repair changed matching prefix letters")
        check(shortRepair.children().get(0).equals(afkPrefix)
                        && shortRepair.children().get(2).equals(suffix),
                "short nickname repair replaced part of the AFK prefix or suffix")
        check(hasStyledText(shortRepair, "A", NamedTextColor.GREEN),
                "short visible nickname component was not repaired")
    }

    private fun verifyAdvancement(nickname: Component, label: String) {
        val listener = listener(
                PlayerFixture(ACCOUNT_ID, "AccountName", nickname))
        val prefix = Component.text("[VIP] ")
        val suffix = Component.text("!")
        val advancement = Component.translatable("chat.type.advancement.task",
                playerDisplay(ACCOUNT_ID, "AccountName", prefix, suffix),
                Component.text("[Stone Age]"))

        val transformed = listener.transformComponent(advancement)
        val player = argument(transformed, 0)
        checkPlain(player, "[VIP] Crabby!", label + " advancement nickname was duplicated")
        check(player.children().size == 3,
                label + " advancement changed the team display wrapper shape")
        check(player.children().get(0).equals(prefix) && player.children().get(2).equals(suffix),
                label + " advancement did not preserve the team prefix/suffix exactly once")
        checkOnce(PLAIN.serialize(player), "Crabby", label + " advancement")
        check(listener.transformComponent(transformed).equals(transformed),
                label + " advancement transform was not idempotent")
    }

    private fun verifyTwoPlayerDeath() {
        val killerNickname = NicknameComponentResolver.fromRawNick(
                "<gradient:#00ff00:#0000ff>Lobster</gradient>")!!
        val listener = listener(
                PlayerFixture(ACCOUNT_ID, "VictimAccount", Component.text("Crabby")),
                PlayerFixture(KILLER_ID, "KillerAccount", killerNickname))
        val death = Component.translatable("death.attack.player",
                Component.text("VictimAccount").hoverEvent(HoverEvent.showEntity(PLAYER_TYPE, ACCOUNT_ID)),
                playerDisplay(KILLER_ID, "KillerAccount", Component.text("[RED] "), Component.empty()))

        val transformed = listener.transformComponent(death)
        val victim = argument(transformed, 0)
        val killer = argument(transformed, 1)
        checkPlain(victim, "Crabby", "death message duplicated the victim nickname")
        checkPlain(killer, "[RED] Lobster", "death message duplicated the killer nickname")
        checkOnce(PLAIN.serialize(victim), "Crabby", "death victim")
        checkOnce(PLAIN.serialize(killer), "Lobster", "death killer")
        check(listener.transformComponent(transformed).equals(transformed),
                "two-player death transform was not idempotent")
    }

    private fun verifySuffixPreservation() {
        val listener = listener(
                PlayerFixture(ACCOUNT_ID, "AccountName", Component.text("Crabby")))
        val lifecycle = Component.text("AccountName").append(Component.text(" left the game"))

        val transformed = listener.transformComponent(lifecycle)
        checkPlain(transformed, "Crabby left the game", "lifecycle suffix was discarded")
        checkOnce(PLAIN.serialize(transformed), "Crabby", "lifecycle suffix")
        check(listener.transformComponent(transformed).equals(transformed),
                "lifecycle suffix transform was not idempotent")
    }

    private fun playerDisplay(uuid: UUID, accountName: String, prefix: Component, suffix: Component): Component {
        return Component.empty()
                .hoverEvent(HoverEvent.showEntity(PLAYER_TYPE, uuid))
                .append(prefix)
                .append(Component.text(accountName))
                .append(suffix)
    }

    private fun listener(vararg players: PlayerFixture): NicknameMessageListener {
        val byUuid = HashMap<UUID, NicknameMessageListener.ResolvedNickname>()
        val byName = HashMap<String, NicknameMessageListener.ResolvedNickname>()
        for (player in players) {
            val resolved = NicknameMessageListener.ResolvedNickname(player.accountName(), player.nickname())
            byUuid.put(player.uuid(), resolved)
            byName.put(player.accountName(), resolved)
        }

        return NicknameMessageListener(object : NicknameMessageListener.NicknameResolver {
            override fun byUuid(uuid: UUID): NicknameMessageListener.ResolvedNickname? {
                return byUuid.get(uuid)
            }

            override fun byAccountName(accountName: String): NicknameMessageListener.ResolvedNickname? {
                return byName.get(accountName)
            }
        })
    }

    private fun argument(component: Component, index: Int): Component {
        if (component !is TranslatableComponent) {
            throw AssertionError("expected a translatable component")
        }
        val value = component.arguments().get(index).value()
        if (value !is ComponentLike) {
            throw AssertionError("expected a component translation argument")
        }
        return value.asComponent()
    }

    private fun checkPlain(component: Component, expected: String, message: String) {
        val actual = PLAIN.serialize(component)
        check(actual.equals(expected), message + ": " + actual)
    }

    private fun hasStyledText(component: Component, text: String, color: NamedTextColor): Boolean {
        for (child in component.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (child is TextComponent
                    && child.content().equals(text)
                    && color.equals(child.color())) {
                return true
            }
        }
        return false
    }

    private fun checkOnce(value: String, nickname: String, label: String) {
        check(value.indexOf(nickname) >= 0 && value.indexOf(nickname) == value.lastIndexOf(nickname),
                label + " contained the nickname more than once: " + value)
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private data class PlayerFixture(private val uuid: UUID, private val accountName: String, private val nickname: Component) {
        fun uuid(): UUID = uuid
        fun accountName(): String = accountName
        fun nickname(): Component = nickname
    }
}
