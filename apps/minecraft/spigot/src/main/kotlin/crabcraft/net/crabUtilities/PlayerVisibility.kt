package crabcraft.net.crabUtilities

import org.bukkit.entity.Player

/** Applies Bukkit's viewer-specific visibility rules to a player collection. */
object PlayerVisibility {
    @JvmStatic
    fun visibleTo(viewer: Player, candidates: Iterable<Player>): List<Player> =
        java.util.List.copyOf(candidates.filter { viewer.canSee(it) })
}
