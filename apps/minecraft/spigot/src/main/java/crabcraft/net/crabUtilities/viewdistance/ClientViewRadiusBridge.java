package crabcraft.net.crabUtilities.viewdistance;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the client-facing render radius stable while Paper changes its real
 * chunk load and send radii.
 */
final class ClientViewRadiusBridge implements Listener {

    private static final String HANDLER_NAME = "crabutilities_view_radius";

    private final CrabUtilities plugin;
    private final int advertisedRadius;
    private final Map<UUID, RadiusPacketHandler> handlers = new ConcurrentHashMap<>();
    private volatile boolean running;

    ClientViewRadiusBridge(CrabUtilities plugin, int advertisedRadius) {
        this.plugin = plugin;
        this.advertisedRadius = advertisedRadius;
    }

    void start() {
        if (running) {
            throw new IllegalStateException("Client view radius bridge is already running");
        }
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::attachAndAdvertise);
    }

    void shutdown(boolean restoreActualRadius) {
        if (!running) {
            return;
        }
        running = false;
        HandlerList.unregisterAll(this);
        Bukkit.getOnlinePlayers().forEach(player -> detach(player, restoreActualRadius));
        handlers.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        attachAndAdvertise(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        attachAndAdvertise(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        detach(event.getPlayer(), false);
    }

    private void attachAndAdvertise(Player player) {
        attach(player, true);
    }

    private void attach(Player player, boolean advertise) {
        if (!running) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        if (serverPlayer.connection == null
                || serverPlayer.connection.connection == null
                || serverPlayer.connection.connection.channel == null) {
            return;
        }

        Connection connection = serverPlayer.connection.connection;
        Channel channel = connection.channel;
        UUID playerId = player.getUniqueId();
        RadiusPacketHandler handler = handlers.computeIfAbsent(
                playerId,
                ignored -> new RadiusPacketHandler(advertisedRadius));

        runOnChannel(channel, () -> {
            if (!running || handlers.get(playerId) != handler || !channel.isActive()) {
                return;
            }

            ChannelPipeline pipeline = channel.pipeline();
            Object existing = pipeline.get(HANDLER_NAME);
            if (existing != handler) {
                if (existing != null) {
                    pipeline.remove(HANDLER_NAME);
                }
                pipeline.addLast(HANDLER_NAME, handler);
            }
            if (advertise) {
                serverPlayer.connection.send(
                        new ClientboundSetChunkCacheRadiusPacket(advertisedRadius));
            }
        });
    }

    private void detach(Player player, boolean restoreActualRadius) {
        UUID playerId = player.getUniqueId();
        RadiusPacketHandler handler = handlers.remove(playerId);
        if (handler == null) {
            return;
        }

        ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
        if (serverPlayer.connection == null
                || serverPlayer.connection.connection == null
                || serverPlayer.connection.connection.channel == null) {
            return;
        }

        Connection connection = serverPlayer.connection.connection;
        Channel channel = connection.channel;
        int actualRadius = restoreActualRadius ? player.getSendViewDistance() : -1;

        runOnChannel(channel, () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) == handler) {
                pipeline.remove(HANDLER_NAME);
            }
            if (restoreActualRadius && channel.isActive()) {
                serverPlayer.connection.send(
                        new ClientboundSetChunkCacheRadiusPacket(actualRadius));
            }
        });
    }

    private void runOnChannel(Channel channel, Runnable action) {
        if (channel.eventLoop().inEventLoop()) {
            action.run();
            return;
        }
        channel.eventLoop().execute(action);
    }

    static Object keepAdvertisedRadius(Object message, int advertisedRadius) {
        if (message instanceof ClientboundSetChunkCacheRadiusPacket packet
                && packet.getRadius() != advertisedRadius) {
            return new ClientboundSetChunkCacheRadiusPacket(advertisedRadius);
        }
        return message;
    }

    private static final class RadiusPacketHandler extends ChannelDuplexHandler {

        private final int advertisedRadius;

        private RadiusPacketHandler(int advertisedRadius) {
            this.advertisedRadius = advertisedRadius;
        }

        @Override
        public void write(
                ChannelHandlerContext context,
                Object message,
                ChannelPromise promise) throws Exception {
            super.write(
                    context,
                    keepAdvertisedRadius(message, advertisedRadius),
                    promise);
        }
    }
}
