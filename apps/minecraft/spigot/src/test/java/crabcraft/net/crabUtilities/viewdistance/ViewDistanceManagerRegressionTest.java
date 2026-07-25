package crabcraft.net.crabUtilities.viewdistance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class ViewDistanceManagerRegressionTest {

    public static void main(String[] args) throws Exception {
        checkBoundsValidation();
        checkTickDurationMedian();
        checkAdjustmentHysteresis();
        checkAdjustmentSwitch();
        checkManualBounds();
        checkCommandSurface();
        checkWorldDistanceChanges();
        checkAdvertisedRadiusRewrite();
        checkClientBridgeLifecycle();
        checkConfigurationContractAndAttribution();
    }

    private static void checkBoundsValidation() {
        ViewDistanceManager.Bounds bounds = ViewDistanceManager.Bounds.checked("test", 6, 12);
        check(bounds.clamp(5) == 6, "distance was not clamped to its minimum");
        check(bounds.clamp(9) == 9, "in-range distance was changed");
        check(bounds.clamp(13) == 12, "distance was not clamped to its maximum");

        expectInvalidBounds(1, 12);
        expectInvalidBounds(6, 33);
        expectInvalidBounds(12, 6);
    }

    private static void expectInvalidBounds(int minimum, int maximum) {
        try {
            ViewDistanceManager.Bounds.checked("test", minimum, maximum);
            throw new AssertionError("invalid bounds were accepted: " + minimum + "–" + maximum);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void checkTickDurationMedian() {
        ViewDistanceManager.TickDurationWindow window =
                new ViewDistanceManager.TickDurationWindow(3);
        check(window.median(25.0) == 25.0, "empty MSPT window did not use its fallback");

        window.add(50.0);
        window.add(10.0);
        check(window.median(25.0) == 30.0, "even-sized MSPT median is incorrect");
        window.add(30.0);
        check(window.median(25.0) == 30.0, "odd-sized MSPT median is incorrect");

        // Capacity is three, so the oldest 50ms sample must be discarded.
        window.add(20.0);
        check(window.median(25.0) == 20.0, "MSPT window did not discard its oldest sample");
        window.add(Double.NaN);
        window.add(-1.0);
        check(window.median(25.0) == 20.0, "invalid MSPT samples changed the window");
    }

    private static void checkAdjustmentHysteresis() {
        ViewDistanceManager.AdjustmentPolicy policy = new ViewDistanceManager.AdjustmentPolicy();
        UUID worldId = UUID.randomUUID();

        for (int check = 1; check < ViewDistanceManager.PASSED_CHECKS_FOR_INCREASE; check++) {
            check(policy.choose(worldId, ViewDistanceManager.INCREASE_MSPT_THRESHOLD)
                            == ViewDistanceManager.Adjustment.STAY,
                    "view distance increased before the low-MSPT hysteresis passed");
        }
        check(policy.choose(worldId, ViewDistanceManager.INCREASE_MSPT_THRESHOLD)
                        == ViewDistanceManager.Adjustment.INCREASE,
                "view distance did not increase on the tenth healthy check");
        check(policy.choose(worldId, 25.0) == ViewDistanceManager.Adjustment.INCREASE,
                "continued healthy checks did not continue increasing the distance");

        check(policy.choose(worldId, 45.0) == ViewDistanceManager.Adjustment.STAY,
                "neutral MSPT caused an adjustment");
        check(policy.choose(worldId, 25.0) == ViewDistanceManager.Adjustment.STAY,
                "neutral MSPT did not reset the increase streak");
        check(policy.choose(worldId, ViewDistanceManager.DECREASE_MSPT_THRESHOLD)
                        == ViewDistanceManager.Adjustment.DECREASE,
                "high MSPT did not decrease the distance immediately");
        check(policy.choose(worldId, Double.NaN) == ViewDistanceManager.Adjustment.STAY,
                "invalid MSPT caused an adjustment");
    }

    private static void checkAdjustmentSwitch() {
        ViewDistanceManager.AdjustmentSwitch adjustmentSwitch =
                new ViewDistanceManager.AdjustmentSwitch();
        check(!adjustmentSwitch.isPaused(), "dynamic adjustment started paused");
        check(!adjustmentSwitch.resume(), "running adjustment reported a resume transition");
        check(adjustmentSwitch.pause(), "running adjustment could not be paused");
        check(adjustmentSwitch.isPaused(), "pause transition did not persist");
        check(!adjustmentSwitch.pause(), "paused adjustment reported a second pause transition");
        check(adjustmentSwitch.resume(), "paused adjustment could not be resumed");
        check(!adjustmentSwitch.isPaused(), "resume transition did not persist");
    }

    private static void checkManualBounds() {
        ViewDistanceManager.Bounds bounds = ViewDistanceManager.Bounds.checked("view", 8, 16);
        ViewDistanceManager.requireInBounds("view", 8, bounds);
        ViewDistanceManager.requireInBounds("view", 16, bounds);
        expectInvalidDistance(7, bounds);
        expectInvalidDistance(17, bounds);
    }

    private static void expectInvalidDistance(
            int distance,
            ViewDistanceManager.Bounds bounds) {
        try {
            ViewDistanceManager.requireInBounds("view", distance, bounds);
            throw new AssertionError("manual distance outside configured bounds was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void checkCommandSurface() {
        ViewDistanceCommand command = new ViewDistanceCommand(() -> null);
        List<String> messages = new ArrayList<>();
        CommandSender administrator = commandSender(true, messages);

        List<String> completions =
                command.tabComplete(administrator, new String[]{"viewdistance", ""});
        check(completions.equals(List.of("status", "set", "pause", "resume")),
                "view-distance admin subcommands are incomplete");

        command.handle(administrator, new String[]{"viewdistance", "pause"});
        check(messages.stream().anyMatch(message -> message.contains("disabled")),
                "command did not explain that the adaptive manager is inactive");

        messages.clear();
        CommandSender denied = commandSender(false, messages);
        command.handle(denied, new String[]{"viewdistance", "pause"});
        check(messages.stream().anyMatch(message -> message.contains("permission")),
                "view-distance admin command did not enforce its permission");
    }

    private static CommandSender commandSender(boolean permitted, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permitted;
                    case "sendMessage" -> {
                        if (args != null) {
                            for (Object argument : args) {
                                if (argument instanceof String message) {
                                    messages.add(message);
                                } else if (argument instanceof Component component) {
                                    messages.add(PlainTextComponentSerializer.plainText()
                                            .serialize(component));
                                }
                            }
                        }
                        yield null;
                    }
                    case "getName" -> "view-distance test sender";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "view-distance test sender";
                    default -> null;
                });
    }

    private static void checkWorldDistanceChanges() {
        WorldStub stub = new WorldStub(5, 20);
        ViewDistanceManager.Bounds simulation =
                ViewDistanceManager.Bounds.checked("simulation", 6, 12);
        ViewDistanceManager.Bounds view =
                ViewDistanceManager.Bounds.checked("view", 8, 16);

        ViewDistanceManager.clampWorld(stub.world, simulation, view);
        check(stub.simulationDistance.get() == 6, "simulation distance was not clamped on start");
        check(stub.viewDistance.get() == 16, "view distance was not clamped on start");

        ViewDistanceManager.applyAdjustment(
                stub.world, simulation, view, ViewDistanceManager.Adjustment.INCREASE);
        check(stub.simulationDistance.get() == 7, "simulation distance did not increase by one");
        check(stub.viewDistance.get() == 16, "view distance crossed its configured maximum");

        stub.simulationDistance.set(6);
        stub.viewDistance.set(8);
        ViewDistanceManager.applyAdjustment(
                stub.world, simulation, view, ViewDistanceManager.Adjustment.DECREASE);
        check(stub.simulationDistance.get() == 6, "simulation distance crossed its configured minimum");
        check(stub.viewDistance.get() == 8, "view distance crossed its configured minimum");

        int simulationWrites = stub.simulationWrites.get();
        int viewWrites = stub.viewWrites.get();
        ViewDistanceManager.applyAdjustment(
                stub.world, simulation, view, ViewDistanceManager.Adjustment.STAY);
        check(stub.simulationWrites.get() == simulationWrites,
                "unchanged simulation distance was written again");
        check(stub.viewWrites.get() == viewWrites,
                "unchanged view distance was written again");
    }

    private static void checkAdvertisedRadiusRewrite() {
        ClientboundSetChunkCacheRadiusPacket lower =
                new ClientboundSetChunkCacheRadiusPacket(8);
        Object rewritten = ClientViewRadiusBridge.keepAdvertisedRadius(lower, 16);
        check(rewritten instanceof ClientboundSetChunkCacheRadiusPacket,
                "view-radius packet was replaced with a different packet type");
        check(((ClientboundSetChunkCacheRadiusPacket) rewritten).getRadius() == 16,
                "lower dynamic radius was still advertised to the client");
        check(rewritten != lower, "immutable view-radius packet was not replaced");

        ClientboundSetChunkCacheRadiusPacket matching =
                new ClientboundSetChunkCacheRadiusPacket(16);
        check(ClientViewRadiusBridge.keepAdvertisedRadius(matching, 16) == matching,
                "matching advertised radius caused an unnecessary packet replacement");

        Object unrelated = new Object();
        check(ClientViewRadiusBridge.keepAdvertisedRadius(unrelated, 16) == unrelated,
                "unrelated outbound packet was modified");
    }

    private static void checkClientBridgeLifecycle() {
        boolean handlesJoin = false;
        for (Method method : ClientViewRadiusBridge.class.getDeclaredMethods()) {
            if (method.getAnnotation(EventHandler.class) == null
                    || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> eventType = method.getParameterTypes()[0];
            check(eventType != PlayerLoginEvent.class,
                    "view-radius bridge attaches before the player connection is available");
            handlesJoin |= eventType == PlayerJoinEvent.class;
        }
        check(handlesJoin, "view-radius bridge no longer attaches after a player joins");
    }

    private static void checkConfigurationContractAndAttribution() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(readResource("modules/tweaks.yml"));

        ViewDistanceManager.Settings settings = ViewDistanceManager.readSettings(config);
        check(!settings.enabled(), "view-distance tweak is not disabled by default");

        config.set(ViewDistanceManager.CONFIG_ROOT + ".enabled", true);
        settings = ViewDistanceManager.readSettings(config);
        check(settings.simulationBounds().equals(new ViewDistanceManager.Bounds(6, 12)),
                "bundled simulation-distance bounds changed");
        check(settings.viewBounds().equals(new ViewDistanceManager.Bounds(8, 16)),
                "bundled view-distance bounds changed");

        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", 4);
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.maximum", 10);
        config.set(ViewDistanceManager.CONFIG_ROOT + ".view-distance.minimum", 7);
        config.set(ViewDistanceManager.CONFIG_ROOT + ".view-distance.maximum", 18);
        settings = ViewDistanceManager.readSettings(config);
        check(settings.enabled(), "enabled view-distance config remained inactive");
        check(settings.simulationBounds().equals(new ViewDistanceManager.Bounds(4, 10)),
                "custom simulation-distance bounds were not parsed");
        check(settings.viewBounds().equals(new ViewDistanceManager.Bounds(7, 18)),
                "custom view-distance bounds were not parsed");

        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", "six");
        expectInvalidSettings(config, "non-integer distance was accepted");
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", 11);
        expectInvalidSettings(config, "minimum greater than maximum was accepted");
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", 4);
        config.set(ViewDistanceManager.CONFIG_ROOT + ".enabled", "sometimes");
        expectInvalidSettings(config, "non-boolean enabled flag was accepted");

        String license = readResource("view-distance-tweaks/LICENSE");
        check(license.contains("Copyright (c) 2020 froobynooby"),
                "ViewDistanceTweaks attribution is missing");
        check(license.contains("Permission is hereby granted, free of charge"),
                "ViewDistanceTweaks MIT permission notice is incomplete");
        check(license.contains("THE SOFTWARE IS PROVIDED \"AS IS\""),
                "ViewDistanceTweaks MIT warranty notice is incomplete");

        String pluginYml = readResource("plugin.yml");
        check(pluginYml.contains("crabutilities.viewdistance.admin:"),
                "view-distance admin permission is missing");
        check(pluginYml.contains("<reload|update|viewdistance>"),
                "view-distance admin command is missing from command usage");
    }

    private static void expectInvalidSettings(YamlConfiguration config, String message) {
        try {
            ViewDistanceManager.readSettings(config);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static String readResource(String name) throws Exception {
        try (var input = ViewDistanceManagerRegressionTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            check(input != null, "bundled resource is missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class WorldStub {
        private final AtomicInteger simulationDistance;
        private final AtomicInteger viewDistance;
        private final AtomicInteger simulationWrites = new AtomicInteger();
        private final AtomicInteger viewWrites = new AtomicInteger();
        private final World world;

        private WorldStub(int simulationDistance, int viewDistance) {
            this.simulationDistance = new AtomicInteger(simulationDistance);
            this.viewDistance = new AtomicInteger(viewDistance);
            this.world = (World) Proxy.newProxyInstance(
                    World.class.getClassLoader(),
                    new Class<?>[]{World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getSimulationDistance" -> this.simulationDistance.get();
                        case "setSimulationDistance" -> {
                            this.simulationDistance.set((int) args[0]);
                            this.simulationWrites.incrementAndGet();
                            yield null;
                        }
                        case "getViewDistance" -> this.viewDistance.get();
                        case "setViewDistance" -> {
                            this.viewDistance.set((int) args[0]);
                            this.viewWrites.incrementAndGet();
                            yield null;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "view-distance world";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
