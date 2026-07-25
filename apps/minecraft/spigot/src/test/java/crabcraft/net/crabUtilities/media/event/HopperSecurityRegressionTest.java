package crabcraft.net.crabUtilities.media.event;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

public final class HopperSecurityRegressionTest {
  public static void main(String[] args) throws Exception {
    EventHandler annotation = JukeboxPlaybackListener.class
      .getMethod("onHopperInsert", InventoryMoveItemEvent.class)
      .getAnnotation(EventHandler.class);
    check(annotation != null && annotation.ignoreCancelled(),
      "cancelled standard hopper transfers still reach the custom insertion handler");
    check(annotation.priority() == EventPriority.MONITOR,
      "hopper insertion runs before protection listeners have made their final decision");

    check(!JukeboxPlaybackListener.transferCompleted(true, false),
      "a cancelled transfer can trigger a delayed playback side effect");
    check(!JukeboxPlaybackListener.transferCompleted(false, false),
      "an unrelated jukebox change can trigger delayed playback");
    check(JukeboxPlaybackListener.transferCompleted(false, true),
      "a completed allowed media-disc transfer was not recognised");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
