package crabcraft.net.crabUtilities.restrictedarea

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.util.UUID
import java.util.function.LongSupplier

/** Wording and per-player throttling for blocked verification actions. */
class VerificationReminder(private val clock: LongSupplier) {
    private val lastSent = HashMap<UUID, Long>()

    constructor() : this(LongSupplier { System.nanoTime() })

    @Synchronized
    fun send(playerId: UUID, player: Audience) {
        val now = clock.asLong
        val previous = lastSent[playerId]
        if (previous != null && now - previous < COOLDOWN_NANOS) return
        lastSent[playerId] = now
        player.sendMessage(MESSAGE)
    }

    @Synchronized
    fun forget(playerId: UUID) { lastSent.remove(playerId) }

    companion object {
        private const val COOLDOWN_NANOS = 5_000_000_000L
        private val MESSAGE = Component.text(
            "You need to be verified to do that. Please join our Discord server and complete the short form to get verified: ",
            NamedTextColor.RED).append(Component.text("discord.crabcraft.net")
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl("https://discord.crabcraft.net")))
    }
}
