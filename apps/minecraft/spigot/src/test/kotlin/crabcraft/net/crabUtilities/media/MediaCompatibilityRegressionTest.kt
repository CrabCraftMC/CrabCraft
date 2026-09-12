package crabcraft.net.crabUtilities.media

import crabcraft.net.crabUtilities.media.item.MediaItemCodec

/**
 * Locks the item metadata contract used by discs and horns already in player
 * inventories and world containers.
 */
object MediaCompatibilityRegressionTest {
  private const val ITEM_NAMESPACE = "customdiscs"

  @JvmStatic
  fun main(args: Array<String>) {
    for ((name, key) in MediaItemCodec.compatibilityKeys()) {
      check(key.namespace == ITEM_NAMESPACE, "item namespace changed for " + name)
      check(key.key == name, "item key changed for " + name)
    }
    check(MediaItemCodec.compatibilityKeys().size == 8,
      "a persisted media key was removed from the compatibility contract")
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }
}
