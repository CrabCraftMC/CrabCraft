package crabcraft.net.crabUtilities;

import org.bukkit.OfflinePlayer;

import java.lang.reflect.Proxy;
import java.util.UUID;

final class NicknamePlaceholderRegressionTest {

    public static void main(String[] args) {
        UUID uuid = UUID.randomUUID();
        OfflinePlayer player = player(uuid, "AccountName");
        LoginStreakExpansion expansion = new LoginStreakExpansion(null, null);

        for (String placeholder : new String[]{"nickname", "nickname_stripped"}) {
            check("AccountName".equals(expansion.onRequest(player, placeholder)),
                    placeholder + ": a missing nickname did not fall back to the account name");
            check("".equals(expansion.onRequest(null, placeholder)),
                    placeholder + ": a missing player did not return an empty string");
            check("".equals(expansion.onRequest(player(UUID.randomUUID(), null), placeholder)),
                    placeholder + ": an unknown account name did not return an empty string");
        }

        try {
            NicknameComponentResolver.cacheNickname(uuid, "&a&lCrabby");
            check("§a§lCrabby".equals(expansion.onRequest(player, "nickname")),
                    "legacy nickname colours or decorations were lost");
            check("Crabby".equals(expansion.onRequest(player, "nickname_stripped")),
                    "the stripped nickname retained legacy colours or decorations");

            NicknameComponentResolver.cacheNickname(uuid, "&#12ab34Crabby");
            check("§x§1§2§a§b§3§4Crabby".equals(expansion.onRequest(player, "nickname")),
                    "hex nickname colours were lost");
            check("Crabby".equals(expansion.onRequest(player, "nickname_stripped")),
                    "the stripped nickname retained hex colours");

            NicknameComponentResolver.cacheNickname(uuid, "<gradient:#ff0000:#0000ff>Cr</gradient>");
            check("§x§f§f§0§0§0§0C§x§0§0§0§0§f§fr".equals(expansion.onRequest(player, "nickname")),
                    "MiniMessage gradient colours were lost");
            check("Cr".equals(expansion.onRequest(player, "nickname_stripped")),
                    "the stripped nickname retained MiniMessage gradient formatting");
            NicknameComponentResolver.cacheNickname(uuid, "<green><bold>Crabby</bold></green>");
            check("Crabby".equals(expansion.onRequest(player, "nickname_stripped")),
                    "the stripped nickname retained MiniMessage colours or decorations");
            for (String placeholder : new String[]{"nickname", "nickname_stripped"}) {
                check(expansion.onRequest(player, placeholder).equals(
                                expansion.onRequest(player(uuid, null), placeholder)),
                        placeholder + ": offline nickname lookup depended on the account name");
            }

            NicknameComponentResolver.cacheNickname(uuid, "");
            for (String placeholder : new String[]{"nickname", "nickname_stripped"}) {
                check("AccountName".equals(expansion.onRequest(player, placeholder)),
                        placeholder + ": clearing a nickname did not restore the account-name fallback");
            }
            check(expansion.onRequest(player, "unknown") == null,
                    "unknown placeholders were claimed by the nickname placeholder");
            check("no".equals(expansion.onRequest(player, "afk")),
                    "the nickname placeholder changed AFK placeholder handling");
            check("0".equals(expansion.onRequest(player, "streak_current")),
                    "the nickname placeholder changed streak placeholder handling");
        } finally {
            NicknameComponentResolver.cacheNickname(uuid, "");
        }
    }

    private static OfflinePlayer player(UUID uuid, String name) {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> name;
                    default -> throw new AssertionError("unexpected player lookup: " + method.getName());
                });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
