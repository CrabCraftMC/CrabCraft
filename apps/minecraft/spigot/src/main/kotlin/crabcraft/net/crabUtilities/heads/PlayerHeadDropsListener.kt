package crabcraft.net.crabUtilities.heads

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.NicknameComponentResolver
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

open class PlayerHeadDropsListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    open fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!plugin.config.getBoolean("tweaks.player-head-drops.enabled", false)) return
        val victim = event.entity
        val killer = victim.killer ?: return
        val head = ItemStack(Material.PLAYER_HEAD)
        val meta = head.itemMeta as? SkullMeta ?: return
        meta.owningPlayer = victim
        val victimName = NicknameComponentResolver.forPlayer(plugin.getEssentials(), victim)
        meta.displayName(
            (victimName ?: Component.text(victim.name, NamedTextColor.GOLD))
                .append(Component.text("'s Head", NamedTextColor.GOLD))
                .decoration(TextDecoration.ITALIC, false)
        )
        val killerName = NicknameComponentResolver.forPlayer(plugin.getEssentials(), killer)
        meta.lore(
            listOf(
                Component.text("Killed by ", NamedTextColor.GRAY)
                    .append(killerName ?: Component.text(killer.name, NamedTextColor.GRAY))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Killed on: ${formatDate(LocalDate.now())}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
            )
        )
        head.itemMeta = meta
        event.drops.add(head)
    }

    private fun formatDate(date: LocalDate): String {
        val day = date.dayOfMonth
        val suffix =
            when (day) {
                1,
                21,
                31 -> "st"
                2,
                22 -> "nd"
                3,
                23 -> "rd"
                else -> "th"
            }
        return "$day$suffix ${date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"
    }
}
