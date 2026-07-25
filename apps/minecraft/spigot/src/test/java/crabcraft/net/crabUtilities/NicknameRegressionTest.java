package crabcraft.net.crabUtilities;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class NicknameRegressionTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Key PLAYER_TYPE = Key.key("minecraft:player");
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KILLER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
        verifyLegacyDisplayRepair();

        verifyAdvancement(Component.text("Crabby"), "simple");
        verifyAdvancement(NicknameComponentResolver.fromRawNick(raw), "gradient");
        verifyTwoPlayerDeath();
        verifySuffixPreservation();
    }

    private static void verifyLegacyDisplayRepair() {
        Component prefix = Component.text("[VIP] ");
        Component suffix = Component.text("!");
        Component visibleDisplay = Component.empty()
                .append(prefix)
                .append(Component.text("Crabby"))
                .append(suffix);
        for (String raw : List.of("&aCrabby", "§aCrabby")) {
            Component repaired = NicknameSync.decoratedNickname(visibleDisplay, raw);
            checkPlain(repaired, "[VIP] Crabby!",
                    "legacy component display lost its prefix or suffix");
            check(hasStyledText(repaired, "Crabby", NamedTextColor.GREEN),
                    "legacy component display did not apply the parsed nickname for " + raw);
            check(repaired.children().get(0).equals(prefix)
                            && repaired.children().get(2).equals(suffix),
                    "legacy component display changed its prefix or suffix component");
        }

        Component afkPrefix = Component.text("[AFK] ");
        Component shortDisplay = Component.empty()
                .append(afkPrefix)
                .append(Component.text("A"))
                .append(suffix);
        Component shortRepair = NicknameSync.decoratedNickname(shortDisplay, "&aA");
        checkPlain(shortRepair, "[AFK] A!",
                "short nickname repair changed matching prefix letters");
        check(shortRepair.children().get(0).equals(afkPrefix)
                        && shortRepair.children().get(2).equals(suffix),
                "short nickname repair replaced part of the AFK prefix or suffix");
        check(hasStyledText(shortRepair, "A", NamedTextColor.GREEN),
                "short visible nickname component was not repaired");
    }

    private static void verifyAdvancement(Component nickname, String label) {
        NicknameMessageListener listener = listener(
                new PlayerFixture(ACCOUNT_ID, "AccountName", nickname));
        Component prefix = Component.text("[VIP] ");
        Component suffix = Component.text("!");
        Component advancement = Component.translatable("chat.type.advancement.task",
                playerDisplay(ACCOUNT_ID, "AccountName", prefix, suffix),
                Component.text("[Stone Age]"));

        Component transformed = listener.transformComponent(advancement);
        Component player = argument(transformed, 0);
        checkPlain(player, "[VIP] Crabby!", label + " advancement nickname was duplicated");
        check(player.children().size() == 3,
                label + " advancement changed the team display wrapper shape");
        check(player.children().get(0).equals(prefix) && player.children().get(2).equals(suffix),
                label + " advancement did not preserve the team prefix/suffix exactly once");
        checkOnce(PLAIN.serialize(player), "Crabby", label + " advancement");
        check(listener.transformComponent(transformed).equals(transformed),
                label + " advancement transform was not idempotent");
    }

    private static void verifyTwoPlayerDeath() {
        Component killerNickname = NicknameComponentResolver.fromRawNick(
                "<gradient:#00ff00:#0000ff>Lobster</gradient>");
        NicknameMessageListener listener = listener(
                new PlayerFixture(ACCOUNT_ID, "VictimAccount", Component.text("Crabby")),
                new PlayerFixture(KILLER_ID, "KillerAccount", killerNickname));
        Component death = Component.translatable("death.attack.player",
                Component.text("VictimAccount").hoverEvent(HoverEvent.showEntity(PLAYER_TYPE, ACCOUNT_ID)),
                playerDisplay(KILLER_ID, "KillerAccount", Component.text("[RED] "), Component.empty()));

        Component transformed = listener.transformComponent(death);
        Component victim = argument(transformed, 0);
        Component killer = argument(transformed, 1);
        checkPlain(victim, "Crabby", "death message duplicated the victim nickname");
        checkPlain(killer, "[RED] Lobster", "death message duplicated the killer nickname");
        checkOnce(PLAIN.serialize(victim), "Crabby", "death victim");
        checkOnce(PLAIN.serialize(killer), "Lobster", "death killer");
        check(listener.transformComponent(transformed).equals(transformed),
                "two-player death transform was not idempotent");
    }

    private static void verifySuffixPreservation() {
        NicknameMessageListener listener = listener(
                new PlayerFixture(ACCOUNT_ID, "AccountName", Component.text("Crabby")));
        Component lifecycle = Component.text("AccountName").append(Component.text(" left the game"));

        Component transformed = listener.transformComponent(lifecycle);
        checkPlain(transformed, "Crabby left the game", "lifecycle suffix was discarded");
        checkOnce(PLAIN.serialize(transformed), "Crabby", "lifecycle suffix");
        check(listener.transformComponent(transformed).equals(transformed),
                "lifecycle suffix transform was not idempotent");
    }

    private static Component playerDisplay(UUID uuid, String accountName,
                                           Component prefix, Component suffix) {
        return Component.empty()
                .hoverEvent(HoverEvent.showEntity(PLAYER_TYPE, uuid))
                .append(prefix)
                .append(Component.text(accountName))
                .append(suffix);
    }

    private static NicknameMessageListener listener(PlayerFixture... players) {
        Map<UUID, NicknameMessageListener.ResolvedNickname> byUuid = new HashMap<>();
        Map<String, NicknameMessageListener.ResolvedNickname> byName = new HashMap<>();
        for (PlayerFixture player : players) {
            NicknameMessageListener.ResolvedNickname resolved =
                    new NicknameMessageListener.ResolvedNickname(player.accountName(), player.nickname());
            byUuid.put(player.uuid(), resolved);
            byName.put(player.accountName(), resolved);
        }

        return new NicknameMessageListener(new NicknameMessageListener.NicknameResolver() {
            @Override
            public NicknameMessageListener.ResolvedNickname byUuid(UUID uuid) {
                return byUuid.get(uuid);
            }

            @Override
            public NicknameMessageListener.ResolvedNickname byAccountName(String accountName) {
                return byName.get(accountName);
            }
        });
    }

    private static Component argument(Component component, int index) {
        if (!(component instanceof TranslatableComponent translatable)) {
            throw new AssertionError("expected a translatable component");
        }
        Object value = translatable.arguments().get(index).value();
        if (!(value instanceof ComponentLike componentLike)) {
            throw new AssertionError("expected a component translation argument");
        }
        return componentLike.asComponent();
    }

    private static void checkPlain(Component component, String expected, String message) {
        String actual = PLAIN.serialize(component);
        check(actual.equals(expected), message + ": " + actual);
    }

    private static boolean hasStyledText(Component component, String text, NamedTextColor color) {
        for (Component child : component.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (child instanceof TextComponent textComponent
                    && textComponent.content().equals(text)
                    && color.equals(child.color())) {
                return true;
            }
        }
        return false;
    }

    private static void checkOnce(String value, String nickname, String label) {
        check(value.indexOf(nickname) >= 0 && value.indexOf(nickname) == value.lastIndexOf(nickname),
                label + " contained the nickname more than once: " + value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record PlayerFixture(UUID uuid, String accountName, Component nickname) {
    }
}
