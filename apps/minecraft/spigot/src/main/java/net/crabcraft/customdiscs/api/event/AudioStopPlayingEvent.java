package net.crabcraft.customdiscs.api.event;

import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired synchronously (on the server/region thread) when audio playback stops,
 * whether stopped manually or finishing naturally.
 */
public class AudioStopPlayingEvent extends Event {
  private static final HandlerList HANDLER_LIST = new HandlerList();

  private final Block block;
  private final String identifier;

  public AudioStopPlayingEvent(Block block, String identifier) {
    super(false);
    this.block = block;
    this.identifier = identifier;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLER_LIST;
  }

  @NotNull
  public static HandlerList getHandlerList() {
    return HANDLER_LIST;
  }

  /**
   * Returns the block where the music was playing.
   *
   * @return The block where the audio originated.
   */
  @NotNull
  public Block getBlock() {
    return block;
  }

  /**
   * @return The identifier (stream URL) of the track that stopped.
   */
  @NotNull
  public String getIdentifier() {
    return identifier;
  }
}
