package net.crabcraft.customdiscs;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import net.crabcraft.customdiscs.audio.AudioEngine;
import org.jspecify.annotations.NonNull;

/** Loads PacketEvents API types only after the soft dependency is present. */
final class PacketEventsIntegration {

  private PacketEventsIntegration() {}

  static AutoCloseable register() {
    var eventManager = PacketEvents.getAPI().getEventManager();
    var listener = eventManager.registerListener(new PacketListener() {
      @Override
      public void onPacketSend(@NonNull PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.EFFECT) {
          var packet = new WrapperPlayServerEffect(event);
          if (packet.getType() == 1010) {
            var pos = packet.getPosition();
            var player = (org.bukkit.entity.Player) event.getPlayer();
            var block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
            if (AudioEngine.getInstance().isPlaying(block)) event.setCancelled(true);
          }
        }

        if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
          var packet = new WrapperPlayServerBlockEntityData(event);
          var pos = packet.getPosition();
          var player = (org.bukkit.entity.Player) event.getPlayer();
          var block = player.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
          if (AudioEngine.getInstance().isPlaying(block)) event.setCancelled(true);
        }
      }
    }, PacketListenerPriority.HIGHEST);
    return () -> eventManager.unregisterListener(listener);
  }
}
