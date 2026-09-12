package crabcraft.net.crabUtilities

import org.bukkit.entity.Player

/** Applies Bukkit's viewer-specific visibility rules to a player collection. */
class PlayerVisibility private constructor() {
    companion object {
        @JvmStatic
        fun visibleTo(viewer: Player, candidates: Iterable<Player>): List<Player> {
            val visible = ArrayList<Player>()
            for (candidate in candidates) {
                if (viewer.canSee(candidate)) visible.add(candidate)
            }
            return java.util.List.copyOf(visible)
        }
    }
}
