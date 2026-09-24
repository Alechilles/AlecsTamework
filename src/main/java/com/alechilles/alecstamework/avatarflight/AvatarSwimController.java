package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nonnull;

/** Pure underwater propulsion using the existing avatar session and ability cooldown. */
final class AvatarSwimController {
    private AvatarSwimController() {
    }

    @Nonnull
    static AvatarFlightController.Output update(@Nonnull AvatarFlightController.State state,
            @Nonnull AvatarFlightController.Input input, @Nonnull TwAvatarFlightConfig config,
            @Nonnull AvatarFlightProgressionTuning tuning, double dt, long nowMs) {
        if (!input.inFluid()) {
            return AvatarFlightController.groundedOutput(state);
        }
        dt = Math.max(0, dt);
        var movement = config.getMovement();
        double cruise = movement.getMaxForwardSpeed();
        double cap = cruise + config.getBoost().getForwardImpulse();
        double speed = Math.sqrt(state.velocityX() * state.velocityX()
                + state.velocityY() * state.velocityY() + state.velocityZ() * state.velocityZ());
        double forward = axis(input.forwardAxis(), config.getInput().getForwardDeadzone());
        double strafe = axis(input.strafeAxis(), config.getInput().getStrafeDeadzone());
        double vertical = input.crouch() ? -1 : input.jump() ? 1 : axis(input.verticalAxis(), 0.25);
        double pitch = input.pitchRadians();
        double yaw = input.yawRadians();
        double lookX = -Math.sin(yaw) * Math.cos(pitch);
        double lookY = Math.sin(pitch);
        double lookZ = -Math.cos(yaw) * Math.cos(pitch);
        double x = lookX * forward + Math.cos(yaw) * strafe;
        double y = lookY * forward + vertical;
        double z = lookZ * forward - Math.sin(yaw) * strafe;
        double length = Math.sqrt(x * x + y * y + z * z);
        boolean moving = length > 0.00001;
        long nextBoost = state.nextBoostAtMs();
        long cooldownMs = Math.round(config.getBoost().getCooldownSeconds() * 1000);
        long durationMs = Math.round(config.getBoost().getDurationSeconds() * 1000);
        boolean boostApplied = !input.airbrake() && input.sprint() && input.boostAllowed()
                && (nextBoost == 0 || nowMs >= nextBoost);
        // Mode owns cancellation; the existing cooldown deadline supplies the boost clock.
        boolean boostActive = state.mode() == AvatarFlightMode.BOOSTING && nextBoost != 0
                && nowMs < nextBoost - cooldownMs + durationMs;
        if (boostApplied) {
            speed = Math.min(cap, speed + config.getBoost().getForwardImpulse()
                    * tuning.forwardBoostImpulseMultiplier());
            nextBoost = nowMs + cooldownMs;
            boostActive = true;
        }
        double target = moving ? (forward < 0 ? movement.getMaxBackwardSpeed()
                : forward > 0 ? cruise : movement.getDescendSpeed()) : 0;
        if (input.airbrake()) {
            boostActive = false;
            speed = approach(speed, 0, movement.getAirbrakeDeceleration() * dt);
        } else if (boostActive) {
            // A boost is forward along the view, including when starting from rest.
            x = lookX;
            y = lookY;
            z = lookZ;
            length = 1;
            speed = approach(speed, cap,
                    config.getBoost().getForwardImpulse() / config.getBoost().getDurationSeconds() * dt);
        } else {
            double rate = speed > cruise ? config.getCurve().getBoostedSpeedDecay()
                    : !moving ? movement.getHoverHorizontalDamping()
                    : forward < 0 ? movement.getBackwardAcceleration() : movement.getForwardAcceleration();
            speed = approach(speed, Math.min(cruise, target), rate * dt);
            if (moving && forward <= 0) {
                speed = Math.min(speed, Math.min(cruise, target));
            }
        }
        speed = Math.min(cap, speed);
        if ((!moving && !boostActive) || input.airbrake()) {
            // Preserve the current heading while coasting or braking.
            x = state.velocityX();
            y = state.velocityY();
            z = state.velocityZ();
            length = Math.sqrt(x * x + y * y + z * z);
        }
        double scale = length > 0.00001 ? speed / length : 0;
        boolean idle = speed < 0.25;
        return new AvatarFlightController.Output(
                input.airbrake() ? AvatarFlightMode.BRAKING
                        : boostActive ? AvatarFlightMode.BOOSTING
                        : idle ? AvatarFlightMode.HOVER : AvatarFlightMode.FORWARD_FLIGHT,
                x * scale, y * scale, z * scale, state.nextJumpAtMs(), nextBoost,
                state.nextLaunchAtMs(), 0, 0, true, false, boostApplied, false, 0,
                idle, AvatarFlightSpeedMetrics.isFastFlightSpeed(speed, config), pitch, 0,
                AvatarFlightSpeedMetrics.speedRatio(input.airbrake() ? 0 : boostActive ? cap : target, config),
                input.airbrake() && input.airbrakeActivated());
    }

    private static double axis(double value, double deadzone) {
        return Math.abs(value) > deadzone ? Math.signum(value) : 0;
    }

    private static double approach(double value, double target, double step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }
}
