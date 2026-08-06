package com.alechilles.alecstamework.npc.movement;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** Performance-bounded local obstacle probing for autonomous flight steering. */
final class FlyingObstacleAvoidance {
    static final double PROBE_INTERVAL_SECONDS = 0.10;
    static final double HOLD_SECONDS = 0.35;
    static final int MAX_PROBES_PER_UPDATE = 6;

    private static final double MIN_LOOKAHEAD = 4.0;
    private static final double MAX_LOOKAHEAD = 12.0;
    private static final double LOOKAHEAD_SECONDS = 0.75;
    private static final double MIN_USEFUL_CLEARANCE = 0.25;
    private static final double CLIMB_ANGLE = Math.toRadians(35.0);
    private static final double DIAGONAL_CLIMB_ANGLE = Math.toRadians(25.0);
    private static final double SIDE_ANGLE = Math.toRadians(45.0);
    private static final double EPSILON = 1.0E-9;
    private static final int[] CANDIDATE_SIDES = { 0, -1, 1, -1, 1 };

    private final Vector3d probeDirection = new Vector3d();
    private final Vector3d desiredDirection = new Vector3d();
    private final Vector3d waypointDirection = new Vector3d();
    private final Vector3d heldDirection = new Vector3d();
    private final Vector3d[] candidates = {
            new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d()
    };

    private double probeRemaining;
    private double holdRemaining;
    private double fanCooldownRemaining;
    private double heldSpeedScale = 1.0;
    private int probesThisUpdate;
    private int rememberedSide;
    private boolean avoiding;
    private boolean blocked;

    @FunctionalInterface
    interface Probe {
        double probe(@Nonnull Vector3d direction);
    }

    void reset() {
        probeRemaining = 0.0;
        holdRemaining = 0.0;
        fanCooldownRemaining = 0.0;
        heldSpeedScale = 1.0;
        probesThisUpdate = 0;
        rememberedSide = 0;
        avoiding = false;
        blocked = false;
        heldDirection.zero();
    }

    void beginUpdate(double dt) {
        probesThisUpdate = 0;
        double elapsed = Math.max(0.0, dt);
        probeRemaining = Math.max(0.0, probeRemaining - elapsed);
        holdRemaining = Math.max(0.0, holdRemaining - elapsed);
        fanCooldownRemaining = Math.max(0.0, fanCooldownRemaining - elapsed);
    }

    @Nonnull
    Vector3d adjust(@Nonnull Vector3d desired,
                    @Nonnull Vector3d horizontalReference,
                    double maximumSpeed,
                    double turnRadius,
                    @Nonnull Probe probe,
                    @Nonnull Vector3d output) {
        double desiredMagnitude = desired.length();
        if (!isFinite(desired) || !Double.isFinite(desiredMagnitude) || desiredMagnitude <= EPSILON) {
            avoiding = false;
            blocked = false;
            return output.set(desired);
        }
        if (probeRemaining > 0.0) {
            if (blocked) {
                return output.zero();
            }
            return avoiding
                    ? output.set(heldDirection).mul(desiredMagnitude * heldSpeedScale)
                    : output.set(desired);
        }

        double lookahead = lookaheadDistance(desiredMagnitude, maximumSpeed, turnRadius);
        desiredDirection.set(desired).div(desiredMagnitude);

        if (avoiding && holdRemaining > 0.0) {
            double heldClearance = probeClearance(heldDirection, lookahead, probe);
            probeRemaining = PROBE_INTERVAL_SECONDS;
            if (heldClearance >= MIN_USEFUL_CLEARANCE) {
                blocked = false;
                heldSpeedScale = Math.min(1.0, heldClearance);
                return output.set(heldDirection).mul(desiredMagnitude * heldSpeedScale);
            }
            avoiding = false;
            blocked = true;
            holdRemaining = 0.0;
            heldSpeedScale = 0.0;
            heldDirection.zero();
            return output.zero();
        }

        double primaryClearance = probeClearance(desiredDirection, lookahead, probe);
        probeRemaining = PROBE_INTERVAL_SECONDS;
        if (primaryClearance + EPSILON >= 1.0) {
            avoiding = false;
            blocked = false;
            heldSpeedScale = 1.0;
            return output.set(desired);
        }

        if (avoiding) {
            double heldClearance = probeClearance(heldDirection, lookahead, probe);
            if (heldClearance >= MIN_USEFUL_CLEARANCE) {
                blocked = false;
                holdRemaining = HOLD_SECONDS;
                fanCooldownRemaining = HOLD_SECONDS;
                heldSpeedScale = Math.min(1.0, heldClearance);
                return output.set(heldDirection).mul(desiredMagnitude * heldSpeedScale);
            }
            avoiding = false;
            heldSpeedScale = 0.0;
            heldDirection.zero();
        }

        if (fanCooldownRemaining > 0.0) {
            blocked = true;
            return output.zero();
        }

        buildCandidates(desiredDirection, horizontalReference);
        int bestIndex = -1;
        double bestClearance = -1.0;
        double bestAlignment = -Double.MAX_VALUE;
        for (int i = 0; i < candidates.length && probesThisUpdate < MAX_PROBES_PER_UPDATE; i++) {
            Vector3d candidate = candidates[i];
            double clearance = probeClearance(candidate, lookahead, probe);
            double alignment = candidate.dot(desiredDirection);
            if (isBetterCandidate(clearance, alignment, CANDIDATE_SIDES[i],
                    bestClearance, bestAlignment, bestIndex < 0 ? 0 : CANDIDATE_SIDES[bestIndex])) {
                bestIndex = i;
                bestClearance = clearance;
                bestAlignment = alignment;
            }
        }

        fanCooldownRemaining = HOLD_SECONDS;
        if (bestIndex < 0 || bestClearance < MIN_USEFUL_CLEARANCE) {
            avoiding = false;
            blocked = true;
            heldSpeedScale = 0.0;
            heldDirection.zero();
            return output.zero();
        }

        avoiding = true;
        blocked = false;
        holdRemaining = HOLD_SECONDS;
        heldDirection.set(candidates[bestIndex]);
        heldSpeedScale = Math.min(1.0, bestClearance);
        if (CANDIDATE_SIDES[bestIndex] != 0) {
            rememberedSide = CANDIDATE_SIDES[bestIndex];
        }
        return output.set(heldDirection).mul(desiredMagnitude * heldSpeedScale);
    }

    double probeWaypoint(@Nonnull Vector3d route,
                         double maximumSpeed,
                         double turnRadius,
                         @Nonnull Probe probe) {
        double routeMagnitude = route.length();
        if (!isFinite(route) || !Double.isFinite(routeMagnitude) || routeMagnitude <= EPSILON
                || probesThisUpdate >= MAX_PROBES_PER_UPDATE) {
            return 0.0;
        }
        double lookahead = Math.min(routeMagnitude,
                lookaheadDistance(routeMagnitude, maximumSpeed, turnRadius));
        waypointDirection.set(route).div(routeMagnitude);
        return probeClearance(waypointDirection, lookahead, probe);
    }

    private double probeClearance(@Nonnull Vector3d direction,
                                  double lookahead,
                                  @Nonnull Probe probe) {
        if (probesThisUpdate >= MAX_PROBES_PER_UPDATE) {
            return 0.0;
        }
        probeDirection.set(direction).mul(lookahead);
        double clearDistance = probe.probe(probeDirection);
        probesThisUpdate++;
        if (!Double.isFinite(clearDistance) || clearDistance <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, clearDistance / lookahead);
    }

    private void buildCandidates(@Nonnull Vector3d original,
                                 @Nonnull Vector3d horizontalReference) {
        double horizontalLength = Math.hypot(original.x, original.z);
        double axisX;
        double axisZ;
        if (horizontalLength > EPSILON) {
            axisX = original.x / horizontalLength;
            axisZ = original.z / horizontalLength;
        } else {
            double referenceLength = Math.hypot(horizontalReference.x, horizontalReference.z);
            if (referenceLength > EPSILON) {
                axisX = horizontalReference.x / referenceLength;
                axisZ = horizontalReference.z / referenceLength;
            } else {
                axisX = 0.0;
                axisZ = 1.0;
            }
        }

        if (horizontalLength <= EPSILON) {
            setCandidate(candidates[0], axisX, axisZ, 0.0, CLIMB_ANGLE);
            setCandidate(candidates[1], axisX, axisZ, -SIDE_ANGLE, 0.0);
            setCandidate(candidates[2], axisX, axisZ, SIDE_ANGLE, 0.0);
            setCandidate(candidates[3], axisX, axisZ, -SIDE_ANGLE, DIAGONAL_CLIMB_ANGLE);
            setCandidate(candidates[4], axisX, axisZ, SIDE_ANGLE, DIAGONAL_CLIMB_ANGLE);
            return;
        }

        double basePitch = Math.asin(Math.max(-1.0, Math.min(1.0, original.y)));
        setCandidate(candidates[0], axisX, axisZ, 0.0, Math.max(CLIMB_ANGLE, basePitch));
        setCandidate(candidates[1], axisX, axisZ, -SIDE_ANGLE, Math.max(0.0, basePitch));
        setCandidate(candidates[2], axisX, axisZ, SIDE_ANGLE, Math.max(0.0, basePitch));
        setCandidate(candidates[3], axisX, axisZ, -SIDE_ANGLE,
                Math.max(DIAGONAL_CLIMB_ANGLE, basePitch));
        setCandidate(candidates[4], axisX, axisZ, SIDE_ANGLE,
                Math.max(DIAGONAL_CLIMB_ANGLE, basePitch));
    }

    private static void setCandidate(@Nonnull Vector3d output,
                                     double axisX,
                                     double axisZ,
                                     double yawOffset,
                                     double pitch) {
        double yawCos = Math.cos(yawOffset);
        double yawSin = Math.sin(yawOffset);
        double rotatedX = axisX * yawCos - axisZ * yawSin;
        double rotatedZ = axisX * yawSin + axisZ * yawCos;
        double horizontalScale = Math.cos(pitch);
        output.set(rotatedX * horizontalScale, Math.sin(pitch), rotatedZ * horizontalScale).normalize();
    }

    private boolean isBetterCandidate(double clearance,
                                      double alignment,
                                      int side,
                                      double bestClearance,
                                      double bestAlignment,
                                      int bestSide) {
        boolean clear = clearance + EPSILON >= 1.0;
        boolean bestClear = bestClearance + EPSILON >= 1.0;
        if (clear != bestClear) {
            return clear;
        }
        if (Math.abs(clearance - bestClearance) > EPSILON) {
            return clearance > bestClearance;
        }
        if (Math.abs(alignment - bestAlignment) > EPSILON) {
            return alignment > bestAlignment;
        }
        return side == rememberedSide && bestSide != rememberedSide;
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    static double lookaheadDistance(double desiredMagnitude, double maximumSpeed, double turnRadius) {
        double distance = Math.max(0.0, turnRadius)
                + Math.max(0.0, maximumSpeed)
                * Math.min(2.0, Math.max(0.0, desiredMagnitude))
                * LOOKAHEAD_SECONDS;
        return Math.max(MIN_LOOKAHEAD, Math.min(MAX_LOOKAHEAD, distance));
    }

    int getProbesThisUpdate() {
        return probesThisUpdate;
    }
}
