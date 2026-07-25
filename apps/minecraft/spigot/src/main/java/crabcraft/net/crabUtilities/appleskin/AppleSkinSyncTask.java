package crabcraft.net.crabUtilities.appleskin;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.ByteBuffer;

final class AppleSkinSyncTask extends BukkitRunnable {

    private static final float MINIMUM_EXHAUSTION_CHANGE = 0.01F;

    private final Player player;
    private final long generation;
    private float previousSaturation = -1;
    private float previousExhaustion = -1;

    AppleSkinSyncTask(Player player) {
        this.player = player;
        this.generation = AppleSkinIntegration.currentGeneration();
        runTaskTimer(AppleSkinIntegration.plugin(), 1, 1);
    }

    @Override
    public void run() {
        if (!AppleSkinIntegration.isCurrentGeneration(generation) || !player.isOnline()) {
            cancel();
            return;
        }

        final float saturation = player.getSaturation();
        if (saturation != previousSaturation) {
            send(AppleSkinIntegration.SATURATION_CHANNEL, saturation);
            previousSaturation = saturation;
        }

        final float exhaustion = player.getExhaustion();
        if (Math.abs(exhaustion - previousExhaustion) >= MINIMUM_EXHAUSTION_CHANGE) {
            send(AppleSkinIntegration.EXHAUSTION_CHANNEL, exhaustion);
            previousExhaustion = exhaustion;
        }
    }

    private void send(String channel, float value) {
        player.sendPluginMessage(
                AppleSkinIntegration.plugin(),
                channel,
                ByteBuffer.allocate(Float.BYTES).putFloat(value).array());
    }
}
