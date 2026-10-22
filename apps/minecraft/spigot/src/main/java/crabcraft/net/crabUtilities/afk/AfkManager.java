package crabcraft.net.crabUtilities.afk;

import crabcraft.net.crabUtilities.CrabMessages;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/** Cosmetic AFK status; never changes damage, movement, sleep or other gameplay. */
public final class AfkManager implements Listener, CommandExecutor {

    private final Plugin plugin;
    private final Function<Player, Component> displayName;
    private final AfkTracker tracker = new AfkTracker(Duration.ofMinutes(5).toNanos());
    private BukkitTask idleTask;

    public AfkManager(Plugin plugin, Function<Player, Component> displayName) {
        this.plugin = plugin;
        this.displayName = displayName;
    }

    public void start() {
        if (idleTask != null) return;
        long now = System.nanoTime();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            tracker.add(player.getUniqueId(), now);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        idleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkIdle, 20L, 20L);
    }

    public void shutdown() {
        if (idleTask != null) {
            idleTask.cancel();
            idleTask = null;
        }
        HandlerList.unregisterAll(this);
        tracker.clear();
    }

    public boolean isAfk(UUID uuid) {
        return uuid != null && tracker.isAfk(uuid);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CrabMessages.error("Only players can use /afk."));
            return true;
        }
        if (!sender.hasPermission("crabutilities.afk")) {
            sender.sendMessage(CrabMessages.error("You do not have permission to use /afk."));
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(CrabMessages.error("Usage: /afk"));
            return true;
        }
        announce(player, tracker.toggle(player.getUniqueId(), System.nanoTime()));
        return true;
    }

    private void checkIdle() {
        long now = System.nanoTime();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (tracker.markIdle(player.getUniqueId(), now)) announce(player, true);
        }
    }

    /** Records accepted activity on the server thread, including messages handled by the chat bridge. */
    public void recordActivity(Player player) {
        if (tracker.recordActivity(player.getUniqueId(), System.nanoTime())) announce(player, false);
    }

    private void announce(Player player, boolean afk) {
        Component message = Component.empty().append(displayName.apply(player))
                .append(CrabMessages.muted(afk ? " is now AFK." : " is no longer AFK."));
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.canSee(player)) viewer.sendMessage(message);
        }
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        tracker.add(event.getPlayer().getUniqueId(), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition() || event.hasChangedOrientation()) recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isAfkCommand(event.getMessage())) recordActivity(event.getPlayer());
    }

    static boolean isAfkCommand(String message) {
        String label = message.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return label.equals("/afk") || label.endsWith(":afk");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!event.isAsynchronous()) {
            recordActivity(event.getPlayer());
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) recordActivity(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) recordActivity(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) recordActivity(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItem(PlayerItemHeldEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        recordActivity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        recordActivity(event.getPlayer());
    }
}
