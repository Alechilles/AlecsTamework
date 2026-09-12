package com.alechilles.alecstamework.npc.movement;

/** Per-motion thermal progress; no world references or background work. */
final class KettleFlightState {
    private static final double WINGBEAT_CYCLE_SECONDS = 12.0;
    private static final double GLIDE_SECONDS = 10.5;
    private double elapsed;
    private double wingbeatPhase;

    void reset() {
        elapsed = 0.0;
        wingbeatPhase = 0.0;
    }

    void advance(double dt) {
        if (Double.isFinite(dt) && dt > 0.0) {
            elapsed = Math.min(300.0, elapsed + dt);
            wingbeatPhase = (wingbeatPhase + dt) % WINGBEAT_CYCLE_SECONDS;
        }
    }

    boolean shouldGlide(int memberIndex) {
        double offset = Math.floorMod(memberIndex, 6) * 2.0;
        return (wingbeatPhase + offset) % WINGBEAT_CYCLE_SECONDS < GLIDE_SECONDS;
    }

    double altitude(int memberIndex, double minimum, double maximum) {
        return Math.min(maximum, minimum + Math.floorMod(memberIndex, 4) * 2.0 + elapsed * 0.35);
    }

    static double radius(int memberIndex, double baseRadius) {
        return Math.max(1.0, baseRadius + (Math.floorMod(memberIndex, 3) - 1) * 1.5);
    }
}
