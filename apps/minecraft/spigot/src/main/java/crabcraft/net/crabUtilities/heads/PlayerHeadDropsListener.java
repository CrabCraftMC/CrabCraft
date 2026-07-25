package crabcraft.net.crabUtilities.heads;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.NicknameComponentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

public class PlayerHeadDropsListener implements Listener {

    private final CrabUtilities plugin;

    public PlayerHeadDropsListener(final CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        if (!this.plugin.getConfig().getBoolean("tweaks.player-head-drops.enabled", false)) {
            return;
        }
        final Player victim = event.getEntity();
        final Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }

        final ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof final SkullMeta meta)) {
            return;
        }
        meta.setOwningPlayer(victim);
        final Component victimName = NicknameComponentResolver.forPlayer(this.plugin.getEssentials(), victim);
        meta.displayName((victimName != null ? victimName : Component.text(victim.getName(), NamedTextColor.GOLD))
                .append(Component.text("'s Head", NamedTextColor.GOLD))
                .decoration(TextDecoration.ITALIC, false));
        final Component killerName = NicknameComponentResolver.forPlayer(this.plugin.getEssentials(), killer);
        meta.lore(List.of(Component.text("Killed by ", NamedTextColor.GRAY)
                .append(killerName != null ? killerName : Component.text(killer.getName(), NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false),
                Component.text("Killed on: " + formatDate(LocalDate.now()), NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        head.setItemMeta(meta);
        event.getDrops().add(head);
    }

    private static String formatDate(final LocalDate date) {
        final int day = date.getDayOfMonth();
        final String suffix = switch (day) {
            case 1, 21, 31 -> "st";
            case 2, 22 -> "nd";
            case 3, 23 -> "rd";
            default -> "th";
        };
        return day + suffix + " " + date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
