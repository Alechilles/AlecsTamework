package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import javax.annotation.Nonnull;

/**
 * Mutable deterministic state for the mounted glide physics step.
 */
public final class MountedGlidePhysicsState {
    private double glideSpeed;
    private double verticalVelocity;
    private double flapCooldownRemainingSeconds;
    private double boostRemainingSeconds;

    public static MountedGlidePhysicsState from(@Nonnull TwMountedGlideConfig config) {
        MountedGlidePhysicsState state = new MountedGlidePhysicsState();
        state.glideSpeed = config.getGlide().getBaseSpeed();
        return state;
    }

    public double getGlideSpeed() {
        return glideSpeed;
    }

    public void setGlideSpeed(double glideSpeed) {
        this.glideSpeed = glideSpeed;
    }

    public double getVerticalVelocity() {
        return verticalVelocity;
    }

    public void setVerticalVelocity(double verticalVelocity) {
        this.verticalVelocity = verticalVelocity;
    }

    public double getFlapCooldownRemainingSeconds() {
        return flapCooldownRemainingSeconds;
    }

    public void setFlapCooldownRemainingSeconds(double flapCooldownRemainingSeconds) {
        this.flapCooldownRemainingSeconds = Math.max(0.0, flapCooldownRemainingSeconds);
    }

    public double getBoostRemainingSeconds() {
        return boostRemainingSeconds;
    }

    public void setBoostRemainingSeconds(double boostRemainingSeconds) {
        this.boostRemainingSeconds = Math.max(0.0, boostRemainingSeconds);
    }
}
