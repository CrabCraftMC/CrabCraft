package crabcraft.net.crabUtilities.awards

import org.bukkit.Material

object SuspiciousBrushTrackerRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(SuspiciousBrushTracker.completedBrushMaterial(Material.SUSPICIOUS_SAND, Material.SAND) == Material.SUSPICIOUS_SAND,
            "completed suspicious sand should be counted")
        check(SuspiciousBrushTracker.completedBrushMaterial(Material.SUSPICIOUS_GRAVEL, Material.GRAVEL) == Material.SUSPICIOUS_GRAVEL,
            "completed suspicious gravel should be counted")
        check(SuspiciousBrushTracker.completedBrushMaterial(Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_SAND) == null,
            "intermediate brushing stages must not be counted")
        check(SuspiciousBrushTracker.completedBrushMaterial(Material.SAND, Material.AIR) == null,
            "ordinary block changes must not be counted")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
