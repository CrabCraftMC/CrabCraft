package crabcraft.net.crabUtilities.media.item;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

public final class MediaClearRegressionTest {
  public static void main(String[] args) {
    verifyDiscMetadataClearing();
    verifyHornMetadataClearing();
  }

  private static void verifyDiscMetadataClearing() {
    verifyClearing(
      Set.of("remote", "volume", "distance", "name"),
      MediaItemCodec::clearDiscData,
      "disc");
  }

  private static void verifyHornMetadataClearing() {
    verifyClearing(
      Set.of("horn_remote", "horn_volume", "horn_name", "horn_original_instrument"),
      MediaItemCodec::clearHornData,
      "horn");
  }

  private static void verifyClearing(
    Set<String> ownedKeyNames,
    java.util.function.Consumer<PersistentDataContainer> clear,
    String itemType
  ) {
    NamespacedKey foreignKey = NamespacedKey.fromString("example:foreign");
    check(foreignKey != null, "foreign test key could not be created");

    Set<NamespacedKey> remainingKeys = new HashSet<>();
    for (String key : ownedKeyNames) {
      NamespacedKey namespacedKey = NamespacedKey.fromString("customdiscs:" + key);
      check(namespacedKey != null, "owned test key could not be created: " + key);
      remainingKeys.add(namespacedKey);
    }
    remainingKeys.add(foreignKey);

    clear.accept(containerFor(remainingKeys));
    check(remainingKeys.equals(Set.of(foreignKey)),
      "clearing a " + itemType + " must remove owned keys and preserve foreign metadata");
  }

  private static PersistentDataContainer containerFor(Set<NamespacedKey> remainingKeys) {
    return (PersistentDataContainer) Proxy.newProxyInstance(
      PersistentDataContainer.class.getClassLoader(),
      new Class<?>[]{PersistentDataContainer.class},
      (proxy, method, arguments) -> {
        if (method.getName().equals("remove")) {
          remainingKeys.remove((NamespacedKey) arguments[0]);
          return null;
        }
        throw new UnsupportedOperationException(method.getName());
      });
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
