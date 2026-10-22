package crabcraft.net.crabUtilities.velocity.tags;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol;
import crabcraft.net.crabUtilities.tags.TagSnapshot;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;

import java.util.UUID;

/** Accepts actor-less requests from the player's active backend, never from the client. */
public final class VelocityTagBridge {
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(TagBridgeProtocol.CHANNEL);

    private final CrabUtilitiesVelocity plugin;
    private final TagService service;

    public VelocityTagBridge(CrabUtilitiesVelocity plugin, TagService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void start() {
        plugin.getServer().getChannelRegistrar().register(CHANNEL);
        plugin.getServer().getEventManager().register(plugin, this);
    }

    public void shutdown() {
        plugin.getServer().getEventManager().unregisterListener(plugin, this);
        plugin.getServer().getChannelRegistrar().unregister(CHANNEL);
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)
                || !(event.getTarget() instanceof Player player)
                || player.getCurrentServer().filter(source::equals).isEmpty()) return;

        try {
            TagBridgeProtocol.Packet packet = TagBridgeProtocol.decode(event.getData());
            if (isRequest(packet.type())) service.request(player, source, packet);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().debug("Ignored malformed tag bridge payload: {}", e.getMessage());
        }
    }

    static boolean isRequest(TagBridgeProtocol.Type type) {
        return type == TagBridgeProtocol.Type.QUERY || type == TagBridgeProtocol.Type.SELECT;
    }

    void reply(ServerConnection source, UUID requestId, UUID playerId,
               TagBridgeProtocol.Status status, TagSnapshot snapshot) {
        source.sendPluginMessage(CHANNEL, TagBridgeProtocol.result(requestId, playerId, status, snapshot));
    }

    void push(Player player, TagSnapshot snapshot) {
        player.getCurrentServer().ifPresent(connection ->
                connection.sendPluginMessage(CHANNEL, TagBridgeProtocol.state(snapshot)));
    }
}
