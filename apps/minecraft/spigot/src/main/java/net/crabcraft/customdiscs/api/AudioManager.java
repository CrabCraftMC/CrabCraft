package net.crabcraft.customdiscs.api;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * High-level manager for custom audio playback using the Simple Voice Chat API.
 * <p>
 * This manager coordinates audio loading, spatial channel creation, and player
 * synchronization for jukebox-like behavior at specific world coordinates.
 */
public interface AudioManager {
  /**
   * Starts audio playback at the specified block location.
   * <p>
   * A {@link LocationalAudioChannel} is created at the block's center position and audio is
   * streamed to all players within the configured jukebox distance. If playback is already
   * active at this block, this method is a no-op.
   * <p>
   * A persistent "Now Playing" action bar is shown to all players within range,
   * updating dynamically as players enter or leave the area.
   *
   * @param block      The block (typically a jukebox) acting as the audio source.
   * @param identifier The source identifier (stream URL or unique ID) used to load the audio.
   */
  void play(@NotNull Block block, @NotNull String identifier);

  /**
   * Determines if a custom audio track is currently being broadcasted from the given block.
   *
   * @param block The block location to verify.
   * @return {@code true} if an active audio session exists at this location; {@code false} otherwise.
   */
  boolean isPlaying(@NotNull Block block);

  /**
   * Stops audio playback at the specified block and releases all associated resources.
   * <p>
   * This includes closing the {@link LocationalAudioChannel} and terminating
   * the audio track.
   *
   * @param block The block where playback should be terminated.
   */
  void stopPlaying(@NotNull Block block);

  /**
   * Terminates all active audio sessions across the entire server.
   * <p>
   * This is typically used during plugin disablement or administrative resets.
   */
  void stopPlayingAll();

  /**
   * Retrieves the spatial audio channel associated with a specific block.
   *
   * @param block The block where playback is occurring.
   * @return The {@link LocationalAudioChannel} being used for broadcast,
   * or {@code null} if no audio is playing at this location.
   */
  @Nullable
  LocationalAudioChannel getAudioChannel(@NotNull Block block);

  /**
   * Retrieves the collection of players who were within the audible radius
   * when the track was initially triggered.
   *
   * @param block The block where playback is occurring.
   * @return A {@link Collection} of {@link ServerPlayer}s who received
   * the initial broadcast, or {@code null} if no session is active.
   */
  @Nullable
  Collection<ServerPlayer> getPlayersInRangeAtStart(@NotNull Block block);
}
