package net.crabcraft.customdiscs.api;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a custom music disc entry within the system.
 * <p>
 * This class acts as a data container that links a physical Minecraft {@link ItemStack}
 * with its corresponding audio source metadata and identifier.
 */
@SuppressWarnings("ClassCanBeRecord")
public class DiscEntry {
  private final ItemStack disc;
  private final Component name;
  private final String identifier;
  private final boolean local;
  private final float volume;
  private final int distance;

  /**
   * Constructs a new DiscEntry.
   *
   * @param disc       The {@link ItemStack} representing the disc in game.
   * @param name       The display name of the track (e.g., used for action bars or tooltips).
   * @param identifier The source identifier (a stream URL) used to load the audio.
   * @param local      Deprecated and unused; always {@code false} (local-file discs were removed).
   */
  public DiscEntry(ItemStack disc, Component name, String identifier, boolean local) {
    this(disc, name, identifier, local, 1.0f, 0);
  }

  public DiscEntry(ItemStack disc, Component name, String identifier, boolean local, float volume) {
    this(disc, name, identifier, local, volume, 0);
  }

  public DiscEntry(ItemStack disc, Component name, String identifier, boolean local, float volume, int distance) {
    this.disc = disc;
    this.name = name;
    this.identifier = identifier;
    this.local = local;
    this.volume = volume;
    this.distance = distance;
  }

  /**
   * Returns the physical item associated with this music disc.
   *
   * @return The {@link ItemStack} of the disc.
   */
  @NotNull
  public ItemStack getDisc() {
    return disc;
  }

  /**
   * Returns the formatted display name of the disc track.
   * Usually displayed to the player when the disc starts playing.
   *
   * @return The track name as an Adventure {@link Component}.
   */
  @NotNull
  public Component getName() {
    return name;
  }

  /**
   * Returns the unique identifier of the audio source.
   * This string is used by the audio engine to resolve and play the track.
   *
   * @return The source string (a stream URL).
   */
  @NotNull
  public String getIdentifier() {
    return identifier;
  }

  /**
   * Returns the per-disc volume multiplier (1.0 = source volume; e.g. 0.5 = half, 2.0 = double).
   *
   * @return the volume multiplier.
   */
  public float getVolume() {
    return volume;
  }

  /**
   * Returns the per-disc audio range in blocks, or 0 to use the jukebox/server default.
   *
   * @return the range in blocks, or 0 for the default.
   */
  public int getDistance() {
    return distance;
  }

  /**
   * @deprecated Local-file discs were removed; this always returns {@code false}.
   * @return {@code false}
   */
  @Deprecated
  public boolean isLocal() {
    return local;
  }
}
