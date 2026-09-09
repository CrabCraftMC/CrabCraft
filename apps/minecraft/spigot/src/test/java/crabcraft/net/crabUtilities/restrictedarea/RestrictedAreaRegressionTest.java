package crabcraft.net.crabUtilities.restrictedarea;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.Material;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RestrictedAreaRegressionTest {

    private RestrictedAreaRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyBundledDefaults();
        verifyBoundsAndMovement();
        verifyPermissionChangesAreLive();
        verifyCombatItemsRemainUsable();
        verifyCombatTargetsArePlayersOnly();
        verifyInvalidReturnLocationIsRejected();
        verifyNonNumericCoordinatesAreRejected();
    }

    private static void verifyBundledDefaults() throws Exception {
        YamlConfiguration config = loadGameplayConfig();
        check(!config.getBoolean("restricted-area.enabled", true),
                "restricted area is not opt-in");
        check(config.getString("restricted-area.bypass-permission", "")
                        .equals(RestrictedAreaSettings.DEFAULT_PERMISSION),
                "restricted area permission default changed unexpectedly");
    }

    private static void verifyBoundsAndMovement() {
        YamlConfiguration config = enabledConfig();
        // Deliberately reverse the configured corners to prove they normalise.
        config.set("restricted-area.bounds.first.x", 10D);
        config.set("restricted-area.bounds.second.x", -10D);

        RestrictedAreaSettings settings = RestrictedAreaSettings.load(config);
        RestrictedAreaSettings.Area area = settings.area();

        check(area.contains("holding", -10D, 60D, -5D),
                "inclusive minimum corner is outside the area");
        check(area.contains("holding", 10D, 70D, 5D),
                "inclusive maximum corner is outside the area");
        check(!area.contains("world", 0D, 64D, 0D),
                "same coordinates in another world are inside the area");
        check(area.movementDecision(
                        "holding", 0D, 64D, 0D,
                        "holding", 1D, 64D, 1D)
                        == RestrictedAreaSettings.MovementDecision.ALLOW,
                "movement within the area was blocked");
        check(area.movementDecision(
                        "holding", 0D, 64D, 0D,
                        "holding", 11D, 64D, 0D)
                        == RestrictedAreaSettings.MovementDecision.BLOCK,
                "movement leaving the area was not blocked");
        check(area.movementDecision(
                        "world", 0D, 64D, 0D,
                        "world", 1D, 64D, 1D)
                        == RestrictedAreaSettings.MovementDecision.RETURN,
                "a restricted player outside the area was not returned");
    }

    private static void verifyInvalidReturnLocationIsRejected() {
        YamlConfiguration config = enabledConfig();
        config.set("restricted-area.return-location.x", 50D);
        try {
            RestrictedAreaSettings.load(config);
            throw new AssertionError("return location outside the area was accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("return-location"),
                    "invalid return location produced an unclear error");
        }
    }

    private static void verifyNonNumericCoordinatesAreRejected() {
        YamlConfiguration config = enabledConfig();
        config.set("restricted-area.bounds.first.x", "not-a-number");
        try {
            RestrictedAreaSettings.load(config);
            throw new AssertionError("non-numeric coordinate was accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("must be a number"),
                    "non-numeric coordinate produced an unclear error");
        }
    }

    private static void verifyPermissionChangesAreLive() {
        RestrictedAreaSettings settings = RestrictedAreaSettings.load(enabledConfig());
        AtomicBoolean hasPermission = new AtomicBoolean(false);
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> hasPermission.get();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> defaultValue(method.getReturnType());
                });

        check(RestrictedAreaListener.isRestricted(player, settings),
                "player without the permission was not restricted");
        hasPermission.set(true);
        check(!RestrictedAreaListener.isRestricted(player, settings),
                "permission grant did not remove restrictions immediately");
        hasPermission.set(false);
        check(RestrictedAreaListener.isRestricted(player, settings),
                "permission removal did not restore restrictions immediately");
    }

    private static void verifyCombatItemsRemainUsable() {
        check(RestrictedAreaListener.isCombatItem(Material.BOW),
                "bows are not allowed for restricted PvP");
        check(RestrictedAreaListener.isCombatItem(Material.CROSSBOW),
                "crossbows are not allowed for restricted PvP");
        check(RestrictedAreaListener.isCombatItem(Material.TRIDENT),
                "tridents are not allowed for restricted PvP");
        check(RestrictedAreaListener.isCombatItem(Material.SHIELD),
                "shields are not allowed for restricted PvP");
        check(!RestrictedAreaListener.isCombatItem(Material.ENDER_PEARL),
                "ender pearls bypass the non-combat restriction");
        check(!RestrictedAreaListener.isCombatItem(Material.CHEST),
                "ordinary interaction items bypass the restriction");
    }

    private static void verifyCombatTargetsArePlayersOnly() {
        check(RestrictedAreaListener.isPvpTarget(proxy(Player.class)),
                "player target was not recognised as PvP");
        check(!RestrictedAreaListener.isPvpTarget(proxy(Entity.class)),
                "non-player entity target was recognised as PvP");
    }

    private static YamlConfiguration enabledConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("restricted-area.enabled", true);
        config.set("restricted-area.bypass-permission", "crabcraft.member");
        config.set("restricted-area.world", "holding");
        config.set("restricted-area.bounds.first.x", -5D);
        config.set("restricted-area.bounds.first.y", 60D);
        config.set("restricted-area.bounds.first.z", -5D);
        config.set("restricted-area.bounds.second.x", 5D);
        config.set("restricted-area.bounds.second.y", 70D);
        config.set("restricted-area.bounds.second.z", 5D);
        config.set("restricted-area.return-location.x", 0D);
        config.set("restricted-area.return-location.y", 64D);
        config.set("restricted-area.return-location.z", 0D);
        config.set("restricted-area.return-location.yaw", 0D);
        config.set("restricted-area.return-location.pitch", 0D);
        return config;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static YamlConfiguration loadGameplayConfig() throws Exception {
        try (InputStream input = RestrictedAreaRegressionTest.class.getClassLoader()
                .getResourceAsStream("modules/gameplay.yml")) {
            check(input != null, "bundled gameplay.yml is missing");
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return config;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
