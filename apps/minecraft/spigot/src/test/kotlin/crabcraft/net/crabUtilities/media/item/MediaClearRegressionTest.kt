package crabcraft.net.crabUtilities.media.item

import java.lang.reflect.Proxy
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataContainer

object MediaClearRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        verifyClearing(setOf("remote", "volume", "distance", "name"), MediaItemCodec::clearDiscData, "disc")
        verifyClearing(
            setOf("horn_remote", "horn_volume", "horn_name", "horn_original_instrument"),
            MediaItemCodec::clearHornData,
            "horn",
        )
    }

    private fun verifyClearing(ownedKeyNames: Set<String>, clear: (PersistentDataContainer) -> Unit, itemType: String) {
        val foreignKey = NamespacedKey.fromString("example:foreign")
        check(foreignKey != null, "foreign test key could not be created")
        val remainingKeys = HashSet<NamespacedKey>()
        for (key in ownedKeyNames) {
            val namespacedKey = NamespacedKey.fromString("customdiscs:$key")
            check(namespacedKey != null, "owned test key could not be created: $key")
            remainingKeys.add(namespacedKey!!)
        }
        remainingKeys.add(foreignKey!!)
        clear(containerFor(remainingKeys))
        check(
            remainingKeys == setOf(foreignKey),
            "clearing a $itemType must remove owned keys and preserve foreign metadata",
        )
    }

    private fun containerFor(remainingKeys: MutableSet<NamespacedKey>) =
        Proxy.newProxyInstance(
            PersistentDataContainer::class.java.classLoader,
            arrayOf(PersistentDataContainer::class.java),
        ) { _, method, arguments ->
            if (method.name == "remove") {
                remainingKeys.remove(arguments!![0] as NamespacedKey)
                null
            } else throw UnsupportedOperationException(method.name)
        } as PersistentDataContainer

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
