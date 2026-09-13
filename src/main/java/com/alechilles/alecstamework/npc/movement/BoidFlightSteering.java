package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Per-follower local boid steering accumulator. Call {@link #reset()} before sampling the bounded nearby members.
 * It stores only primitive sums so a flight update does not allocate temporary collections or vectors.
 */
final class BoidFlightSteering {
    private static final double EPSILON = 1.0E-6;
    private static final double OVERLAP_DISTANCE_SQUARED = 1.0E-24;
    private static final double SEPARATION_WEIGHT = 1.40;
    private static final double ALIGNMENT_WEIGHT = 0.55;
    private static final double COHESION_WEIGHT = 0.22;
    private static final double LEADER_ATTRACTION_WEIGHT = 0.45;

    private double neighborPositionX;
    private double neighborPositionY;
    private double neighborPositionZ;
    private double neighborVelocityX;
    private double neighborVelocityY;
    private double neighborVelocityZ;
    private double separationX;
    private double separationY;
    private double separationZ;
    private int positionCount;
    private int velocityCount;

    void reset() {
        neighborPositionX = 0.0;
        neighborPositionY = 0.0;
        neighborPositionZ = 0.0;
        neighborVelocityX = 0.0;
        neighborVelocityY = 0.0;
        neighborVelocityZ = 0.0;
        separationX = 0.0;
        separationY = 0.0;
        separationZ = 0.0;
        positionCount = 0;
        velocityCount = 0;
    }

    void addNeighbor(@Nonnull Vector3d self,
                     @Nonnull Vector3d neighborPosition,
                     @Nonnull Vector3d neighborVelocity,
                     double spacing) {
        addNeighbor(self, neighborPosition, neighborVelocity, spacing, 1.0);
    }

    /**
     * Adds one nearby member. Callers can provide opposing deterministic signs for true position overlaps so both
     * members choose different separation directions; ordinary near-neighbor separation ignores this value.
     */
    void addNeighbor(@Nonnull Vector3d self,
                     @Nonnull Vector3d neighborPosition,
                     @Nonnull Vector3d neighborVelocity,
                     double spacing,
                     double overlapDirection) {
        if (!isFinite(self) || !isFinite(neighborPosition)) {
            return;
        }
        neighborPositionX += neighborPosition.x;
        neighborPositionY += neighborPosition.y;
        neighborPositionZ += neighborPosition.z;
        positionCount++;

        if (isFinite(neighborVelocity)) {
            neighborVelocityX += neighborVelocity.x;
            neighborVelocityY += neighborVelocity.y;
            neighborVelocityZ += neighborVelocity.z;
            velocityCount++;
        }

        double safeSpacing = positiveFinite(spacing, EPSILON);
        double awayX = self.x - neighborPosition.x;
        double awayY = self.y - neighborPosition.y;
        double awayZ = self.z - neighborPosition.z;
        double distanceSquared = awayX * awayX + awayY * awayY + awayZ * awayZ;
        if (!Double.isFinite(distanceSquared)) {
            return;
        }
        if (distanceSquared <= OVERLAP_DISTANCE_SQUARED) {
            // Exact overlaps lack a geometric direction. The caller supplies pair-opposed stable signs when known.
            separationX += Math.copySign(1.0, Double.isFinite(overlapDirection) ? overlapDirection : 1.0);
            return;
        }
        double distance = Math.sqrt(distanceSquared);
        if (distance >= safeSpacing) {
            return;
        }
        double scale = (safeSpacing - distance) / safeSpacing / distance;
        separationX += awayX * scale;
        separationY += awayY * scale;
        separationZ += awayZ * scale;
    }

    /**
     * Resolves a normalized desired translation. Leader velocity provides cruise feedforward while local separation,
     * alignment, cohesion, and a soft distant-leader pull shape the flock without assigning fixed slots.
     */
    @Nonnull
    Vector3d resolve(@Nonnull Vector3d self,
                     @Nonnull Vector3d leaderPosition,
                     @Nonnull Vector3d leaderVelocity,
                     double spacing,
                     double tightness,
                     double maximumSpeed,
                     double relativeSpeed,
                     @Nonnull Vector3d output) {
        double safeMaximumSpeed = positiveFinite(maximumSpeed, EPSILON);
        double correctionScale = clamp01(tightness) * clamp01(relativeSpeed);
        if (isFinite(leaderVelocity)) {
            output.set(leaderVelocity);
        } else {
            output.zero();
        }
        limitSpeed(output, safeMaximumSpeed);

        if (positionCount > 0 && isFinite(self)) {
            double centerX = neighborPositionX / positionCount - self.x;
            double centerY = neighborPositionY / positionCount - self.y;
            double centerZ = neighborPositionZ / positionCount - self.z;
            double safeSpacing = positiveFinite(spacing, EPSILON);
            addNormalized(output, centerX, centerY, centerZ,
                    safeMaximumSpeed * correctionScale * COHESION_WEIGHT
                            * unitScale(vectorLength(centerX, centerY, centerZ) / safeSpacing));
            addNormalized(output, separationX, separationY, separationZ,
                    safeMaximumSpeed * correctionScale * SEPARATION_WEIGHT
                            * unitScale(vectorLength(separationX, separationY, separationZ)));
        }
        if (velocityCount > 0) {
            double averageVelocityX = neighborVelocityX / velocityCount;
            double averageVelocityY = neighborVelocityY / velocityCount;
            double averageVelocityZ = neighborVelocityZ / velocityCount;
            double baselineVelocityX = isFinite(leaderVelocity.x) ? leaderVelocity.x : 0.0;
            double baselineVelocityY = isFinite(leaderVelocity.y) ? leaderVelocity.y : 0.0;
            double baselineVelocityZ = isFinite(leaderVelocity.z) ? leaderVelocity.z : 0.0;
            output.add(
                    (averageVelocityX - baselineVelocityX) * correctionScale * ALIGNMENT_WEIGHT,
                    (averageVelocityY - baselineVelocityY) * correctionScale * ALIGNMENT_WEIGHT,
                    (averageVelocityZ - baselineVelocityZ) * correctionScale * ALIGNMENT_WEIGHT);
        }
        addSoftLeaderAttraction(self, leaderPosition, spacing, safeMaximumSpeed, correctionScale, output);

        if (!isFinite(output)) {
            return output.zero();
        }
        limitSpeed(output, safeMaximumSpeed);
        return output.div(safeMaximumSpeed);
    }

    private static void addSoftLeaderAttraction(@Nonnull Vector3d self,
                                                 @Nonnull Vector3d leaderPosition,
                                                 double spacing,
                                                 double maximumSpeed,
                                                 double correctionScale,
                                                 @Nonnull Vector3d output) {
        if (!isFinite(self) || !isFinite(leaderPosition)) {
            return;
        }
        double towardX = leaderPosition.x - self.x;
        double towardY = leaderPosition.y - self.y;
        double towardZ = leaderPosition.z - self.z;
        double distanceSquared = towardX * towardX + towardY * towardY + towardZ * towardZ;
        if (!Double.isFinite(distanceSquared) || distanceSquared <= EPSILON * EPSILON) {
            return;
        }
        double distance = Math.sqrt(distanceSquared);
        double leash = positiveFinite(spacing, EPSILON) * 4.0;
        if (distance <= leash) {
            return;
        }
        double ramp = Math.min(1.0, (distance - leash) / leash);
        addNormalized(output, towardX, towardY, towardZ,
                maximumSpeed * correctionScale * LEADER_ATTRACTION_WEIGHT * ramp);
    }

    private static void addNormalized(@Nonnull Vector3d output,
                                      double x,
                                      double y,
                                      double z,
                                      double magnitude) {
        double lengthSquared = x * x + y * y + z * z;
        if (!Double.isFinite(lengthSquared) || lengthSquared <= EPSILON * EPSILON || magnitude <= 0.0) {
            return;
        }
        double scale = magnitude / Math.sqrt(lengthSquared);
        output.add(x * scale, y * scale, z * scale);
    }

    private static double vectorLength(double x, double y, double z) {
        double lengthSquared = x * x + y * y + z * z;
        return Double.isFinite(lengthSquared) ? Math.sqrt(lengthSquared) : 0.0;
    }

    private static void limitSpeed(@Nonnull Vector3d vector, double maximumSpeed) {
        double lengthSquared = vector.x * vector.x + vector.y * vector.y + vector.z * vector.z;
        if (!Double.isFinite(lengthSquared)) {
            vector.zero();
            return;
        }
        double maximumSquared = maximumSpeed * maximumSpeed;
        if (lengthSquared > maximumSquared) {
            vector.mul(maximumSpeed / Math.sqrt(lengthSquared));
        }
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return isFinite(vector.x) && isFinite(vector.y) && isFinite(vector.z);
    }

    private static boolean isFinite(double value) {
        return Double.isFinite(value);
    }

    private static double positiveFinite(double value, double fallback) {
        return Double.isFinite(value) && value > EPSILON ? value : fallback;
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static double unitScale(double value) {
        return clamp01(value);
    }
}
