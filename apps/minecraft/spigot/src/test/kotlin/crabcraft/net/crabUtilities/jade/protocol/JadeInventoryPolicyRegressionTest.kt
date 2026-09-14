package crabcraft.net.crabUtilities.jade.protocol

import crabcraft.net.crabUtilities.jade.JadeBootstrap
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.slf4j.LoggerFactory

object JadeInventoryPolicyRegressionTest {

    private val INVENTORY_PROVIDER_IDS = setOf(
        "minecraft:item_storage",
        "minecraft:furnace",
        "minecraft:shelf",
        "minecraft:lectern",
        "minecraft:jukebox",
        "minecraft:campfire")

    @JvmStatic
    fun main(args: Array<String>) {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        JadeBootstrap.LOGGER = LoggerFactory.getLogger(JadeInventoryPolicyRegressionTest::class.java)
        JadeProtocol.init(false)

        val registered = registeredProviderIds()
        check(JadeProtocol.isActive(), "Jade did not become active after initialisation")
        check(registered.none(INVENTORY_PROVIDER_IDS::contains),
            "inventory-bearing Jade providers were registered while disabled: $registered")
        check(registered.isNotEmpty(), "disabling inventory data disabled every Jade provider")

        JadeProtocol.init(true)
        val inventoryEnabled = registeredProviderIds()
        check(inventoryEnabled.any(INVENTORY_PROVIDER_IDS::contains),
            "inventory-bearing Jade providers were not registered when enabled")

        JadeProtocol.init(false)
        val reloaded = registeredProviderIds()
        check(reloaded == registered,
            "reloading Jade retained stale or duplicate providers: $reloaded")

        JadeProtocol.shutdown()
        check(!JadeProtocol.isActive(), "Jade remained active after shutdown")
    }

    private fun registeredProviderIds(): Set<String> {
        val registered = mutableSetOf<String>()
        JadeProtocol.blockDataProviders.entries().forEach { entry ->
            entry.value.forEach { registered.add(it.getUid().toString()) }
        }
        JadeProtocol.entityDataProviders.entries().forEach { entry ->
            entry.value.forEach { registered.add(it.getUid().toString()) }
        }
        JadeProtocol.itemStorageProviders.entries().forEach { entry ->
            entry.value.forEach { registered.add(it.getUid().toString()) }
        }
        return registered
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
