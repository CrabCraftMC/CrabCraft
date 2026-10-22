package crabcraft.net.crabUtilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class NicknameSoftDependencyRegressionTest {

    public static void main(String[] args) {
        UUID uuid = UUID.randomUUID();
        AtomicReference<Component> displayName = new AtomicReference<>();
        AtomicReference<Component> tabName = new AtomicReference<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "AccountName";
                    case "displayName" -> {
                        displayName.set((Component) arguments[0]);
                        yield null;
                    }
                    case "playerListName" -> {
                        tabName.set((Component) arguments[0]);
                        yield null;
                    }
                    default -> throw new AssertionError("unexpected player lookup: " + method.getName());
                });

        check(NicknameComponentResolver.forPlayer(player) == null,
                "a player without a nickname did not use the account-name fallback");
        check(NicknameComponentResolver.forUniqueId(null) == null,
                "a missing UUID did not use the account-name fallback");
        check(NicknameComponentResolver.plainNicknameOrName(player).equals("AccountName"),
                "plain nickname lookup did not fall back to the account name");

        NicknameComponentResolver.cacheNickname(uuid, "<green>Crabby</green>");
        Component expected = Component.text("Crabby", NamedTextColor.GREEN);
        check(expected.equals(NicknameComponentResolver.forPlayer(player)),
                "online nickname lookup required EssentialsX or lost its colour");
        check(expected.equals(NicknameComponentResolver.forUniqueId(uuid)),
                "offline UUID nickname lookup required EssentialsX");
        check(NicknameComponentResolver.plainNicknameOrName(player).equals("Crabby"),
                "voice nickname lookup exposed formatting tags");

        NicknameSync.applyDisplayName(player);
        check(expected.equals(displayName.get()) && expected.equals(tabName.get()),
                "nickname colours were not applied to both the display and tab names");
        NicknameComponentResolver.cacheNickname(uuid, "");
        NicknameSync.applyDisplayName(player);
        check(NicknameComponentResolver.forUniqueId(uuid) == null,
                "an explicit nickname clear left a cached nickname");
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        check(plain.serialize(displayName.get()).equals("AccountName")
                        && plain.serialize(tabName.get()).equals("AccountName"),
                "clearing a nickname did not reset the display and tab names");
        check(NicknameComponentResolver.plainNicknameOrName(player).equals("AccountName"),
                "clearing a nickname did not reset plain nickname lookups");

    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
