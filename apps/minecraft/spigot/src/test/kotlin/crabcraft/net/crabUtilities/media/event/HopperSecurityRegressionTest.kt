package crabcraft.net.crabUtilities.media.event

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.inventory.InventoryMoveItemEvent

object HopperSecurityRegressionTest {
  @JvmStatic
  fun main(args: Array<String>) {
    val annotation = JukeboxPlaybackListener::class.java
      .getMethod("onHopperInsert", InventoryMoveItemEvent::class.java)
      .getAnnotation(EventHandler::class.java)
    check(annotation != null && annotation.ignoreCancelled,
      "cancelled standard hopper transfers still reach the custom insertion handler")
    check(annotation!!.priority == EventPriority.MONITOR,
      "hopper insertion runs before protection listeners have made their final decision")

    check(!JukeboxPlaybackListener.transferCompleted(true, false),
      "a cancelled transfer can trigger a delayed playback side effect")
    check(!JukeboxPlaybackListener.transferCompleted(false, false),
      "an unrelated jukebox change can trigger delayed playback")
    check(JukeboxPlaybackListener.transferCompleted(false, true),
      "a completed allowed media-disc transfer was not recognised")
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }
}
