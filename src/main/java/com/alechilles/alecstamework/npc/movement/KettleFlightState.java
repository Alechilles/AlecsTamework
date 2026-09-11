package com.alechilles.alecstamework.npc.movement;

/** Per-motion thermal progress; no world references or background work. */
final class KettleFlightState {
    private double elapsed;

    void reset() {
        elapsed = 0.0;
    }

    void advance(double dt) {
        if (Double.isFinite(dt) && dt > 0.0) {
            elapsed = Math.min(300.0, elapsed + dt);
        }
    }

    double altitude(int memberIndex, double minimum, double maximum) {
        return Math.min(maximum, minimum + Math.floorMod(memberIndex, 4) * 2.0 + elapsed * 0.35);
    }

    static double radius(int memberIndex, double baseRadius) {
        return Math.max(1.0, baseRadius + (Math.floorMod(memberIndex, 3) - 1) * 1.5);
    }
}
