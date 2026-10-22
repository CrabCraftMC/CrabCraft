package crabcraft.net.crabUtilities;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Applies the proxy's vanish state using Paper's entity and tab visibility packets. */
public final class PaperVanishManager implements Listener, PluginMessageListener {

    public static final String SEE_PERMISSION = "crabutilities.vanish.see";
    private static final String STAFF_PERMISSION = "crabutilities.staffchat";

    private final Plugin plugin;
    private final Consumer<Player> onStatusChanged;
    private final Map<UUID, VanishBridgeProtocol.Message> states = new ConcurrentHashMap<>();
    private BukkitTask refreshTask;

    public PaperVanishManager(Plugin plugin, Consumer<Player> onStatusChanged) {
        this.plugin = plugin;
        this.onStatusChanged = onStatusChanged;
    }

    public void start() {
        var server = plugin.getServer();
        server.getMessenger().registerOutgoingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL);
        server.getMessenger().registerIncomingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL, this);
        server.getPluginManager().registerEvents(this, plugin);
        for (Player player : server.getOnlinePlayers()) {
            prepare(player);
        }
        refreshTask = server.getScheduler().runTaskTimer(plugin, this::refresh, 1L, 20L);
    }

    public void shutdown() {
        if (refreshTask != null) refreshTask.cancel();
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, VanishBridgeProtocol.CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, VanishBridgeProtocol.CHANNEL);
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            target.removeMetadata("vanished", plugin);
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (!viewer.equals(target)) viewer.showPlayer(plugin, target);
            }
        }
        states.clear();
    }

    /** Unknown backend sessions stay hidden until the proxy supplies their state. */
    public boolean isVanished(Player player) {
        var state = states.get(player.getUniqueId());
        return state == null || state.vanished();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        // Paper fires this before initial tab packets and entity tracking.
        prepare(event.getPlayer());
        // Velocity owns the network's join/leave announcements.
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        states.remove(event.getPlayer().getUniqueId());
        event.getPlayer().removeMetadata("vanished", plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(PaperServerListPingEvent event) {
        event.setNumPlayers((int) plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> !isVanished(player)).count());
        event.getListedPlayers().removeIf(info -> {
            var state = states.get(info.id());
            return state == null || state.vanished();
        });
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] payload) {
        if (!VanishBridgeProtocol.CHANNEL.equals(channel) || !carrier.isOnline()) return;
        VanishBridgeProtocol.Message message;
        try {
            message = VanishBridgeProtocol.decode(payload);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Ignored malformed proxy vanish state: " + e.getMessage());
            return;
        }
        if (message.ready()) return;
        var previous = states.get(carrier.getUniqueId());
        if (previous != null && message.revision() < previous.revision()) return;

        states.put(carrier.getUniqueId(), message);
        updateMetadata(carrier, message.vanished());
        refreshVisibility(carrier);
        onStatusChanged.accept(carrier);
        // An acknowledgement means the visibility rules have already been applied.
        carrier.sendPluginMessage(plugin, VanishBridgeProtocol.CHANNEL, payload);
    }

    private void prepare(Player player) {
        states.remove(player.getUniqueId());
        updateMetadata(player, true);
        refreshVisibility(player);
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (!target.equals(player) && isVanished(target)) applyVisibility(player, target);
        }
    }

    void refresh() {
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (!states.containsKey(target.getUniqueId())) {
                target.sendPluginMessage(plugin, VanishBridgeProtocol.CHANNEL, VanishBridgeProtocol.ready());
            }
            if (isVanished(target) && refreshVisibility(target)) onStatusChanged.accept(target);
        }
    }

    private boolean refreshVisibility(Player target) {
        boolean changed = false;
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.equals(target)) changed |= applyVisibility(viewer, target);
        }
        return changed;
    }

    private boolean applyVisibility(Player viewer, Player target) {
        // Pending players are hidden from moderators too until their state is known.
        boolean visible = !isVanished(target) || states.containsKey(target.getUniqueId())
                && (viewer.hasPermission(SEE_PERMISSION) || viewer.hasPermission(STAFF_PERMISSION));
        boolean previouslyVisible = viewer.canSee(target);
        if (visible) viewer.showPlayer(plugin, target);
        else viewer.hidePlayer(plugin, target);
        return previouslyVisible != viewer.canSee(target);
    }

    private void updateMetadata(Player player, boolean vanished) {
        if (vanished) player.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        else player.removeMetadata("vanished", plugin);
    }
}
