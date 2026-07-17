package crabcraft.net.crabUtilities.villagers;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class SharedVillagerDiscountListenerRegressionTest {
    public static void main(String[] args) throws Exception {
        cureDiscountMatchesVanillaReputation();
        radiusIsSphericalAndExcludesSpectators();
        configIsOptInWithDocumentedRadius();
    }

    private static void cureDiscountMatchesVanillaReputation() {
        UUID playerId = UUID.randomUUID();
        Reputation original = new Reputation();
        original.setReputation(ReputationType.MAJOR_POSITIVE, 12);
        original.setReputation(ReputationType.TRADING, 7);
        AtomicReference<Reputation> saved = new AtomicReference<>();
        Villager villager = proxy(Villager.class, (proxy, method, args) -> switch (method.getName()) {
            case "getReputation" -> original;
            case "setReputation" -> {
                check(playerId.equals(args[0]), "discount was saved under the wrong player");
                saved.set((Reputation) args[1]);
                yield null;
            }
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> defaultValue(method.getReturnType());
        });

        SharedVillagerDiscountListener.applyCureDiscount(villager, playerId);

        Reputation result = saved.get();
        check(result != null, "updated cure reputation was not saved");
        check(result.getReputation(ReputationType.MAJOR_POSITIVE) == 20,
                "major-positive cure gossip does not match vanilla");
        check(result.getReputation(ReputationType.MINOR_POSITIVE) == 25,
                "minor-positive cure gossip does not match vanilla");
        check(result.getReputation(ReputationType.TRADING) == 7,
                "unrelated trading reputation was changed");
    }

    private static void radiusIsSphericalAndExcludesSpectators() {
        AtomicReference<Double> queriedRadius = new AtomicReference<>();
        AtomicReference<Collection<Player>> candidates = new AtomicReference<>();
        World world = proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getNearbyPlayers" -> {
                queriedRadius.set((double) args[1]);
                yield candidates.get();
            }
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> defaultValue(method.getReturnType());
        });
        Location origin = new Location(world, 0D, 0D, 0D);
        Player inside = player(world, 3D, 4D, 0D, GameMode.SURVIVAL);
        Player cubeCorner = player(world, 4D, 4D, 0D, GameMode.SURVIVAL);
        Player spectator = player(world, 1D, 0D, 0D, GameMode.SPECTATOR);
        candidates.set(List.of(inside, cubeCorner, spectator));

        List<Player> nearby = SharedVillagerDiscountListener.nearbyPlayers(world, origin, 5D);

        check(queriedRadius.get() == 5D, "configured radius was not used for the player query");
        check(nearby.equals(List.of(inside)), "radius was not a non-spectator 3D sphere");
    }

    private static void configIsOptInWithDocumentedRadius() throws Exception {
        String config;
        try (var input = SharedVillagerDiscountListenerRegressionTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            check(input != null, "bundled config.yml is missing");
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        check(config.contains("shared-villager-discounts:\n    enabled: false\n    radius: 100.0"),
                "shared villager discounts are not opt-in with a 100-block default radius");
    }

    private static Player player(World world, double x, double y, double z, GameMode gameMode) {
        return proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getLocation" -> new Location(world, x, y, z);
            case "getGameMode" -> gameMode;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
