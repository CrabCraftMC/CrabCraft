package crabcraft.net.crabUtilities.accurateplacement;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

/** Loads PacketEvents types only after the soft dependency has been confirmed. */
final class AccurateBlockPlacementPacketEventsIntegration {

    private AccurateBlockPlacementPacketEventsIntegration() {
    }

    static AutoCloseable register(AccurateBlockPlacementManager manager) {
        var eventManager = PacketEvents.getAPI().getEventManager();
        var listener = eventManager.registerListener(new PacketListener() {
            @Override
            public void onPacketReceive(@NonNull PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT
                        || !(event.getPlayer() instanceof Player player)) {
                    return;
                }

                var packet = new WrapperPlayClientPlayerBlockPlacement(event);
                var position = packet.getBlockPosition();
                var cursor = packet.getCursorPosition();
                if (!manager.capture(
                        player,
                        position.getX(),
                        position.getY(),
                        position.getZ(),
                        cursor.getX(),
                        packet.getSequence())) {
                    return;
                }

                packet.setCursorPosition(cursor.withX(0.5F));
                event.markForReEncode(true);
            }
        }, PacketListenerPriority.LOWEST);
        return () -> eventManager.unregisterListener(listener);
    }
}
