package crabcraft.net.crabUtilities.awards;

import org.bukkit.Material;

public final class SuspiciousBrushTrackerRegressionTest {

    public static void main(String[] args) {
        check(SuspiciousBrushTracker.completedBrushMaterial(
                        Material.SUSPICIOUS_SAND, Material.SAND) == Material.SUSPICIOUS_SAND,
                "completed suspicious sand should be counted");
        check(SuspiciousBrushTracker.completedBrushMaterial(
                        Material.SUSPICIOUS_GRAVEL, Material.GRAVEL) == Material.SUSPICIOUS_GRAVEL,
                "completed suspicious gravel should be counted");
        check(SuspiciousBrushTracker.completedBrushMaterial(
                        Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_SAND) == null,
                "intermediate brushing stages must not be counted");
        check(SuspiciousBrushTracker.completedBrushMaterial(Material.SAND, Material.AIR) == null,
                "ordinary block changes must not be counted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
