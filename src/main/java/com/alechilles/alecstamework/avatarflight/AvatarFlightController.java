package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import javax.annotation.Nonnull;

/**
 * Pure velocity controller for transformed-player avatar flight.
 */
public final class AvatarFlightController {
    private static final double MIN_FORWARD_FOR_BRAKE = 0.15;
    private static final double MIN_FORWARD_FOR_PITCH_TRADE = 0.5;
    private static final double HORIZONTAL_IDLE_SPEED = 0.25;
    private static final double MAX_PHYSICAL_PITCH_UP_RADIANS = Math.toRadians(60.0);
    private static final double MAX_VISUAL_ROLL_RADIANS = Math.toRadians(20.0);
    private static final double PITCH_UP_FORWARD_DRAG_MULTIPLIER = 4.0;

    private AvatarFlightController() {
    }

    @Nonnull
    public static Output update(@Nonnull State state,
                                @Nonnull Input input,
                                @Nonnull TwAvatarFlightConfig config,
                                double dt,
                                long nowMs) {
        TwAvatarFlightConfig.MovementSettings movement = config.getMovement();
        double yaw = input.yawRadians();
        double forwardX = -Math.sin(yaw);
        double forwardZ = -Math.cos(yaw);
        double rightX = -Math.sin(yaw - Math.PI / 2.0);
        double rightZ = -Math.cos(yaw - Math.PI / 2.0);
        double currentForwardSpeed = state.velocityX() * forwardX + state.velocityZ() * forwardZ;
        double currentStrafeSpeed = state.velocityX() * rightX + state.velocityZ() * rightZ;
        double currentHorizontalSpeed = Math.sqrt(state.velocityX() * state.velocityX()
                + state.velocityZ() * state.velocityZ());

        double targetForwardSpeed = currentForwardSpeed;
        double targetStrafeSpeed = currentStrafeSpeed;
        double vertical = state.velocityY();
        AvatarFlightMode mode;
        double pitchUpAmount = pitchUpAmount(input.pitchRadians());

        boolean jumpIntent = input.jump() || input.verticalAxis() > 0.0;
        boolean descendIntent = input.crouch() || input.verticalAxis() < 0.0;
        boolean explicitAirbrakeIntent = input.airbrake();
        if (input.onGround() && !jumpIntent) {
            return new Output(AvatarFlightMode.GROUNDED, 0.0, 0.0, 0.0,
                    state.nextJumpAtMs(), state.nextBoostAtMs(), false, false, false,
                    true, false, 0.0, 0.0);
        }

        if (explicitAirbrakeIntent) {
            targetForwardSpeed = approach(targetForwardSpeed, 0.0, movement.getAirbrakeDeceleration() * dt);
            targetStrafeSpeed = approach(targetStrafeSpeed, 0.0, movement.getAirbrakeDeceleration() * dt);
            mode = AvatarFlightMode.BRAKING;
        } else if (input.forwardAxis() > config.getInput().getForwardDeadzone()) {
            if (pitchUpAmount > 0.0) {
                targetForwardSpeed = Math.max(0.0, Math.max(targetForwardSpeed, currentHorizontalSpeed));
            } else {
                targetForwardSpeed = approach(
                        targetForwardSpeed,
                        movement.getMaxForwardSpeed() * input.forwardAxis(),
                        movement.getForwardAcceleration() * dt
                );
            }
            mode = AvatarFlightMode.FORWARD_FLIGHT;
        } else if (input.forwardAxis() < -config.getInput().getForwardDeadzone()) {
            if (currentForwardSpeed > MIN_FORWARD_FOR_BRAKE) {
                targetForwardSpeed = Math.max(0.0, currentForwardSpeed - movement.getAirbrakeDeceleration() * dt);
                mode = AvatarFlightMode.BRAKING;
            } else {
                targetForwardSpeed = approach(
                        targetForwardSpeed,
                        -movement.getMaxBackwardSpeed(),
                        movement.getBackwardAcceleration() * dt
                );
                mode = AvatarFlightMode.BACKING;
            }
        } else {
            targetForwardSpeed = approach(targetForwardSpeed, 0.0, movement.getHoverHorizontalDamping() * dt);
            mode = AvatarFlightMode.HOVER;
        }
        if (explicitAirbrakeIntent) {
            // Airbrake already owns lateral damping above.
        } else if (Math.abs(input.strafeAxis()) > config.getInput().getStrafeDeadzone()) {
            targetStrafeSpeed = approach(
                    targetStrafeSpeed,
                    movement.getMaxBackwardSpeed() * input.strafeAxis(),
                    movement.getBackwardAcceleration() * dt
            );
        } else {
            targetStrafeSpeed = approach(targetStrafeSpeed, 0.0, movement.getHoverHorizontalDamping() * dt);
        }

        boolean jumpApplied = false;
        boolean boostApplied = false;
        long nextJumpAtMs = state.nextJumpAtMs();
        long nextBoostAtMs = state.nextBoostAtMs();
        if (jumpIntent && (nextJumpAtMs == 0L || nowMs >= nextJumpAtMs)) {
            vertical = Math.max(vertical, 0.0) + config.getJump().getUpwardImpulse();
            nextJumpAtMs = nowMs + Math.round(config.getJump().getCooldownSeconds() * 1000.0);
            jumpApplied = true;
        }
        if (!explicitAirbrakeIntent && input.sprint() && (nextBoostAtMs == 0L || nowMs >= nextBoostAtMs)) {
            targetForwardSpeed = Math.min(
                    movement.getMaxForwardSpeed(),
                    Math.max(targetForwardSpeed, 0.0) + config.getBoost().getForwardImpulse()
            );
            nextBoostAtMs = nowMs + Math.round(config.getBoost().getCooldownSeconds() * 1000.0);
            boostApplied = true;
            mode = AvatarFlightMode.FORWARD_FLIGHT;
        }

        if (explicitAirbrakeIntent) {
            vertical = approach(vertical, 0.0, movement.getAirbrakeDeceleration() * dt);
            mode = AvatarFlightMode.BRAKING;
        } else if (descendIntent) {
            vertical = -movement.getDescendSpeed();
            mode = AvatarFlightMode.DESCENDING;
        } else if (targetForwardSpeed > MIN_FORWARD_FOR_PITCH_TRADE) {
            PitchAdjustment pitch = applyPitch(input.pitchRadians(), targetForwardSpeed, vertical, movement, dt);
            targetForwardSpeed = pitch.forwardSpeed();
            vertical = pitch.verticalSpeed();
        } else if (!jumpApplied) {
            vertical = approach(vertical, 0.0, movement.getHoverVerticalDamping() * dt);
        }

        vertical = Math.max(-movement.getMaxFallSpeed(), Math.min(movement.getMaxFallSpeed(), vertical));
        double x = forwardX * targetForwardSpeed + rightX * targetStrafeSpeed;
        double z = forwardZ * targetForwardSpeed + rightZ * targetStrafeSpeed;
        boolean applyVelocity = mode != AvatarFlightMode.GROUNDED;
        double horizontalSpeed = Math.sqrt(x * x + z * z);
        boolean horizontalIdle = horizontalSpeed < HORIZONTAL_IDLE_SPEED;
        boolean fastFlight = !horizontalIdle && input.sprint() && mode == AvatarFlightMode.FORWARD_FLIGHT;
        double visualPitch = input.pitchRadians();
        double visualRoll = resolveVisualRoll(
                state.velocityX(),
                state.velocityZ(),
                x,
                z,
                targetStrafeSpeed,
                movement.getMaxForwardSpeed()
        );
        return new Output(mode, x, vertical, z, nextJumpAtMs, nextBoostAtMs, applyVelocity, jumpApplied, boostApplied,
                horizontalIdle, fastFlight, visualPitch, visualRoll);
    }

    @Nonnull
    private static PitchAdjustment applyPitch(double pitchRadians,
                                              double forwardSpeed,
                                              double verticalSpeed,
                                              @Nonnull TwAvatarFlightConfig.MovementSettings movement,
                                              double dt) {
        if (pitchRadians > 0.0) {
            double amount = pitchUpAmount(pitchRadians);
            double physicalPitch = Math.min(MAX_PHYSICAL_PITCH_UP_RADIANS, pitchRadians);
            double drag = movement.getPitchUpSpeedCost() * amount * dt;
            double glideSpeed = Math.sqrt(forwardSpeed * forwardSpeed + verticalSpeed * verticalSpeed);
            double effectiveSpeed = Math.max(0.0, glideSpeed - drag);
            double energyForwardSpeed = Math.min(movement.getMaxForwardSpeed(),
                    effectiveSpeed * Math.cos(physicalPitch));
            double retainedForwardSpeed = Math.max(0.0,
                    forwardSpeed - drag * PITCH_UP_FORWARD_DRAG_MULTIPLIER);
            double targetForwardSpeed = Math.min(movement.getMaxForwardSpeed(),
                    Math.max(energyForwardSpeed, retainedForwardSpeed));
            double targetVerticalSpeed = Math.min(movement.getMaxFallSpeed(),
                    effectiveSpeed * Math.sin(physicalPitch));
            double turnDelta = movement.getPitchUpLiftScale() * Math.max(1.0, glideSpeed) * amount * dt;
            return new PitchAdjustment(
                    Math.max(0.0, approach(forwardSpeed, targetForwardSpeed, turnDelta)),
                    approach(verticalSpeed, targetVerticalSpeed, turnDelta)
            );
        }
        if (pitchRadians < 0.0) {
            double amount = Math.min(1.0, -pitchRadians / Math.toRadians(70.0));
            return new PitchAdjustment(
                    Math.min(movement.getMaxForwardSpeed(), forwardSpeed + movement.getPitchDownSpeedGain() * amount * dt),
                    verticalSpeed - movement.getPitchDownDiveScale() * amount * dt
            );
        }
        return new PitchAdjustment(forwardSpeed, verticalSpeed);
    }

    private static double pitchUpAmount(double pitchRadians) {
        if (pitchRadians <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, pitchRadians / Math.toRadians(70.0));
    }

    private static double approach(double current, double target, double maxDelta) {
        if (current < target) {
            return Math.min(target, current + Math.max(0.0, maxDelta));
        }
        if (current > target) {
            return Math.max(target, current - Math.max(0.0, maxDelta));
        }
        return current;
    }

    private static double resolveVisualRoll(double previousX,
                                            double previousZ,
                                            double nextX,
                                            double nextZ,
                                            double strafeSpeed,
                                            double maxForwardSpeed) {
        double strafeRoll = resolveStrafeRoll(strafeSpeed, maxForwardSpeed);
        double turnRoll = resolveTurnRoll(previousX, previousZ, nextX, nextZ);
        return Math.abs(turnRoll) > Math.abs(strafeRoll) ? turnRoll : strafeRoll;
    }

    private static double resolveStrafeRoll(double strafeSpeed, double maxForwardSpeed) {
        if (maxForwardSpeed <= 0.0) {
            return 0.0;
        }
        double amount = clamp(strafeSpeed / maxForwardSpeed, -1.0, 1.0);
        return -amount * MAX_VISUAL_ROLL_RADIANS;
    }

    private static double resolveTurnRoll(double previousX, double previousZ, double nextX, double nextZ) {
        double previousSpeed = Math.hypot(previousX, previousZ);
        double nextSpeed = Math.hypot(nextX, nextZ);
        if (previousSpeed < HORIZONTAL_IDLE_SPEED || nextSpeed < HORIZONTAL_IDLE_SPEED) {
            return 0.0;
        }
        double normalizedPreviousX = previousX / previousSpeed;
        double normalizedPreviousZ = previousZ / previousSpeed;
        double normalizedNextX = nextX / nextSpeed;
        double normalizedNextZ = nextZ / nextSpeed;
        double signedTurn = clamp(
                normalizedPreviousX * normalizedNextZ - normalizedPreviousZ * normalizedNextX,
                -1.0,
                1.0
        );
        return -signedTurn * MAX_VISUAL_ROLL_RADIANS;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PitchAdjustment(double forwardSpeed, double verticalSpeed) {
    }

    public record State(double velocityX,
                        double velocityY,
                        double velocityZ,
                        long nextJumpAtMs,
                        long nextBoostAtMs) {
        @Nonnull
        public static State from(@Nonnull AvatarFlightComponent component) {
            return new State(
                    component.getVelocityX(),
                    component.getVelocityY(),
                    component.getVelocityZ(),
                    component.getNextJumpAtMs(),
                    component.getNextBoostAtMs()
            );
        }
    }

    public record Input(double forwardAxis,
                        double strafeAxis,
                        double verticalAxis,
                        boolean jump,
                        boolean crouch,
                        boolean sprint,
                        boolean airbrake,
                        boolean onGround,
                        double yawRadians,
                        double pitchRadians) {
    }

    public record Output(@Nonnull AvatarFlightMode mode,
                         double velocityX,
                         double velocityY,
                         double velocityZ,
                         long nextJumpAtMs,
                         long nextBoostAtMs,
                         boolean applyVelocity,
                         boolean jumpApplied,
                         boolean boostApplied,
                         boolean horizontalIdle,
                         boolean fastFlight,
                         double visualPitchRadians,
                         double visualRollRadians) {
    }
}
