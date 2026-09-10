package crabcraft.net.crabUtilities.restrictedarea;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Wording and per-player throttling for blocked verification actions. */
public final class VerificationReminder {

    private static final long COOLDOWN_NANOS = 5_000_000_000L;
    private static final Component MESSAGE = Component.text(
            "You need to be verified to do that. Please join our Discord server and complete the short form to get verified: ",
            NamedTextColor.RED).append(Component.text("discord.crabcraft.net")
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl("https://discord.crabcraft.net")));

    private final Map<UUID, Long> lastSent = new HashMap<>();
    private final LongSupplier clock;

    public VerificationReminder() {
        this(System::nanoTime);
    }

    VerificationReminder(final LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void send(final UUID playerId, final Audience player) {
        final long now = clock.getAsLong();
        final Long previous = lastSent.get(playerId);
        if (previous != null && now - previous < COOLDOWN_NANOS) {
            return;
        }
        lastSent.put(playerId, now);
        player.sendMessage(MESSAGE);
    }

    public synchronized void forget(final UUID playerId) {
        lastSent.remove(playerId);
    }
}
