package crabcraft.net.crabUtilities.media;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import crabcraft.net.crabUtilities.media.audio.AudioEngine;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

/**
 * Hides the vanilla jukebox packets for blocks whose audio is supplied through
 * Simple Voice Chat.
 */
final class JukeboxPacketFilter implements PacketListener {
  static AutoCloseable install() {
    var manager = PacketEvents.getAPI().getEventManager();
    var registration = manager.registerListener(
      new JukeboxPacketFilter(),
      PacketListenerPriority.HIGHEST
    );
    return () -> manager.unregisterListener(registration);
  }

  private JukeboxPacketFilter() {}

  @Override
  public void onPacketSend(@NonNull PacketSendEvent event) {
    Vector3i blockPosition = affectedJukebox(event);
    if (blockPosition == null) return;

    Player recipient = (Player) event.getPlayer();
    var block = recipient.getWorld().getBlockAt(
      blockPosition.getX(),
      blockPosition.getY(),
      blockPosition.getZ()
    );
    if (AudioEngine.getInstance().isPlaying(block)) event.setCancelled(true);
  }

  private static Vector3i affectedJukebox(PacketSendEvent event) {
    if (event.getPacketType() == PacketType.Play.Server.BLOCK_ENTITY_DATA) {
      return new WrapperPlayServerBlockEntityData(event).getPosition();
    }
    if (event.getPacketType() != PacketType.Play.Server.EFFECT) return null;

    WrapperPlayServerEffect effect = new WrapperPlayServerEffect(event);
    return effect.getType() == 1010 ? effect.getPosition() : null;
  }
}
