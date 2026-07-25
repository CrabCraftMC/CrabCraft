package crabcraft.net.crabUtilities.jade.protocol;

import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import crabcraft.net.crabUtilities.jade.protocol.provider.JadeProvider;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JadeInventoryPolicyRegressionTest {

    private static final Set<String> INVENTORY_PROVIDER_IDS = Set.of(
            "minecraft:item_storage",
            "minecraft:furnace",
            "minecraft:shelf",
            "minecraft:lectern",
            "minecraft:jukebox",
            "minecraft:campfire");

    private JadeInventoryPolicyRegressionTest() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        JadeBootstrap.LOGGER = LoggerFactory.getLogger(JadeInventoryPolicyRegressionTest.class);
        JadeProtocol.init(false);

        Set<String> registered = registeredProviderIds();
        check(JadeProtocol.isActive(), "Jade did not become active after initialisation");
        check(registered.stream().noneMatch(INVENTORY_PROVIDER_IDS::contains),
                "inventory-bearing Jade providers were registered while disabled: " + registered);
        check(!registered.isEmpty(), "disabling inventory data disabled every Jade provider");

        JadeProtocol.init(true);
        Set<String> inventoryEnabled = registeredProviderIds();
        check(inventoryEnabled.stream().anyMatch(INVENTORY_PROVIDER_IDS::contains),
                "inventory-bearing Jade providers were not registered when enabled");

        JadeProtocol.init(false);
        Set<String> reloaded = registeredProviderIds();
        check(reloaded.equals(registered),
                "reloading Jade retained stale or duplicate providers: " + reloaded);

        JadeProtocol.shutdown();
        check(!JadeProtocol.isActive(), "Jade remained active after shutdown");
    }

    private static Set<String> registeredProviderIds() {
        return Stream.of(
                        JadeProtocol.blockDataProviders.entries(),
                        JadeProtocol.entityDataProviders.entries(),
                        JadeProtocol.itemStorageProviders.entries())
                .flatMap(stream -> stream)
                .flatMap(entry -> entry.getValue().stream())
                .map(JadeProvider::getUid)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
