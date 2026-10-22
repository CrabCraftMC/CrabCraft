package crabcraft.net.crabUtilities.tags;

import crabcraft.net.crabUtilities.CrabUtilities;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Player-bound requests to Velocity; only committed snapshots enter the cache. */
public final class PaperTagService implements Listener, PluginMessageListener {

    private static final long TIMEOUT_TICKS = 100L;
    private final Plugin plugin;
    private final TagCache cache = new TagCache();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final TagGlyphs glyphs;
    private volatile boolean running;

    public record Response(TagBridgeProtocol.Status status, TagSnapshot snapshot, boolean timedOut) {
    }

    private record Pending(UUID requestId, Player player, boolean selecting,
                           Consumer<Response> callback, BukkitTask timeout) {
    }

    public PaperTagService(CrabUtilities plugin) {
        this(plugin, TagGlyphs.create(plugin));
    }

    PaperTagService(Plugin plugin, TagGlyphs glyphs) {
        this.plugin = plugin;
        this.glyphs = glyphs;
    }

    public void start() {
        running = true;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, TagBridgeProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, TagBridgeProtocol.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Wait one tick so the player's proxy/backend connection can finish initialising.
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers()
                .forEach(this::warm));
    }

    public void shutdown() {
        running = false;
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, TagBridgeProtocol.CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, TagBridgeProtocol.CHANNEL);
        pending.values().forEach(request -> request.timeout().cancel());
        pending.clear();
        cache.clear();
    }

    public TagCache cache() {
        return cache;
    }

    public boolean isSelecting(UUID playerId) {
        Pending request = pending.get(playerId);
        return request != null && request.selecting();
    }

    public void query(Player player, Consumer<Response> callback) {
        request(player, null, -1L, false, callback);
    }

    public void select(Player player, String tagId, long expectedRevision, Consumer<Response> callback) {
        request(player, tagId, expectedRevision, true, callback);
    }

    private void request(Player player, String tagId, long revision, boolean selecting,
                         Consumer<Response> callback) {
        if (!Bukkit.isPrimaryThread()) {
            runOnMain(() -> request(player, tagId, revision, selecting, callback));
            return;
        }
        if (!running || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        Pending previous = pending.get(playerId);
        if (previous != null && previous.selecting()) {
            callback.accept(new Response(TagBridgeProtocol.Status.UNAVAILABLE, null, false));
            return;
        }
        if (previous != null) previous.timeout().cancel();
        UUID requestId = UUID.randomUUID();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending current = pending.get(playerId);
            if (current == null || !current.requestId().equals(requestId)) return;
            pending.remove(playerId);
            if (sameSession(player)) callback.accept(new Response(
                    TagBridgeProtocol.Status.UNAVAILABLE, null, true));
        }, TIMEOUT_TICKS);
        pending.put(playerId, new Pending(requestId, player, selecting, callback, timeout));
        try {
            byte[] message = selecting ? TagBridgeProtocol.select(requestId, tagId, revision)
                    : TagBridgeProtocol.query(requestId);
            player.sendPluginMessage(plugin, TagBridgeProtocol.CHANNEL, message);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            pending.remove(playerId);
            timeout.cancel();
            callback.accept(new Response(TagBridgeProtocol.Status.UNAVAILABLE, null, false));
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!TagBridgeProtocol.CHANNEL.equals(channel)) return;
        TagBridgeProtocol.Packet packet;
        try {
            packet = TagBridgeProtocol.decode(message);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().fine("Ignored malformed tag bridge packet: " + exception.getMessage());
            return;
        }
        if (!carrier.getUniqueId().equals(packet.playerId())) return;
        runOnMain(() -> receive(carrier, packet));
    }

    private void receive(Player player, TagBridgeProtocol.Packet packet) {
        if (!running || !sameSession(player)) return;
        if (packet.type() == TagBridgeProtocol.Type.STATE) {
            if (packet.snapshot() != null) accept(packet.snapshot());
            return;
        }
        if (packet.type() != TagBridgeProtocol.Type.RESULT) return;
        Pending request = pending.get(player.getUniqueId());
        if (request == null || request.player() != player
                || !request.requestId().equals(packet.requestId())) return;
        pending.remove(player.getUniqueId());
        request.timeout().cancel();
        TagSnapshot snapshot = packet.snapshot() == null ? null : accept(packet.snapshot()).snapshot();
        request.callback().accept(new Response(packet.status(), snapshot, false));
    }

    private TagCache.Entry accept(TagSnapshot snapshot) {
        Map<String, Component> icons = new HashMap<>();
        for (TagDefinition tag : snapshot.ownedTags()) {
            if (!TagCache.validGlyphId(tag.glyphId())) continue;
            try {
                Component preview = glyphs.preview(tag.glyphId());
                if (preview != null) icons.put(tag.id(), preview);
            } catch (LinkageError | RuntimeException exception) {
                plugin.getLogger().fine("Cannot resolve tag glyph " + tag.glyphId() + ": " + exception.getMessage());
            }
        }
        return cache.accept(snapshot, icons);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        cache.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sameSession(player)) warm(player);
        });
    }

    private void warm(Player player) {
        // A menu request may have arrived before the delayed join/reload warm-up.
        if (!pending.containsKey(player.getUniqueId())) query(player, response -> {});
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        cache.remove(playerId);
        Pending request = pending.remove(playerId);
        if (request != null) request.timeout().cancel();
    }

    private static boolean sameSession(Player player) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    public void runOnMain(Runnable action) {
        if (!running) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
