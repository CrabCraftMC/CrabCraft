package crabcraft.net.crabUtilities.bingo;

import java.util.UUID;
import org.bukkit.event.Listener;

/** Lifecycle shared by the event-driven weekly bingo detector groups. */
public interface BingoDetector extends Listener {
    void resetPlayer(UUID playerId);

    void clear();
}
