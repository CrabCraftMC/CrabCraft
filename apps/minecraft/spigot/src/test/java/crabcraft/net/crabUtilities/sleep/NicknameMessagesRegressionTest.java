package crabcraft.net.crabUtilities.sleep;

import crabcraft.net.crabUtilities.NicknameComponentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;

final class NicknameMessagesRegressionTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static void main(String[] args) {
        Component crabby = NicknameComponentResolver.fromRawNick("&#12AB34Crabby");
        check(crabby != null, "legacy hex nickname was not parsed");

        Component single = SleepBroadcastListener.formatMessage(
                "<player> slept.", List.of(crabby));
        check(plain(single).equals("Crabby slept."), "single sleeper did not use the nickname");
        check(GsonComponentSerializer.gson().serialize(single).toLowerCase().contains("#12ab34"),
                "single sleeper lost nickname colour");

        Component two = SleepBroadcastListener.formatMessage(
                "<players> slept (<count>).", List.of(crabby, Component.text("Shelly")));
        check(plain(two).equals("Crabby and Shelly slept (2)."),
                "two-player nickname list was not joined naturally");
        check(SleepBroadcastListener.joinNames(List.of(crabby, Component.text("Shelly"))).color() == null,
                "first nickname colour leaked into the rest of the sleeper list");

        Component three = SleepBroadcastListener.formatMessage(
                "<players>", List.of(crabby, Component.text("Shelly"), Component.text("Claws")));
        check(plain(three).equals("Crabby, Shelly, and Claws"),
                "three-player nickname list was not joined naturally");

        Component literalTags = SleepBroadcastListener.formatMessage(
                "<player> slept.", List.of(Component.text("<red>Crab</red>")));
        check(plain(literalTags).equals("<red>Crab</red> slept."),
                "nickname text was parsed as MiniMessage");

        check(NicknameComponentResolver.fromRawNick(null) == null,
                "missing nickname did not preserve the account-name fallback");
    }

    private static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
