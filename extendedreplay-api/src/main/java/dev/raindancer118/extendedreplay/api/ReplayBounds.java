package dev.raindancer118.extendedreplay.api;

/**
 * Spatial recording bounds of a session. Events outside the bounds are not recorded
 * (player frames of players inside are always recorded).
 */
public record ReplayBounds(double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {

    public static ReplayBounds cuboid(double x1, double y1, double z1,
                                      double x2, double y2, double z2) {
        return new ReplayBounds(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public static ReplayBounds radius(double centerX, double centerZ, double radius) {
        return new ReplayBounds(
                centerX - radius, -10_000, centerZ - radius,
                centerX + radius, 10_000, centerZ + radius);
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
