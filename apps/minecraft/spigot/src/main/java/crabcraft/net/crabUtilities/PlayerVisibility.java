package crabcraft.net.crabUtilities;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Applies Bukkit's viewer-specific visibility rules to a player collection. */
public final class PlayerVisibility {

    private PlayerVisibility() {
    }

    public static List<Player> visibleTo(
            Player viewer,
            Iterable<? extends Player> candidates) {
        List<Player> visible = new ArrayList<>();
        for (Player candidate : candidates) {
            if (viewer.canSee(candidate)) {
                visible.add(candidate);
            }
        }
        return List.copyOf(visible);
    }
}
