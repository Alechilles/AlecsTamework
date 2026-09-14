package com.alechilles.alecstamework.npc.movement;

import org.joml.Vector3d;

/** Per-motion arrival hysteresis; the flock layout reserves room for this drift. */
final class FollowFlightSteering {
    static final double SETTLE_DISTANCE = 0.4;
    static final double RESUME_DISTANCE = 1.0;
    private boolean approaching;

    void reset() {
        approaching = false;
    }

    Vector3d resolve(Vector3d position, Vector3d target, double speed, double slowDownDistance,
                     double climbSpeed, double sinkSpeed, Vector3d output) {
        output.set(target).sub(position);
        double distance = output.length();
        double boundary = approaching ? SETTLE_DISTANCE : RESUME_DISTANCE;
        approaching = distance > boundary;
        if (!approaching) return output.zero();

        // Slow all three axes together. In particular, a small height error must not
        // request full climb/sink strength and reverse it on the following tick.
        double scale = Math.min(1, distance / Math.max(RESUME_DISTANCE, slowDownDistance));
        output.mul(speed * scale / distance);
        output.y = Math.max(-sinkSpeed, Math.min(climbSpeed, output.y));
        return output;
    }
}
