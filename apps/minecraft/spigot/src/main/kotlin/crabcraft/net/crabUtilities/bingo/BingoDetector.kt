package crabcraft.net.crabUtilities.bingo

import java.util.UUID
import org.bukkit.event.Listener

/** Lifecycle shared by the event-driven weekly bingo detector groups. */
interface BingoDetector : Listener {
    fun resetPlayer(playerId: UUID)

    fun clear()
}
