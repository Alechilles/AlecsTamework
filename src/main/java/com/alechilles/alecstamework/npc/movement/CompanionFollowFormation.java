package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Resolves a stable loose-formation target for one companion following a leader.
 *
 * <p>The caller owns the instance and supplies the companion's stable slot. This helper keeps
 * only short-lived motion state; it does not inspect or cache ECS entities. A stationary leader
 * keeps its last travel heading so camera turns do not rotate the companions around it.</p>
 */
public final class CompanionFollowFormation {
    private static final double EPSILON = 1.0E-6;
    private static final double TELEPORT_DISTANCE = 32.0;
    private static final double MAX_OFFSET_SPEED_SCALE = 0.75;

    private final Vector3d lastLeaderPosition = new Vector3d();
    private final Vector3d leaderHeading = new Vector3d();
    private final Vector3d sampledHeading = new Vector3d();
    private final Vector3d leaderVelocity = new Vector3d();
    private final Vector3d desiredOffset = new Vector3d();
    private final Vector3d currentOffset = new Vector3d();
    private final Vector3d desiredTarget = new Vector3d();

    private boolean initialized;
    private boolean hasOffset;

    /** Clears motion and slot smoothing state before the helper is reused for another leader. */
    public void reset() {
        initialized = false;
        hasOffset = false;
        lastLeaderPosition.zero();
        leaderHeading.zero();
        sampledHeading.zero();
        leaderVelocity.zero();
        desiredOffset.zero();
        currentOffset.zero();
        desiredTarget.zero();
    }

    /**
     * Computes the target for one stable follower slot.
     *
     * @param leaderPosition current leader position
     * @param leaderYaw leader body yaw, used only until horizontal motion establishes a heading
     * @param slot stable zero-based follower slot assigned by the caller
     * @param spacing desired loose-formation spacing; must be finite and positive
     * @param altitude desired target altitude (world Y coordinate)
     * @param dt elapsed seconds since the preceding update
     * @param output mutable vector receiving the target
     * @return {@code output}, or a new zero vector when a null output is supplied
     */
    @Nonnull
    public Vector3d update(@Nonnull Vector3d leaderPosition,
                           float leaderYaw,
                           int slot,
                           double spacing,
                           double altitude,
                           double dt,
                           @Nonnull Vector3d output) {
        Vector3d result = output == null ? new Vector3d() : output;
        if (!isValidInput(leaderPosition, leaderYaw, spacing, altitude, dt)) {
            reset();
            return result.zero();
        }

        double safeDt = Math.max(0.0, dt);
        boolean teleport = initialized && lastLeaderPosition.distanceSquared(leaderPosition)
                > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
        if (teleport || !isFiniteState()) {
            reset();
        }

        if (!initialized) {
            BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(leaderYaw, leaderHeading);
            lastLeaderPosition.set(leaderPosition);
            initialized = true;
        } else {
            leaderVelocity.zero();
            if (safeDt > EPSILON) {
                leaderVelocity.set(leaderPosition).sub(lastLeaderPosition).div(safeDt);
                if (leaderVelocity.x * leaderVelocity.x + leaderVelocity.z * leaderVelocity.z
                        > EPSILON * EPSILON) {
                    sampledHeading.set(leaderVelocity.x, 0.0, leaderVelocity.z);
                    FlightFormationSteering.smoothHeading(
                            leaderHeading, sampledHeading, safeDt, leaderHeading);
                }
            }
            lastLeaderPosition.set(leaderPosition);
        }

        FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE,
                slot, spacing, 0.0, leaderPosition, leaderHeading, desiredTarget);
        desiredTarget.y = altitude;
        desiredOffset.set(desiredTarget).sub(leaderPosition);

        if (!hasOffset) {
            currentOffset.set(desiredOffset);
            hasOffset = true;
        } else {
            double offsetSpeed = Math.max(EPSILON, spacing * MAX_OFFSET_SPEED_SCALE);
            FlightFormationSteering.smoothOffset(
                    currentOffset, desiredOffset, offsetSpeed, safeDt, currentOffset);
        }

        result.set(leaderPosition).add(currentOffset);
        if (!isFinite(result)) {
            reset();
            return result.zero();
        }
        return result;
    }

    private static boolean isValidInput(@Nonnull Vector3d leaderPosition,
                                        float leaderYaw,
                                        double spacing,
                                        double altitude,
                                        double dt) {
        return leaderPosition != null
                && isFinite(leaderPosition)
                && Float.isFinite(leaderYaw)
                && Double.isFinite(spacing)
                && spacing > 0.0
                && Double.isFinite(altitude)
                && Double.isFinite(dt)
                && dt >= 0.0;
    }

    private boolean isFiniteState() {
        return isFinite(lastLeaderPosition)
                && isFinite(leaderHeading)
                && isFinite(currentOffset)
                && isFinite(desiredOffset);
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
