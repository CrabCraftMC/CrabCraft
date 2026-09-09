package crabcraft.net.crabUtilities.restrictedarea;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class VerificationReminderRegressionTest {

    public static void main(String[] args) {
        AtomicLong now = new AtomicLong();
        VerificationReminder reminder = new VerificationReminder(now::get);
        List<Component> messages = new ArrayList<>();
        Audience player = new Audience() {
            @Override
            public void sendMessage(Component message) {
                messages.add(message);
            }
        };
        UUID first = UUID.randomUUID();
        reminder.send(first, player);
        check(messages.size() == 1, "first blocked action did not send a reminder");
        Component message = messages.getFirst();
        check(NamedTextColor.RED.equals(message.color()), "reminder is not red");
        check(((TextComponent) message).content().contains("complete the short form"),
                "reminder does not explain how to get verified");
        Component link = message.children().getFirst();
        check(((TextComponent) link).content().equals("discord.crabcraft.net"),
                "Discord link label is incorrect");
        check(ClickEvent.openUrl("https://discord.crabcraft.net").equals(link.clickEvent()),
                "Discord link is not clickable");
        check(link.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE,
                "Discord link is not underlined");

        now.set(4_999_999_999L);
        reminder.send(first, player);
        check(messages.size() == 1, "repeated blocked actions flooded chat");
        reminder.send(UUID.randomUUID(), player);
        check(messages.size() == 2, "one player's cooldown silenced another player");
        now.set(5_000_000_000L);
        reminder.send(first, player);
        check(messages.size() == 3, "reminder was still throttled after five seconds");
        reminder.forget(first);
        reminder.send(first, player);
        check(messages.size() == 4, "disconnect cleanup retained the cooldown");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
