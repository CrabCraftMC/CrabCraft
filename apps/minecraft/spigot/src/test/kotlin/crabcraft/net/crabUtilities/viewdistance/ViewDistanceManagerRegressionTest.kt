package crabcraft.net.crabUtilities.viewdistance

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object ViewDistanceManagerRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        checkBoundsValidation()
        checkTickDurationMedian()
        checkAdjustmentHysteresis()
        checkAdjustmentSwitch()
        checkManualBounds()
        checkCommandSurface()
        checkWorldDistanceChanges()
        checkAdvertisedRadiusRewrite()
        checkClientBridgeLifecycle()
        checkConfigurationContractAndAttribution()
    }

    private fun checkBoundsValidation() {
        val bounds = ViewDistanceManager.Bounds.checked("test", 6, 12)
        check(bounds.clamp(5) == 6, "distance was not clamped to its minimum")
        check(bounds.clamp(9) == 9, "in-range distance was changed")
        check(bounds.clamp(13) == 12, "distance was not clamped to its maximum")
        expectInvalidBounds(1, 12)
        expectInvalidBounds(6, 33)
        expectInvalidBounds(12, 6)
    }

    private fun expectInvalidBounds(minimum: Int, maximum: Int) {
        try {
            ViewDistanceManager.Bounds.checked("test", minimum, maximum)
            throw AssertionError("invalid bounds were accepted: $minimum–$maximum")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun checkTickDurationMedian() {
        val window = ViewDistanceManager.TickDurationWindow(3)
        check(window.median(25.0) == 25.0, "empty MSPT window did not use its fallback")
        window.add(50.0)
        window.add(10.0)
        check(window.median(25.0) == 30.0, "even-sized MSPT median is incorrect")
        window.add(30.0)
        check(window.median(25.0) == 30.0, "odd-sized MSPT median is incorrect")
        // Capacity is three, so the oldest 50ms sample must be discarded.
        window.add(20.0)
        check(window.median(25.0) == 20.0, "MSPT window did not discard its oldest sample")
        window.add(Double.NaN)
        window.add(-1.0)
        check(window.median(25.0) == 20.0, "invalid MSPT samples changed the window")
    }

    private fun checkAdjustmentHysteresis() {
        val policy = ViewDistanceManager.AdjustmentPolicy()
        val worldId = UUID.randomUUID()
        for (count in 1 until ViewDistanceManager.PASSED_CHECKS_FOR_INCREASE) {
            check(policy.choose(worldId, ViewDistanceManager.INCREASE_MSPT_THRESHOLD) == ViewDistanceManager.Adjustment.STAY,
                "view distance increased before the low-MSPT hysteresis passed")
        }
        check(policy.choose(worldId, ViewDistanceManager.INCREASE_MSPT_THRESHOLD) == ViewDistanceManager.Adjustment.INCREASE,
            "view distance did not increase on the tenth healthy check")
        check(policy.choose(worldId, 25.0) == ViewDistanceManager.Adjustment.INCREASE,
            "continued healthy checks did not continue increasing the distance")
        check(policy.choose(worldId, 45.0) == ViewDistanceManager.Adjustment.STAY, "neutral MSPT caused an adjustment")
        check(policy.choose(worldId, 25.0) == ViewDistanceManager.Adjustment.STAY, "neutral MSPT did not reset the increase streak")
        check(policy.choose(worldId, ViewDistanceManager.DECREASE_MSPT_THRESHOLD) == ViewDistanceManager.Adjustment.DECREASE,
            "high MSPT did not decrease the distance immediately")
        check(policy.choose(worldId, Double.NaN) == ViewDistanceManager.Adjustment.STAY, "invalid MSPT caused an adjustment")
    }

    private fun checkAdjustmentSwitch() {
        val adjustmentSwitch = ViewDistanceManager.AdjustmentSwitch()
        check(!adjustmentSwitch.isPaused(), "dynamic adjustment started paused")
        check(!adjustmentSwitch.resume(), "running adjustment reported a resume transition")
        check(adjustmentSwitch.pause(), "running adjustment could not be paused")
        check(adjustmentSwitch.isPaused(), "pause transition did not persist")
        check(!adjustmentSwitch.pause(), "paused adjustment reported a second pause transition")
        check(adjustmentSwitch.resume(), "paused adjustment could not be resumed")
        check(!adjustmentSwitch.isPaused(), "resume transition did not persist")
    }

    private fun checkManualBounds() {
        val bounds = ViewDistanceManager.Bounds.checked("view", 8, 16)
        ViewDistanceManager.requireInBounds("view", 8, bounds)
        ViewDistanceManager.requireInBounds("view", 16, bounds)
        expectInvalidDistance(7, bounds)
        expectInvalidDistance(17, bounds)
    }

    private fun expectInvalidDistance(distance: Int, bounds: ViewDistanceManager.Bounds) {
        try {
            ViewDistanceManager.requireInBounds("view", distance, bounds)
            throw AssertionError("manual distance outside configured bounds was accepted")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun checkCommandSurface() {
        val command = ViewDistanceCommand { null }
        val messages = ArrayList<String>()
        val administrator = commandSender(true, messages)
        val completions = command.tabComplete(administrator, arrayOf("viewdistance", ""))
        check(completions == listOf("status", "set", "pause", "resume"), "view-distance admin subcommands are incomplete")
        command.handle(administrator, arrayOf("viewdistance", "pause"))
        check(messages.any { it.contains("disabled") }, "command did not explain that the adaptive manager is inactive")
        messages.clear()
        val denied = commandSender(false, messages)
        command.handle(denied, arrayOf("viewdistance", "pause"))
        check(messages.any { it.contains("permission") }, "view-distance admin command did not enforce its permission")
    }

    private fun commandSender(permitted: Boolean, messages: MutableList<String>): CommandSender {
        val name = "synthetic-${UUID.randomUUID()}"
        return Proxy.newProxyInstance(CommandSender::class.java.classLoader, arrayOf(CommandSender::class.java)) { proxy, method, args ->
            when (method.name) {
                "hasPermission" -> permitted
                "sendMessage" -> {
                    if (args != null) {
                        for (argument in args) {
                            when (argument) {
                                is String -> messages.add(argument)
                                is Component -> messages.add(PlainTextComponentSerializer.plainText().serialize(argument))
                            }
                        }
                    }
                    null
                }
                "getName", "toString" -> name
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                else -> null
            }
        } as CommandSender
    }

    private fun checkWorldDistanceChanges() {
        val stub = WorldStub(5, 20)
        val simulation = ViewDistanceManager.Bounds.checked("simulation", 6, 12)
        val view = ViewDistanceManager.Bounds.checked("view", 8, 16)
        ViewDistanceManager.clampWorld(stub.world, simulation, view)
        check(stub.simulationDistance.get() == 6, "simulation distance was not clamped on start")
        check(stub.viewDistance.get() == 16, "view distance was not clamped on start")
        ViewDistanceManager.applyAdjustment(stub.world, simulation, view, ViewDistanceManager.Adjustment.INCREASE)
        check(stub.simulationDistance.get() == 7, "simulation distance did not increase by one")
        check(stub.viewDistance.get() == 16, "view distance crossed its configured maximum")
        stub.simulationDistance.set(6)
        stub.viewDistance.set(8)
        ViewDistanceManager.applyAdjustment(stub.world, simulation, view, ViewDistanceManager.Adjustment.DECREASE)
        check(stub.simulationDistance.get() == 6, "simulation distance crossed its configured minimum")
        check(stub.viewDistance.get() == 8, "view distance crossed its configured minimum")
        val simulationWrites = stub.simulationWrites.get()
        val viewWrites = stub.viewWrites.get()
        ViewDistanceManager.applyAdjustment(stub.world, simulation, view, ViewDistanceManager.Adjustment.STAY)
        check(stub.simulationWrites.get() == simulationWrites, "unchanged simulation distance was written again")
        check(stub.viewWrites.get() == viewWrites, "unchanged view distance was written again")
    }

    private fun checkAdvertisedRadiusRewrite() {
        val lower = ClientboundSetChunkCacheRadiusPacket(8)
        val rewritten = ClientViewRadiusBridge.keepAdvertisedRadius(lower, 16)
        check(rewritten is ClientboundSetChunkCacheRadiusPacket, "view-radius packet was replaced with a different packet type")
        check((rewritten as ClientboundSetChunkCacheRadiusPacket).getRadius() == 16, "lower dynamic radius was still advertised to the client")
        check(rewritten !== lower, "immutable view-radius packet was not replaced")
        val matching = ClientboundSetChunkCacheRadiusPacket(16)
        check(ClientViewRadiusBridge.keepAdvertisedRadius(matching, 16) === matching, "matching advertised radius caused an unnecessary packet replacement")
        val unrelated = Any()
        check(ClientViewRadiusBridge.keepAdvertisedRadius(unrelated, 16) === unrelated, "unrelated outbound packet was modified")
    }

    private fun checkClientBridgeLifecycle() {
        var handlesJoin = false
        for (method in ClientViewRadiusBridge::class.java.declaredMethods) {
            if (method.getAnnotation(EventHandler::class.java) == null || method.parameterCount != 1) continue
            val eventType = method.parameterTypes[0]
            check(eventType != PlayerLoginEvent::class.java, "view-radius bridge attaches before the player connection is available")
            handlesJoin = handlesJoin || eventType == PlayerJoinEvent::class.java
        }
        check(handlesJoin, "view-radius bridge no longer attaches after a player joins")
    }

    private fun checkConfigurationContractAndAttribution() {
        val config = YamlConfiguration()
        config.loadFromString(readResource("modules/tweaks.yml"))
        var settings = ViewDistanceManager.readSettings(config)
        check(!settings.enabled(), "view-distance tweak is not disabled by default")
        config.set(ViewDistanceManager.CONFIG_ROOT + ".enabled", true)
        settings = ViewDistanceManager.readSettings(config)
        check(settings.simulationBounds() == ViewDistanceManager.Bounds(6, 12), "bundled simulation-distance bounds changed")
        check(settings.viewBounds() == ViewDistanceManager.Bounds(8, 16), "bundled view-distance bounds changed")
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", 4)
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.maximum", 10)
        config.set(ViewDistanceManager.CONFIG_ROOT + ".view-distance.minimum", 7)
        config.set(ViewDistanceManager.CONFIG_ROOT + ".view-distance.maximum", 18)
        settings = ViewDistanceManager.readSettings(config)
        check(settings.enabled(), "enabled view-distance config remained inactive")
        check(settings.simulationBounds() == ViewDistanceManager.Bounds(4, 10), "custom simulation-distance bounds were not parsed")
        check(settings.viewBounds() == ViewDistanceManager.Bounds(7, 18), "custom view-distance bounds were not parsed")
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", "six")
        expectInvalidSettings(config, "non-integer distance was accepted")
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", 11)
        expectInvalidSettings(config, "minimum greater than maximum was accepted")
        config.set(ViewDistanceManager.CONFIG_ROOT + ".simulation-distance.minimum", 4)
        config.set(ViewDistanceManager.CONFIG_ROOT + ".enabled", "sometimes")
        expectInvalidSettings(config, "non-boolean enabled flag was accepted")
        val license = readResource("view-distance-tweaks/LICENSE")
        check(license.contains("Copyright (c) 2020 froobynooby"), "ViewDistanceTweaks attribution is missing")
        check(license.contains("Permission is hereby granted, free of charge"), "ViewDistanceTweaks MIT permission notice is incomplete")
        check(license.contains("THE SOFTWARE IS PROVIDED \"AS IS\""), "ViewDistanceTweaks MIT warranty notice is incomplete")
        val pluginYml = readResource("plugin.yml")
        check(pluginYml.contains("crabutilities.viewdistance.admin:"), "view-distance admin permission is missing")
        check(pluginYml.contains("<reload|update|viewdistance>"), "view-distance admin command is missing from command usage")
    }

    private fun expectInvalidSettings(config: YamlConfiguration, message: String) {
        try {
            ViewDistanceManager.readSettings(config)
            throw AssertionError(message)
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun readResource(name: String): String = ViewDistanceManagerRegressionTest::class.java.classLoader.getResourceAsStream(name).use { input ->
        check(input != null, "bundled resource is missing: $name")
        String(input!!.readAllBytes(), StandardCharsets.UTF_8)
    }

    private class WorldStub(simulationDistance: Int, viewDistance: Int) {
        val simulationDistance = AtomicInteger(simulationDistance)
        val viewDistance = AtomicInteger(viewDistance)
        val simulationWrites = AtomicInteger()
        val viewWrites = AtomicInteger()
        private val name = "synthetic-${UUID.randomUUID()}"
        val world: World = Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { proxy, method, args ->
            when (method.name) {
                "getSimulationDistance" -> this.simulationDistance.get()
                "setSimulationDistance" -> { this.simulationDistance.set(args!![0] as Int); simulationWrites.incrementAndGet(); null }
                "getViewDistance" -> this.viewDistance.get()
                "setViewDistance" -> { this.viewDistance.set(args!![0] as Int); viewWrites.incrementAndGet(); null }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> throw UnsupportedOperationException(method.name)
            }
        } as World
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
