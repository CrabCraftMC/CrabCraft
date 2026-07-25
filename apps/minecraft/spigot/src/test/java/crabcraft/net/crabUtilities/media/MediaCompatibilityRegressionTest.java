package crabcraft.net.crabUtilities.media;

import crabcraft.net.crabUtilities.media.item.MediaItemCodec;
import org.bukkit.NamespacedKey;

/**
 * Locks the item metadata contract used by discs and horns already in player
 * inventories and world containers.
 */
public final class MediaCompatibilityRegressionTest {
  private static final String ITEM_NAMESPACE = "customdiscs";

  public static void main(String[] args) {
    for (var entry : MediaItemCodec.compatibilityKeys().entrySet()) {
      NamespacedKey key = entry.getValue();
      check(key.getNamespace().equals(ITEM_NAMESPACE),
        "item namespace changed for " + entry.getKey());
      check(key.getKey().equals(entry.getKey()),
        "item key changed for " + entry.getKey());
    }
    check(MediaItemCodec.compatibilityKeys().size() == 7,
      "a persisted media key was removed from the compatibility contract");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
