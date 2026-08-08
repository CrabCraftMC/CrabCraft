package crabcraft.net.crabUtilities.media.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Encodes Crab Utilities playback data on items.
 *
 * <p>The namespace and key names are a save-format contract. They deliberately
 * retain their historical values so existing inventories and world containers
 * continue to work after the implementation change.</p>
 */
public final class MediaItemCodec {
  private static final NamespacedKey DISC_SOURCE = key("remote");
  private static final NamespacedKey DISC_VOLUME = key("volume");
  private static final NamespacedKey DISC_RANGE = key("distance");
  private static final NamespacedKey DISC_NAME = key("name");
  private static final NamespacedKey HORN_SOURCE = key("horn_remote");
  private static final NamespacedKey HORN_VOLUME = key("horn_volume");
  private static final NamespacedKey HORN_NAME = key("horn_name");
  private static final NamespacedKey HORN_ORIGINAL_INSTRUMENT = key("horn_original_instrument");

  private MediaItemCodec() {}

  public record DiscData(Component title, String source, float volume, int range, String storedName) {}
  public record HornData(String source, float volume, String storedName) {}

  public static boolean isMusicDiscHeld(Player player) {
    return player.getInventory().getItemInMainHand().getType().name().startsWith("MUSIC_DISC_");
  }

  public static boolean jukeboxHasRecord(Block block) {
    return block.getState() instanceof Jukebox jukebox
      && jukebox.getRecord().getType() != Material.AIR;
  }

  public static boolean isDisc(ItemStack item) {
    if (item == null || !item.hasItemMeta()) return false;
    ItemMeta meta = requireMeta(item);
    return meta.getPersistentDataContainer().has(DISC_SOURCE, PersistentDataType.STRING);
  }

  public static DiscData readDisc(ItemStack item) {
    if (!isDisc(item)) throw new IllegalArgumentException("Item has no media-disc source");
    ItemMeta meta = requireMeta(item);
    PersistentDataContainer data = meta.getPersistentDataContainer();
    String source = Objects.requireNonNull(data.get(DISC_SOURCE, PersistentDataType.STRING));
    Float volume = data.get(DISC_VOLUME, PersistentDataType.FLOAT);
    Integer range = data.get(DISC_RANGE, PersistentDataType.INTEGER);
    String storedName = data.get(DISC_NAME, PersistentDataType.STRING);
    return new DiscData(displayTitle(meta), source, volume == null ? 1f : volume,
      range == null ? 0 : range, storedName == null ? "" : storedName);
  }

  public static void writeDisc(
    ItemMeta meta,
    String source,
    String name,
    float volume,
    int range
  ) {
    PersistentDataContainer data = meta.getPersistentDataContainer();
    clearDiscData(data);
    data.set(DISC_SOURCE, PersistentDataType.STRING, source);
    data.set(DISC_VOLUME, PersistentDataType.FLOAT, volume);
    data.set(DISC_RANGE, PersistentDataType.INTEGER, range);
    data.set(DISC_NAME, PersistentDataType.STRING, name);
  }

  public static boolean isGoatHornHeld(Player player) {
    return player.getInventory().getItemInMainHand().getType() == Material.GOAT_HORN;
  }

  public static boolean isHorn(ItemStack item) {
    return item != null
      && item.getType() == Material.GOAT_HORN
      && item.hasItemMeta()
      && requireMeta(item).getPersistentDataContainer()
        .has(HORN_SOURCE, PersistentDataType.STRING);
  }

  public static HornData readHorn(ItemStack item) {
    if (!isHorn(item)) throw new IllegalArgumentException("Item has no media-horn source");
    PersistentDataContainer data = requireMeta(item).getPersistentDataContainer();
    String source = Objects.requireNonNull(data.get(HORN_SOURCE, PersistentDataType.STRING));
    Float volume = data.get(HORN_VOLUME, PersistentDataType.FLOAT);
    String storedName = data.get(HORN_NAME, PersistentDataType.STRING);
    return new HornData(source, volume == null ? 1f : volume, storedName == null ? "" : storedName);
  }

  public static void writeHorn(
    ItemMeta meta,
    String source,
    String name,
    float volume,
    String originalInstrument
  ) {
    PersistentDataContainer data = meta.getPersistentDataContainer();
    clearHornMediaData(data);
    if (originalInstrument != null) {
      data.set(HORN_ORIGINAL_INSTRUMENT, PersistentDataType.STRING, originalInstrument);
    }
    data.set(HORN_SOURCE, PersistentDataType.STRING, source);
    data.set(HORN_VOLUME, PersistentDataType.FLOAT, volume);
    data.set(HORN_NAME, PersistentDataType.STRING, name);
  }

  public static String readOriginalHornInstrument(ItemStack item) {
    if (!isHorn(item)) throw new IllegalArgumentException("Item has no media-horn source");
    return requireMeta(item).getPersistentDataContainer()
      .get(HORN_ORIGINAL_INSTRUMENT, PersistentDataType.STRING);
  }

  public static void clearDisc(ItemMeta meta) {
    clearDiscData(meta.getPersistentDataContainer());
  }

  public static void clearHorn(ItemMeta meta) {
    clearHornData(meta.getPersistentDataContainer());
  }

  public static ItemMeta requireMeta(ItemStack item) {
    ItemMeta meta = item.getItemMeta();
    if (meta == null) throw new IllegalArgumentException("Item has no metadata");
    return meta;
  }

  public static UUID playbackId(Block block) {
    long worldAndHeight = block.getWorld().getUID().getLeastSignificantBits() ^ block.getY();
    long horizontal = ((long) block.getX() << 32) | (block.getZ() & 0xFFFFFFFFL);
    return new UUID(worldAndHeight, horizontal);
  }

  public static Map<String, NamespacedKey> compatibilityKeys() {
    Map<String, NamespacedKey> keys = new LinkedHashMap<>();
    keys.put("remote", DISC_SOURCE);
    keys.put("volume", DISC_VOLUME);
    keys.put("distance", DISC_RANGE);
    keys.put("name", DISC_NAME);
    keys.put("horn_remote", HORN_SOURCE);
    keys.put("horn_volume", HORN_VOLUME);
    keys.put("horn_name", HORN_NAME);
    keys.put("horn_original_instrument", HORN_ORIGINAL_INSTRUMENT);
    return Map.copyOf(keys);
  }

  static void clearDiscData(PersistentDataContainer data) {
    data.remove(DISC_SOURCE);
    data.remove(DISC_VOLUME);
    data.remove(DISC_RANGE);
    data.remove(DISC_NAME);
  }

  static void clearHornData(PersistentDataContainer data) {
    clearHornMediaData(data);
    data.remove(HORN_ORIGINAL_INSTRUMENT);
  }

  private static void clearHornMediaData(PersistentDataContainer data) {
    data.remove(HORN_SOURCE);
    data.remove(HORN_VOLUME);
    data.remove(HORN_NAME);
  }

  private static Component displayTitle(ItemMeta meta) {
    List<Component> lore = meta.lore();
    return lore == null || lore.isEmpty()
      ? Component.text("Unknown", NamedTextColor.GRAY)
      : lore.getFirst();
  }

  private static NamespacedKey key(@NotNull String value) {
    return Objects.requireNonNull(NamespacedKey.fromString("customdiscs:" + value));
  }
}
