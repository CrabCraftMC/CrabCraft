package crabcraft.net.crabUtilities;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.UUID;

final class NicknameSoftDependencyRegressionTest {

    public static void main(String[] args) {
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("player should not be queried when EssentialsX is absent");
                });

        check(NicknameComponentResolver.forPlayer(null, player) == null,
                "missing EssentialsX did not fall back for an online player");
        check(NicknameComponentResolver.forUniqueId(null, UUID.randomUUID()) == null,
                "missing EssentialsX did not fall back for a UUID lookup");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
