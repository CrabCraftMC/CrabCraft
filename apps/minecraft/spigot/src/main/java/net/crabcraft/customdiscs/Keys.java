package net.crabcraft.customdiscs;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;

public class Keys {
  public static final Key<String> REMOTE_DISC = Key.create("remote", PersistentDataType.STRING);
  public static final Key<Float> DISC_VOLUME = Key.create("volume", PersistentDataType.FLOAT);
  public static final Key<Integer> DISC_DISTANCE = Key.create("distance", PersistentDataType.INTEGER);
  public static final Key<String> DISC_NAME = Key.create("name", PersistentDataType.STRING);

  // Goat-horn keys are intentionally distinct from the disc keys above so a custom horn is
  // never mistaken for a custom disc (isCustomDisc keys on "remote").
  public static final Key<String> HORN_REMOTE = Key.create("horn_remote", PersistentDataType.STRING);
  public static final Key<Float> HORN_VOLUME = Key.create("horn_volume", PersistentDataType.FLOAT);
  public static final Key<String> HORN_NAME = Key.create("horn_name", PersistentDataType.STRING);

  @Deprecated(forRemoval = true)
  public static final Key<String> LEGACY_REMOTE_DISC = Key.create("remote-customdisc", PersistentDataType.STRING);
  @Deprecated(forRemoval = true)
  public static final Key<String> LEGACY_YOUTUBE_DISC = Key.create("customdiscyt", PersistentDataType.STRING);
  @Deprecated(forRemoval = true)
  public static final Key<String> LEGACY_SOUNDCLOUD_DISC = Key.create("customdiscsc", PersistentDataType.STRING);

  public record Key<T>(NamespacedKey key, PersistentDataType<T, T> dataType) {
    public static <Z> Key<Z> create(String key, PersistentDataType<Z, Z> dataType) {
      // Preserve the original namespace so discs created by the standalone
      // CustomDiscs plugin remain valid after migration into CrabUtilities.
      return new Key<>(Objects.requireNonNull(NamespacedKey.fromString("customdiscs:" + key)), dataType);
    }
  }
}
