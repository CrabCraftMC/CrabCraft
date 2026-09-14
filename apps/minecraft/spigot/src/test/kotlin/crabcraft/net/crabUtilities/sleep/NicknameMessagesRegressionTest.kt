package crabcraft.net.crabUtilities.sleep

import crabcraft.net.crabUtilities.NicknameComponentResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

internal object NicknameMessagesRegressionTest {
    private val PLAIN = PlainTextComponentSerializer.plainText()
    @JvmStatic
    fun main(args: Array<String>) {
        val crabby = NicknameComponentResolver.fromRawNick("&#12AB34Crabby")
        check(crabby != null, "legacy hex nickname was not parsed")
        check(plain(NicknameComponentResolver.fromRawNick("&aCrabby")) == "Crabby", "legacy colour nickname was not parsed")
        val standard = NicknameComponentResolver.fromRawNick("<red><bold>Crabby</bold></red>")
        check(plain(standard) == "Crabby", "standard colour or decoration tags were not parsed")
        val standardJson = GsonComponentSerializer.gson().serialize(standard!!).lowercase(java.util.Locale.getDefault())
        check(standardJson.contains("red") && standardJson.contains("bold"), "standard nickname styling was discarded")
        check(plain(NicknameComponentResolver.fromRawNick("<color:red>Crabby</color>")) == "Crabby", "explicit color tag was not parsed")
        check(plain(NicknameComponentResolver.fromRawNick("<gradient:#ff0000:#0000ff>Crabby</gradient>")) == "Crabby", "gradient nickname was not parsed")
        check(plain(NicknameComponentResolver.fromRawNick("<rainbow>Crabby</rainbow>")) == "Crabby", "rainbow nickname was not parsed")
        check(plain(NicknameComponentResolver.fromRawNick("<red>Crab<reset>by")) == "Crabby", "reset nickname tag was not parsed")
        for (unsafe in listOf("<click:run_command:'/op @s'>Crabby</click>", "<hover:show_text:'hello'>Crabby</hover>", "<insert:test>Crabby</insert>", "<newline>Crabby")) {
            val parsed = NicknameComponentResolver.fromRawNick(unsafe)
            check(plain(parsed) == unsafe, "unsafe nickname tag was enabled: $unsafe")
            checkNoInteractiveEvents(parsed!!, unsafe)
        }
        val malformed = "<gradient:not-a-colour>Crabby</gradient>"
        check(plain(NicknameComponentResolver.fromRawNick(malformed)) == malformed, "malformed MiniMessage nickname did not fall back to literal text")
        val single = SleepBroadcastListener.formatMessage("<player> slept.", listOf(crabby!!))
        check(plain(single) == "Crabby slept.", "single sleeper did not use the nickname")
        check(GsonComponentSerializer.gson().serialize(single).lowercase(java.util.Locale.getDefault()).contains("#12ab34"), "single sleeper lost nickname colour")
        val two = SleepBroadcastListener.formatMessage("<players> slept (<count>).", listOf(crabby, Component.text("Shelly")))
        check(plain(two) == "Crabby and Shelly slept (2).", "two-player nickname list was not joined naturally")
        check(SleepBroadcastListener.joinNames(listOf(crabby, Component.text("Shelly"))).color() == null, "first nickname colour leaked into the rest of the sleeper list")
        val three = SleepBroadcastListener.formatMessage("<players>", listOf(crabby, Component.text("Shelly"), Component.text("Claws")))
        check(plain(three) == "Crabby, Shelly, and Claws", "three-player nickname list was not joined naturally")
        val literalTags = SleepBroadcastListener.formatMessage("<player> slept.", listOf(Component.text("<red>Crab</red>")))
        check(plain(literalTags) == "<red>Crab</red> slept.", "nickname text was parsed as MiniMessage")
        check(NicknameComponentResolver.fromRawNick(null) == null, "missing nickname did not preserve the account-name fallback")
    }
    private fun plain(component: Component?): String = PLAIN.serialize(component!!)
    private fun checkNoInteractiveEvents(component: Component, raw: String) {
        for (child in component.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            check(child.clickEvent() == null && child.hoverEvent() == null && child.insertion() == null, "unsafe nickname event was attached: $raw")
        }
    }

    private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
