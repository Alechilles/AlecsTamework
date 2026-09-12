package com.alechilles.alecstamework.npc.movement;

/** Per-motion thermal progress; no world references or background work. */
final class KettleFlightState {
    private static final double WINGBEAT_CYCLE_SECONDS = 12.0;
    private static final double GLIDE_SECONDS = 10.5;
    private static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887498949;
    private static final double MIN_RADIUS_MULTIPLIER = 0.65;
    private static final double MAX_RADIUS_MULTIPLIER = 1.5;
    private static final double MIN_SPEED_MULTIPLIER = 0.7;
    private static final double MAX_SPEED_MULTIPLIER = 1.15;
    private static final double MIN_ALTITUDE_FRACTION = 0.05;
    private static final double MAX_ALTITUDE_FRACTION = 0.95;
    private double wingbeatPhase;

    void reset() {
        wingbeatPhase = 0.0;
    }

    void advance(double dt) {
        if (Double.isFinite(dt) && dt > 0.0) {
            wingbeatPhase = (wingbeatPhase + dt) % WINGBEAT_CYCLE_SECONDS;
        }
    }

    boolean shouldGlide(int memberIndex) {
        double offset = Math.floorMod(memberIndex, 6) * 2.0;
        return (wingbeatPhase + offset) % WINGBEAT_CYCLE_SECONDS < GLIDE_SECONDS;
    }

    double altitude(int memberIndex, double minimum, double maximum) {
        double lower = Math.min(minimum, maximum);
        double upper = Math.max(minimum, maximum);
        return lower + (upper - lower) * interpolate(
                MIN_ALTITUDE_FRACTION, MAX_ALTITUDE_FRACTION, memberFraction(memberIndex, 0.17));
    }

    static double radius(int memberIndex, double baseRadius) {
        return Math.max(1.0, baseRadius * interpolate(
                MIN_RADIUS_MULTIPLIER, MAX_RADIUS_MULTIPLIER, memberFraction(memberIndex, 0.43)));
    }

    static double relativeSpeed(int memberIndex, double baseRelativeSpeed) {
        return Math.min(1.0, Math.max(0.0, baseRelativeSpeed * interpolate(
                MIN_SPEED_MULTIPLIER, MAX_SPEED_MULTIPLIER, memberFraction(memberIndex, 0.79))));
    }

    private static double memberFraction(int memberIndex, double phase) {
        double value = memberIndex * GOLDEN_RATIO_CONJUGATE + phase;
        return value - Math.floor(value);
    }

    private static double interpolate(double minimum, double maximum, double fraction) {
        return minimum + (maximum - minimum) * fraction;
    }
}
