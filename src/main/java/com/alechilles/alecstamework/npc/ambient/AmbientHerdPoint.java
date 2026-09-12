package com.alechilles.alecstamework.npc.ambient;

/** Immutable world coordinate retained by ambient-herd work. */
public record AmbientHerdPoint(double x, double y, double z) {
    public AmbientHerdPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Ambient herd coordinates must be finite");
        }
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public AmbientHerdPoint offset(double xOffset, double yOffset, double zOffset) {
        return new AmbientHerdPoint(x + xOffset, y + yOffset, z + zOffset);
    }

    public double horizontalDistanceSquared(AmbientHerdPoint other) {
        double deltaX = x - other.x;
        double deltaZ = z - other.z;
        return deltaX * deltaX + deltaZ * deltaZ;
    }
}
