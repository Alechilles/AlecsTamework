package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Pure formation slot and steering calculations, kept separate from current-world ECS access. */
final class FlightFormationSteering {
    private static final double EPSILON = 1.0E-6;
    private static final double CORRECTION_RESPONSE_PER_SECOND = 1.5;
    private static final double LOOSE_DRIFT_FREQUENCY = 0.35;
    private static final double LOOSE_DRIFT_SCALE = 0.18;

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
        } else {
            double slotAngle = (index + 1) * 2.399963229728653;
            double radius = safeSpacing * 0.60 * Math.sqrt(index + 1.0);
            double phase = driftSeconds * LOOSE_DRIFT_FREQUENCY + index * 2.399963229728653;
            double drift = safeSpacing * LOOSE_DRIFT_SCALE;
            // Keep the expanding sunflower footprint behind the leader while its width and depth grow with sqrt(n).
            trailing = safeSpacing * 1.2 + radius * (1.0 + Math.cos(slotAngle))
                    + Math.sin(phase * 0.7) * drift;
            sideways = radius * Math.sin(slotAngle) + Math.cos(phase) * drift;
            double height = safeSpacing * (0.2 + 0.1 * ((index / 2) % 3));
            vertical = ((index & 1) == 0 ? height : -height)
                    + Math.sin(phase * 1.3) * safeSpacing * 0.08;
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
}
