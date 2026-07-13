package net.crabcraft.customdiscs.api.event;

import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired synchronously (on the server/region thread) when audio playback starts,
 * before the audio channel is created. Cancel it to prevent playback.
 */
public class AudioStartPlayingEvent extends Event implements Cancellable {
  private static final HandlerList HANDLER_LIST = new HandlerList();
  private boolean isCancelled;

  private final Block block;
  private final String identifier;

  public AudioStartPlayingEvent(Block block, String identifier) {
    super(false);
    this.block = block;
    this.identifier = identifier;

    this.isCancelled = false;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLER_LIST;
  }

  @NotNull
  public static HandlerList getHandlerList() {
    return HANDLER_LIST;
  }

  @Override
  public boolean isCancelled() {
    return this.isCancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    this.isCancelled = cancel;
  }

  /**
   * Returns the block where the music starts playing.
   *
   * @return The block where the audio originated.
   */
  @NotNull
  public Block getBlock() {
    return block;
  }

  /**
   * Returns the identifier of the track that is starting to play.
   *
   * @return The source identifier (stream URL) of the track.
   */
  @NotNull
  public String getIdentifier() {
    return identifier;
  }
}
