package crabcraft.net.crabUtilities.spectator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Returns spectators to the location where they entered spectator mode. */
public final class SpectatorBackCommand implements CommandExecutor, Listener {

    private final Map<UUID, Location> startingLocations = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SPECTATOR) {
            startingLocations.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation().clone());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        startingLocations.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use /specback.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(Component.text("Usage: ", NamedTextColor.RED)
                    .append(Component.text("/specback", NamedTextColor.GOLD)));
            return true;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.sendMessage(Component.text(
                    "You must be in spectator mode to use /specback.", NamedTextColor.RED));
            return true;
        }

        Location startingLocation = startingLocations.get(player.getUniqueId());
        if (startingLocation == null) {
            player.sendMessage(Component.text(
                    "No spectator starting location is recorded.", NamedTextColor.RED));
            return true;
        }

        player.teleportAsync(startingLocation).whenComplete((success, error) -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null || !Boolean.TRUE.equals(success)) {
                player.sendMessage(Component.text(
                        "Could not return you to your spectator starting location.", NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                    "Returned to your spectator starting location.", NamedTextColor.GREEN));
        });
        return true;
    }
}
