package crabcraft.net.crabUtilities.restrictedarea;

import org.bukkit.configuration.Configuration;

final class RestrictedAreaSettings {

    static final String CONFIG_ROOT = "restricted-area";
    static final String DEFAULT_PERMISSION = "crabutilities.restricted-area.bypass";

    private final boolean enabled;
    private final String permission;
    private final Area area;
    private final ReturnPoint returnPoint;

    private RestrictedAreaSettings(
            final boolean enabled,
            final String permission,
            final Area area,
            final ReturnPoint returnPoint
    ) {
        this.enabled = enabled;
        this.permission = permission;
        this.area = area;
        this.returnPoint = returnPoint;
    }

    static RestrictedAreaSettings load(final Configuration config) {
        if (!config.getBoolean(CONFIG_ROOT + ".enabled", false)) {
            return disabled();
        }

        final String permission = config.getString(
                CONFIG_ROOT + ".bypass-permission", DEFAULT_PERMISSION).trim();
        final String world = config.getString(CONFIG_ROOT + ".world", "").trim();
        if (permission.isEmpty()) {
            throw new IllegalArgumentException("bypass-permission must not be empty");
        }
        if (world.isEmpty()) {
            throw new IllegalArgumentException("world must not be empty");
        }

        final double firstX = finite(config, "bounds.first.x");
        final double firstY = finite(config, "bounds.first.y");
        final double firstZ = finite(config, "bounds.first.z");
        final double secondX = finite(config, "bounds.second.x");
        final double secondY = finite(config, "bounds.second.y");
        final double secondZ = finite(config, "bounds.second.z");
        final Area area = new Area(
                world,
                Math.min(firstX, secondX),
                Math.min(firstY, secondY),
                Math.min(firstZ, secondZ),
                Math.max(firstX, secondX),
                Math.max(firstY, secondY),
                Math.max(firstZ, secondZ));

        final ReturnPoint returnPoint = new ReturnPoint(
                finite(config, "return-location.x"),
                finite(config, "return-location.y"),
                finite(config, "return-location.z"),
                (float) finite(config, "return-location.yaw"),
                (float) finite(config, "return-location.pitch"));
        if (!area.contains(world, returnPoint.x(), returnPoint.y(), returnPoint.z())) {
            throw new IllegalArgumentException("return-location must be inside the configured bounds");
        }

        return new RestrictedAreaSettings(true, permission, area, returnPoint);
    }

    static RestrictedAreaSettings disabled() {
        return new RestrictedAreaSettings(false, DEFAULT_PERMISSION, null, null);
    }

    private static double finite(final Configuration config, final String path) {
        final String fullPath = CONFIG_ROOT + "." + path;
        if (!config.contains(fullPath, true)) {
            throw new IllegalArgumentException(path + " is required");
        }
        final Object rawValue = config.get(fullPath);
        if (!(rawValue instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        final double value = number.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(path + " must be finite");
        }
        return value;
    }

    boolean enabled() {
        return enabled;
    }

    String permission() {
        return permission;
    }

    Area area() {
        return area;
    }

    ReturnPoint returnPoint() {
        return returnPoint;
    }

    enum MovementDecision {
        ALLOW,
        BLOCK,
        RETURN
    }

    record Area(
            String world,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        boolean contains(
                final String candidateWorld,
                final double x,
                final double y,
                final double z
        ) {
            return world.equals(candidateWorld)
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        MovementDecision movementDecision(
                final String fromWorld,
                final double fromX,
                final double fromY,
                final double fromZ,
                final String toWorld,
                final double toX,
                final double toY,
                final double toZ
        ) {
            if (contains(toWorld, toX, toY, toZ)) {
                return MovementDecision.ALLOW;
            }
            return contains(fromWorld, fromX, fromY, fromZ)
                    ? MovementDecision.BLOCK
                    : MovementDecision.RETURN;
        }
    }

    record ReturnPoint(double x, double y, double z, float yaw, float pitch) {
    }
}
