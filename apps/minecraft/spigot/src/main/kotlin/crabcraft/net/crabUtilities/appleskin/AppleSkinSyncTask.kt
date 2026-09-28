package crabcraft.net.crabUtilities.appleskin

import java.nio.ByteBuffer
import kotlin.math.abs
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

class AppleSkinSyncTask(private val player: Player) : BukkitRunnable() {
    private val generation = AppleSkinIntegration.currentGeneration()
    private var previousSaturation = -1f
    private var previousExhaustion = -1f

    init {
        runTaskTimer(AppleSkinIntegration.plugin(), 1, 1)
    }

    override fun run() {
        if (!AppleSkinIntegration.isCurrentGeneration(generation) || !player.isOnline) {
            cancel()
            return
        }
        val saturation = player.saturation
        if (saturation != previousSaturation) {
            send(AppleSkinIntegration.SATURATION_CHANNEL, saturation)
            previousSaturation = saturation
        }
        val exhaustion = player.exhaustion
        if (abs(exhaustion - previousExhaustion) >= MINIMUM_EXHAUSTION_CHANGE) {
            send(AppleSkinIntegration.EXHAUSTION_CHANNEL, exhaustion)
            previousExhaustion = exhaustion
        }
    }

    private fun send(channel: String, value: Float) {
        player.sendPluginMessage(
            AppleSkinIntegration.plugin(),
            channel,
            ByteBuffer.allocate(Float.SIZE_BYTES).putFloat(value).array(),
        )
    }

    companion object {
        private const val MINIMUM_EXHAUSTION_CHANGE = 0.01f
    }
}
