package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Pure formation slot and steering calculations, kept separate from current-world ECS access. */
final class FlightFormationSteering {
    private static final double EPSILON = 1.0E-6;
    private static final double CORRECTION_RESPONSE_PER_SECOND = 1.5;
    private static final double SLOT_MOTION_MATCH = 0.85;
    private static final double LOOSE_WARP_FREQUENCY = 0.045;
    private static final double CLUSTER_WARP_FREQUENCY = 0.055;
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    private static final double CLUSTER_HEIGHT_SEQUENCE = 0.7548776662466927;
    private static final double LOOSE_HEIGHT_SEQUENCE = 0.5698402909980532;
    private static final double TWO_PI = Math.PI * 2.0;

    private FlightFormationSteering() {
    }

    @Nonnull
    static Vector3d resolveTarget(@Nonnull BuilderBodyMotionTameworkFlightFormation.Formation formation,
                                  int followerIndex,
                                  double spacing,
                                  double driftSeconds,
                                  @Nonnull Vector3d leaderPosition,
                                  @Nonnull Vector3d heading,
                                  @Nonnull Vector3d output) {
        int index = Math.max(0, followerIndex);
        double safeSpacing = Math.max(EPSILON, spacing);
        double horizontalLength = Math.hypot(heading.x, heading.z);
        double forwardX = horizontalLength > EPSILON ? heading.x / horizontalLength : 0.0;
        double forwardZ = horizontalLength > EPSILON ? heading.z / horizontalLength : 1.0;
        double rightX = -forwardZ;
        double rightZ = forwardX;

        double trailing;
        double sideways;
        double vertical = 0.0;
        if (formation == BuilderBodyMotionTameworkFlightFormation.Formation.CHEVRON) {
            int row = index / 2 + 1;
            trailing = row * safeSpacing;
            sideways = (index & 1) == 0 ? row * safeSpacing : -row * safeSpacing;
        } else if (formation == BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER) {
            double slotAngle = (index + 1) * GOLDEN_ANGLE + centeredHash(index, 17) * 0.48;
            double radius = safeSpacing * 0.80 * Math.cbrt(index + 1.0);
            // Do not derive height from the angle's companion sequence. That produces a visible folded surface
            // in large flocks; this independent, deterministic sequence keeps slots evenly distributed in 3D.
            double heightFraction = clamp(-1.0, 1.0,
                    1.0 - 2.0 * fractionalPart(index * CLUSTER_HEIGHT_SEQUENCE + 0.10)
                            + centeredHash(index, 31) * 0.18);
            double horizontalRadius = radius * Math.sqrt(Math.max(0.0, 1.0 - heightFraction * heightFraction));
            double phase = driftSeconds * CLUSTER_WARP_FREQUENCY + unitHash(index, 43) * TWO_PI;
            double secondaryPhase = driftSeconds * 0.037 + unitHash(index, 59) * TWO_PI;
            double warp = Math.min(safeSpacing * 0.75, safeSpacing * 0.10 + radius * 0.05);
            double sinPhase = Math.sin(phase);
            double cosPhase = Math.cos(phase);
            double sinSecondaryPhase = Math.sin(secondaryPhase);
            // Slow, independently phased deformation keeps a flock alive without rotating or translating it as one.
            trailing = safeSpacing * 1.10 + radius * (0.55 + 0.25 * Math.cos(slotAngle))
                    + sinPhase * warp;
            sideways = horizontalRadius * Math.sin(slotAngle)
                    + (cosPhase + sinSecondaryPhase * 0.45) * warp * 0.70;
            vertical = radius * heightFraction
                    + (sinSecondaryPhase + cosPhase * 0.35) * warp * 0.55;
        } else {
            double slotAngle = (index + 1) * GOLDEN_ANGLE + centeredHash(index, 71) * 0.68;
            double radius = safeSpacing * 0.60 * Math.sqrt(index + 1.0);
            double phase = driftSeconds * LOOSE_WARP_FREQUENCY + unitHash(index, 83) * TWO_PI;
            double secondaryPhase = driftSeconds * 0.031 + unitHash(index, 97) * TWO_PI;
            double warp = Math.min(safeSpacing * 0.80, safeSpacing * 0.14 + radius * 0.04);
            double verticalSpan = Math.min(safeSpacing * 0.75, safeSpacing * 0.16 + radius * 0.04);
            double heightFraction = 1.0 - 2.0 * fractionalPart(index * LOOSE_HEIGHT_SEQUENCE + 0.20)
                    + centeredHash(index, 109) * 0.16;
            double sinPhase = Math.sin(phase);
            double cosPhase = Math.cos(phase);
            double sinSecondaryPhase = Math.sin(secondaryPhase);
            // Keep the expanding footprint behind the leader, but perturb its spiral and height bands into a volume.
            trailing = safeSpacing * 1.2 + radius * (0.85 + 0.55 * Math.cos(slotAngle))
                    + sinPhase * warp;
            sideways = radius * 0.95 * Math.sin(slotAngle)
                    + (cosPhase + sinSecondaryPhase * 0.45) * warp * 0.80;
            vertical = clamp(-1.0, 1.0, heightFraction) * verticalSpan
                    + (sinSecondaryPhase + cosPhase * 0.35) * warp * 0.55;
        }
        return output.set(
                leaderPosition.x - trailing * forwardX + sideways * rightX,
                leaderPosition.y + vertical,
                leaderPosition.z - trailing * forwardZ + sideways * rightZ);
    }

    @Nonnull
    static Vector3d resolveTranslation(@Nonnull Vector3d selfPosition,
                                       @Nonnull Vector3d targetPosition,
                                       @Nonnull Vector3d leaderVelocity,
                                       double maximumSpeed,
                                       double relativeSpeed,
                                       double tightness,
                                       double dt,
                                       @Nonnull Vector3d output) {
        double safeMaximumSpeed = Math.max(EPSILON, maximumSpeed);
        output.set(leaderVelocity);
        if (!isFinite(output)) {
            output.zero();
        }
        double leaderSpeed = output.length();
        if (leaderSpeed > safeMaximumSpeed) {
            output.mul(safeMaximumSpeed / leaderSpeed);
        }

        double correctionX = targetPosition.x - selfPosition.x;
        double correctionY = targetPosition.y - selfPosition.y;
        double correctionZ = targetPosition.z - selfPosition.z;
        double distance = Math.sqrt(
                correctionX * correctionX + correctionY * correctionY + correctionZ * correctionZ);
        if (Double.isFinite(distance) && distance > EPSILON) {
            double catchupCap = safeMaximumSpeed * clamp01(relativeSpeed);
            double responseSpeed = distance * CORRECTION_RESPONSE_PER_SECOND * clamp01(tightness);
            double noOvershootSpeed = dt > EPSILON ? distance / dt : responseSpeed;
            double correctionSpeed = Math.min(catchupCap, Math.min(responseSpeed, noOvershootSpeed));
            double correctionScale = correctionSpeed / distance;
            output.add(correctionX * correctionScale, correctionY * correctionScale, correctionZ * correctionScale);
        }

        double desiredSpeed = output.length();
        if (!isFinite(output) || !Double.isFinite(desiredSpeed)) {
            return output.zero();
        }
        if (desiredSpeed > safeMaximumSpeed) {
            output.mul(safeMaximumSpeed / desiredSpeed);
        }
        return output.div(safeMaximumSpeed);
    }

    /**
     * Match most of the slot's relative movement before position error builds up during a turn.
     * Leaving a small amount to position correction keeps the flock elastic rather than rigid.
     * Activation has no previous offset and should use the leader velocity directly.
     */
    @Nonnull
    static Vector3d resolveSlotVelocity(@Nonnull Vector3d previousOffset,
                                       @Nonnull Vector3d currentOffset,
                                       @Nonnull Vector3d leaderVelocity,
                                       double dt,
                                       @Nonnull Vector3d output) {
        if (!Double.isFinite(dt) || dt <= EPSILON) {
            return output.set(leaderVelocity);
        }
        double scale = SLOT_MOTION_MATCH / dt;
        return output.set(
                leaderVelocity.x + (currentOffset.x - previousOffset.x) * scale,
                leaderVelocity.y + (currentOffset.y - previousOffset.y) * scale,
                leaderVelocity.z + (currentOffset.z - previousOffset.z) * scale);
    }

    /** Ease slot changes relative to the leader, preserving straight-line travel without added lag. */
    @Nonnull
    static Vector3d smoothOffset(@Nonnull Vector3d current,
                                 @Nonnull Vector3d desired,
                                 double maximumSpeed,
                                 double dt,
                                 @Nonnull Vector3d output) {
        double distance = current.distance(desired);
        double stepTime = Math.max(0.0, dt);
        double blend = 1.0 - Math.exp(-stepTime * 2.0);
        if (distance > EPSILON) {
            blend = Math.min(blend, Math.max(0.0, maximumSpeed) * stepTime / distance);
        }
        return output.set(
                current.x + (desired.x - current.x) * blend,
                current.y + (desired.y - current.y) * blend,
                current.z + (desired.z - current.z) * blend);
    }

    static boolean smoothHeading(@Nonnull Vector3d current,
                                 @Nonnull Vector3d desired,
                                 double dt,
                                 @Nonnull Vector3d output) {
        double desiredLength = Math.hypot(desired.x, desired.z);
        if (!Double.isFinite(desiredLength) || desiredLength <= EPSILON) {
            return false;
        }
        double blend = 1.0 - Math.exp(-Math.max(0.0, dt) * 6.0);
        if (Math.hypot(current.x, current.z) <= EPSILON) {
            output.set(desired.x / desiredLength, 0.0, desired.z / desiredLength);
            return true;
        }
        // Interpolate the angle: normalizing a vector blend can get stuck when the leader reverses direction.
        double currentAngle = Math.atan2(current.z, current.x);
        double desiredAngle = Math.atan2(desired.z, desired.x);
        double difference = desiredAngle - currentAngle;
        double shortestTurn = Math.atan2(Math.sin(difference), Math.cos(difference));
        double angle = currentAngle + shortestTurn * blend;
        output.set(Math.cos(angle), 0.0, Math.sin(angle));
        return true;
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clamp(double minimum, double maximum, double value) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** Stable [0, 1) pseudo-random value for a formation slot, with no shared state or allocation. */
    private static double unitHash(int index, int salt) {
        long value = ((long) index + 1L) * 0x9E3779B97F4A7C15L + ((long) salt * 0xD1B54A32D192ED03L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double centeredHash(int index, int salt) {
        return unitHash(index, salt) * 2.0 - 1.0;
    }

    private static double fractionalPart(double value) {
        return value - Math.floor(value);
    }
}
