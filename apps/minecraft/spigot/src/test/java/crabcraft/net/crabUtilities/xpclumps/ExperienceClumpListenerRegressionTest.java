package crabcraft.net.crabUtilities.xpclumps;

import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class ExperienceClumpListenerRegressionTest {
    public static void main(String[] args) throws Exception {
        OrbStub destination = new OrbStub(7);
        OrbStub nearby = new OrbStub(5);
        AtomicBoolean unrelatedRemoved = new AtomicBoolean();
        Entity unrelated = proxy(Entity.class, (proxy, method, methodArgs) -> switch (method.getName()) {
            case "remove" -> {
                unrelatedRemoved.set(true);
                yield null;
            }
            case "equals" -> proxy == methodArgs[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "unrelated entity";
            default -> defaultValue(method.getReturnType());
        });

        destination.nearby = List.of(nearby.orb, unrelated, destination.orb);
        ExperienceClumpListener.mergeNearbyOrbs(destination.orb);

        check(destination.experience.get() == 12, "nearby XP was not added to the spawned orb");
        check(nearby.removed.get(), "absorbed XP orb was not removed");
        check(!destination.removed.get(), "spawned XP orb removed itself");
        check(!unrelatedRemoved.get(), "non-XP entity was removed");
        check(destination.searches.get() == 1, "nearby entities were queried more than once");
        check(destination.lastRadius == 3D, "PVPClumps three-block search radius changed");

        String config;
        try (var input = ExperienceClumpListenerRegressionTest.class.getClassLoader()
                .getResourceAsStream("modules/tweaks.yml")) {
            check(input != null, "bundled modules/tweaks.yml is missing");
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        check(config.contains("pvp-clumps:") && config.contains("pvp-clumps:\n    enabled: false"),
                "PVPClumps tweak is not disabled by default");
    }

    private static final class OrbStub {
        private final AtomicInteger experience;
        private final AtomicBoolean removed = new AtomicBoolean();
        private final AtomicInteger searches = new AtomicInteger();
        private final ExperienceOrb orb;
        private List<Entity> nearby = List.of();
        private double lastRadius;

        private OrbStub(int experience) {
            this.experience = new AtomicInteger(experience);
            this.orb = proxy(ExperienceOrb.class, (proxy, method, args) -> switch (method.getName()) {
                case "getExperience" -> this.experience.get();
                case "setExperience" -> {
                    this.experience.set((int) args[0]);
                    yield null;
                }
                case "getNearbyEntities" -> {
                    searches.incrementAndGet();
                    double x = (double) args[0];
                    double y = (double) args[1];
                    double z = (double) args[2];
                    check(x == y && y == z, "XP clump search is not symmetrical");
                    lastRadius = x;
                    yield nearby;
                }
                case "remove" -> {
                    removed.set(true);
                    yield null;
                }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "XP orb";
                default -> defaultValue(method.getReturnType());
            });
        }
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
