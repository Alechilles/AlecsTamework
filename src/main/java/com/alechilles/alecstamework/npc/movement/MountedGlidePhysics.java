package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import javax.annotation.Nonnull;

/**
 * Deterministic mounted glide physics that can be tested without a live Hytale world.
 */
public final class MountedGlidePhysics {
    private MountedGlidePhysics() {
    }

    @Nonnull
    public static Output update(@Nonnull MountedGlidePhysicsState state,
                                @Nonnull TwMountedGlideConfig config,
                                @Nonnull Input input,
                                double dt) {
        double interval = Math.max(0.0, dt);
        TwMountedGlideConfig.GlideSettings glide = config.getGlide();
        TwMountedGlideConfig.FlapSettings flap = config.getFlap();
        TwMountedGlideConfig.AirbrakeSettings airbrake = config.getAirbrake();
        TwMountedGlideConfig.SafetySettings safety = config.getSafety();

        state.setFlapCooldownRemainingSeconds(state.getFlapCooldownRemainingSeconds() - interval);
        state.setBoostRemainingSeconds(state.getBoostRemainingSeconds() - interval);

        double maxPitch = Math.toRadians(Math.max(1.0, safety.getMaxPitchDegrees()));
        double pitch = clamp(input.pitchRadians(), -maxPitch, maxPitch);
        double pitchAmount = Math.min(1.0, Math.abs(pitch) / maxPitch);

        double speed = state.getGlideSpeed();
        double vertical = -glide.getPassiveSinkRate();
        boolean stalled = false;
        boolean airbraking = input.crouching();
        boolean flapped = false;
        boolean forwardFlap = false;

        if (pitch < 0.0) {
            speed += glide.getPitchDownAcceleration() * pitchAmount * interval;
            vertical -= glide.getPitchDownSink() * pitchAmount;
        } else if (pitch > 0.0) {
            boolean canLift = speed > glide.getStallThreshold();
            if (canLift) {
                vertical += glide.getPitchUpLiftConversion() * pitchAmount;
                speed -= glide.getPitchUpSpeedDrain() * pitchAmount * interval;
            } else {
                stalled = true;
                vertical -= glide.getStallSink() * pitchAmount;
            }
        }

        if (airbraking) {
            speed -= airbrake.getSpeedDecay() * interval;
            vertical *= airbrake.getSinkMultiplier();
        }

        if (input.jumpHeld()
                && config.getInput().isHeldJumpAutoFlap()
                && state.getFlapCooldownRemainingSeconds() <= 0.0) {
            flapped = true;
            forwardFlap = input.sprinting();
            if (forwardFlap) {
                speed += flap.getForwardBoostStrength();
            } else {
                vertical += flap.getUpwardBoostStrength();
            }
            state.setFlapCooldownRemainingSeconds(flap.getCooldownSeconds());
            state.setBoostRemainingSeconds(flap.getBoostDurationSeconds());
        }

        speed = clamp(speed, glide.getMinSpeed(), glide.getMaxSpeed());
        vertical = clamp(vertical, -safety.getMaxVerticalSpeed(), safety.getMaxVerticalSpeed());

        state.setGlideSpeed(speed);
        state.setVerticalVelocity(vertical);
        return new Output(speed, vertical, flapped, forwardFlap, stalled, airbraking);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Input(double pitchRadians,
                        double forwardIntent,
                        double strafeIntent,
                        boolean jumpHeld,
                        boolean sprinting,
                        boolean crouching) {
    }

    public record Output(double forwardSpeed,
                         double verticalVelocity,
                         boolean flapped,
                         boolean forwardFlap,
                         boolean stalled,
                         boolean airbraking) {
    }
}
