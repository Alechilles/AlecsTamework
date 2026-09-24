package com.alechilles.alecstamework.npc.movement;

/** Pure speed calculations for native swimming propulsion. */
public final class NativeSwimPhysics {
    private static final double MAX_STEP_SECONDS = 0.1;
    private static final double INPUT_DEADZONE = 0.1;

    private NativeSwimPhysics() {
    }

    public static Step advance(double speed,
                               double boostCooldownSeconds,
                               double forwardInput,
                               boolean boostRequested,
                               double dt,
                               Settings settings) {
        double safeSpeed = Double.isFinite(speed) ? Math.max(0.0, speed) : 0.0;
        double safeCooldown = Double.isFinite(boostCooldownSeconds)
                ? Math.max(0.0, boostCooldownSeconds)
                : 0.0;
        if (!Double.isFinite(speed)
                || !Double.isFinite(boostCooldownSeconds)
                || !Double.isFinite(forwardInput)
                || !Double.isFinite(dt)
                || dt <= 0.0
                || settings == null
                || !settings.isValid()) {
            return new Step(safeSpeed, safeCooldown);
        }

        double step = Math.min(dt, MAX_STEP_SECONDS);
        safeSpeed = Math.min(safeSpeed, settings.cruiseSpeed + settings.boostImpulse);
        safeCooldown = Math.max(0.0, safeCooldown - step);

        if (forwardInput > INPUT_DEADZONE) {
            if (safeSpeed > settings.cruiseSpeed) {
                safeSpeed = moveToward(safeSpeed, settings.cruiseSpeed,
                        settings.boostedDeceleration * step);
            } else {
                safeSpeed = moveToward(safeSpeed, settings.cruiseSpeed,
                        settings.acceleration * step);
            }
        } else if (forwardInput < -INPUT_DEADZONE) {
            safeSpeed = moveToward(safeSpeed, 0.0, settings.brakeDeceleration * step);
        } else {
            safeSpeed = moveToward(safeSpeed, 0.0, settings.coastDeceleration * step);
        }

        if (boostRequested && safeCooldown <= 0.0) {
            safeSpeed = Math.min(settings.cruiseSpeed + settings.boostImpulse,
                    safeSpeed + settings.boostImpulse);
            safeCooldown = settings.boostCooldownSeconds;
        }
        return new Step(safeSpeed, safeCooldown);
    }

    private static double moveToward(double value, double target, double amount) {
        if (value < target) {
            return Math.min(target, value + amount);
        }
        return Math.max(target, value - amount);
    }

    public record Settings(double cruiseSpeed,
                           double acceleration,
                           double coastDeceleration,
                           double brakeDeceleration,
                           double boostImpulse,
                           double boostCooldownSeconds,
                           double boostedDeceleration) {
        public static Settings defaults() {
            return new Settings(8.0, 4.0, 2.0, 8.0, 5.0, 2.0, 3.0);
        }

        public boolean isValid() {
            return Double.isFinite(cruiseSpeed) && cruiseSpeed > 0.0
                    && Double.isFinite(acceleration) && acceleration > 0.0
                    && Double.isFinite(coastDeceleration) && coastDeceleration >= 0.0
                    && Double.isFinite(brakeDeceleration) && brakeDeceleration > 0.0
                    && Double.isFinite(boostImpulse) && boostImpulse > 0.0
                    && Double.isFinite(boostCooldownSeconds) && boostCooldownSeconds > 0.0
                    && Double.isFinite(boostedDeceleration) && boostedDeceleration > 0.0;
        }
    }

    public record Step(double speed, double boostCooldownSeconds) {
    }
}
