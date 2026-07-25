package crabcraft.net.crabUtilities.accurateplacement;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;

final class AccurateBlockPlacementPaperIntegration {

    private static final Field ACK_SEQUENCE = findAckSequence();

    private AccurateBlockPlacementPaperIntegration() {
    }

    static void verify() {
    }

    static int currentSequence(Player player) {
        try {
            return ACK_SEQUENCE.getInt(((CraftPlayer) player).getHandle().connection);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Paper's block-change sequence", exception);
        }
    }

    private static Field findAckSequence() {
        try {
            Field field = ServerGamePacketListenerImpl.class.getDeclaredField("ackBlockChangesUpTo");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Paper's block-change sequence is unavailable", exception);
        }
    }
}
