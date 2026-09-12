package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Spaced positions around a following player's resting area, independent of facing or travel heading.
 * The caller owns this short-lived, entity-free state. Small player movements leave targets at rest.
 */
public final class CompanionFollowFormation {
    private static final double TELEPORT_DISTANCE = 32.0;
    private final Vector3d anchor = new Vector3d();
    private final Vector3d lastLeaderPosition = new Vector3d();
    private boolean initialized;

    /** Clears the resting area before the helper is reused for another player. */
    public void reset() {
        initialized = false;
        anchor.zero();
        lastLeaderPosition.zero();
    }

    /**
     * Computes a stable position around the player. The anchor follows only after the player
     * leaves a half-spacing horizontal dead zone, and moves only far enough to keep up.
     *
     * @param leaderPosition current player position
     * @param leaderYaw retained for caller compatibility; facing never affects follow positions
     * @param slot stable zero-based follower slot assigned by the caller
     * @param spacing minimum horizontal spacing between slots
     * @param altitude desired absolute world Y coordinate
     * @param dt elapsed seconds; retained for caller compatibility
     * @param output vector receiving the target
     * @return the target, or zero for invalid input
     */
    @Nonnull
    public Vector3d update(@Nonnull Vector3d leaderPosition, float leaderYaw, int slot,
                           double spacing, double altitude, double dt, @Nonnull Vector3d output) {
        Vector3d result = output == null ? new Vector3d() : output;
        if (leaderPosition == null || !isFinite(leaderPosition) || !Double.isFinite(spacing)
                || spacing <= 0 || !Double.isFinite(altitude) || !Double.isFinite(dt) || dt < 0) {
            reset();
            return result.zero();
        }
        if (!initialized || lastLeaderPosition.distanceSquared(leaderPosition)
                > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            anchor.set(leaderPosition);
            initialized = true;
        }
        lastLeaderPosition.set(leaderPosition);
        double dx = leaderPosition.x - anchor.x;
        double dz = leaderPosition.z - anchor.z;
        double distance = Math.hypot(dx, dz);
        double slack = spacing * 0.5;
        if (distance > slack) {
            double fraction = (distance - slack) / distance;
            anchor.x += dx * fraction;
            anchor.z += dz * fraction;
        }

        // Six places in the first ring, twelve in the second, and so on. Ring radii
        // differ by one spacing, keeping separate slots apart without heading-based rotation.
        int ring = 1;
        long index = Math.max(0, slot);
        while (index >= 6L * ring) {
            index -= 6L * ring;
            ring++;
        }
        double angle = 2 * Math.PI * index / (6L * ring);
        double radius = spacing * ring;
        return result.set(anchor.x + Math.cos(angle) * radius, altitude,
                anchor.z + Math.sin(angle) * radius);
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}