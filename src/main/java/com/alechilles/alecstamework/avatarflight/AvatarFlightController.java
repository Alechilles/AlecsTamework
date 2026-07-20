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
    private static final double NEUTRAL_PITCH_RADIANS = Math.toRadians(2.0);
    private static final double MIN_DIVE_PITCH_RADIANS = Math.toRadians(8.0);
    private static final double MAX_PHYSICAL_PITCH_UP_RADIANS = Math.toRadians(60.0);
    private static final double MAX_VISUAL_ROLL_RADIANS = Math.toRadians(30.0);
    private static final double PITCH_UP_FORWARD_DRAG_MULTIPLIER = 1.4;
    private static final double STRAFE_ROLL_GAIN = 1.5;
    private static final double TURN_ROLL_GAIN = 1.35;

    private AvatarFlightController() {
    }

    @Nonnull
    public static Output update(@Nonnull State state,
                                @Nonnull Input input,
                                @Nonnull TwAvatarFlightConfig config,
                                double dt,
                                long nowMs) {
        dt = Math.max(0.0, dt);
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
        double rawPitchRadians = input.pitchRadians();
        boolean neutralPitch = isNeutralPitch(rawPitchRadians);
        double effectivePitchRadians = neutralPitch ? 0.0 : rawPitchRadians;
        double pitchUpAmount = pitchUpAmount(effectivePitchRadians);
        boolean pitchDownIntent = effectivePitchRadians < 0.0;

        long nextJumpAtMs = state.nextJumpAtMs();
        long nextBoostAtMs = state.nextBoostAtMs();
        long nextLaunchAtMs = state.nextLaunchAtMs();
        boolean jumpIntent = (input.jump() || input.verticalAxis() > 0.0) && input.flapAllowed();
        boolean boostIntent = input.sprint() && input.boostAllowed();
        boolean descendIntent = input.crouch() || input.verticalAxis() < 0.0;
        boolean explicitAirbrakeIntent = input.airbrake();
        boolean activeFlight = state.mode() != AvatarFlightMode.GROUNDED;
        boolean airbrakeApplied = explicitAirbrakeIntent && input.airbrakeActivated() && activeFlight;
        if (input.inFluid()) {
            return groundedOutput(state);
        }
        if (config.getLaunch().isEnabled()
                && input.launchAllowed()
                && input.launchHoldMs() >= config.getLaunch().getMinChargeMs()
                && (nextLaunchAtMs == 0L || nowMs >= nextLaunchAtMs)) {
            AvatarFlightLaunchCurve.Impulse impulse = AvatarFlightLaunchCurve.impulse(
                    config.getLaunch(),
                    input.launchHoldMs()
            );
            double launchCost = AvatarFlightLaunchCurve.cost(config.getLaunch(), input.launchHoldMs());
            nextLaunchAtMs = nowMs + Math.round(config.getJump().getCooldownSeconds() * 1000.0);
            return new Output(
                    AvatarFlightMode.LAUNCHING,
                    forwardX * impulse.forward(),
                    impulse.up(),
                    forwardZ * impulse.forward(),
                    nextJumpAtMs,
                    nextBoostAtMs,
                    nextLaunchAtMs,
                    0.0,
                    0.0,
                    true,
                    false,
                    false,
                    true,
                    launchCost,
                    false,
                    false,
                    input.pitchRadians(),
                    0.0,
                    AvatarFlightSpeedMetrics.speedRatio(impulse.forward(), config)
            );
        }
        boolean flapReady = nextJumpAtMs == 0L || nowMs >= nextJumpAtMs;
        boolean boostReady = nextBoostAtMs == 0L || nowMs >= nextBoostAtMs;
        boolean flightStartIntent = (jumpIntent && flapReady) || (boostIntent && boostReady);
        if (!flightStartIntent && (input.onGround() || !activeFlight)) {
            return groundedOutput(state);
        }
        boolean pitchControlsActive = !explicitAirbrakeIntent && !descendIntent;
        double diveLoad = AvatarFlightManeuverMath.updateLoad(
                state.diveLoad(),
                pitchControlsActive && effectivePitchRadians < 0.0,
                dt,
                config.getCurve().getDiveLoadRampSeconds(),
                config.getCurve().getDiveLoadDecaySeconds()
        );
        double climbLoad = AvatarFlightManeuverMath.updateLoad(
                state.climbLoad(),
                pitchControlsActive && effectivePitchRadians > 0.0,
                dt,
                config.getCurve().getClimbLoadRampSeconds(),
                config.getCurve().getClimbLoadDecaySeconds()
        );

        if (explicitAirbrakeIntent) {
            targetForwardSpeed = approach(targetForwardSpeed, 0.0, movement.getAirbrakeDeceleration() * dt);
            targetStrafeSpeed = approach(targetStrafeSpeed, 0.0, movement.getAirbrakeDeceleration() * dt);
            mode = AvatarFlightMode.BRAKING;
        } else if (input.forwardAxis() > config.getInput().getForwardDeadzone()) {
            if (pitchUpAmount > 0.0) {
                targetForwardSpeed = forwardStartSpeed(movement,
                        Math.max(targetForwardSpeed, currentHorizontalSpeed),
                        input.forwardAxis());
            } else if (pitchDownIntent) {
                targetForwardSpeed = forwardStartSpeed(movement,
                        Math.max(targetForwardSpeed, currentHorizontalSpeed),
                        input.forwardAxis());
            } else {
                targetForwardSpeed = neutralForwardFlightSpeed(movement, input.forwardAxis(), currentForwardSpeed, dt);
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
        double boostDurationSeconds = config.getBoost().getDurationSeconds();
        long boostDurationMs = Math.round(boostDurationSeconds * 1000.0);
        long boostCooldownMs = Math.round(config.getBoost().getCooldownSeconds() * 1000.0);
        boolean boostActive = isBoostActive(nextBoostAtMs, boostCooldownMs, boostDurationMs, nowMs);
        double glideHorizontalCap = AvatarFlightSpeedMetrics.glideHorizontalCap(config);
        double boostedHorizontalCap = AvatarFlightSpeedMetrics.boostedHorizontalCap(config);
        if (jumpIntent && flapReady) {
            vertical = Math.max(vertical, 0.0) + config.getJump().getUpwardImpulse();
            nextJumpAtMs = nowMs + Math.round(config.getJump().getCooldownSeconds() * 1000.0);
            jumpApplied = true;
        }
        if (!explicitAirbrakeIntent && boostIntent && boostReady) {
            double boost = config.getBoost().getForwardImpulse();
            if (config.getBoost().isDirectional()) {
                double absPitch = Math.abs(effectivePitchRadians);
                double horizontalImpulse = boost * Math.cos(absPitch);
                targetForwardSpeed = Math.min(
                        boostedHorizontalCap,
                        Math.max(Math.max(targetForwardSpeed, currentForwardSpeed), 0.0) + horizontalImpulse
                );
                if (effectivePitchRadians < 0.0) {
                    vertical -= boost * Math.sin(absPitch);
                } else if (effectivePitchRadians > 0.0) {
                    vertical += Math.min(
                            boost * Math.sin(absPitch) * config.getBoost().getUpwardPitchLiftMultiplier(),
                            config.getBoost().getUpwardPitchLiftCap()
                    );
                }
            } else {
                targetForwardSpeed = Math.min(
                        boostedHorizontalCap,
                        Math.max(Math.max(targetForwardSpeed, currentForwardSpeed), 0.0) + boost
                );
            }
            nextBoostAtMs = nowMs + boostCooldownMs;
            boostActive = true;
            boostApplied = true;
            mode = AvatarFlightMode.FORWARD_FLIGHT;
        }
        if (!explicitAirbrakeIntent && boostActive) {
            double boostAcceleration = config.getBoost().getForwardImpulse() / Math.max(0.001, boostDurationSeconds);
            targetForwardSpeed = approach(
                    Math.max(targetForwardSpeed, currentForwardSpeed),
                    boostedHorizontalCap,
                    boostAcceleration * dt
            );
            mode = AvatarFlightMode.FORWARD_FLIGHT;
        }

        if (explicitAirbrakeIntent) {
            vertical = approach(vertical, 0.0, movement.getAirbrakeDeceleration() * dt);
            mode = AvatarFlightMode.BRAKING;
        } else if (descendIntent) {
            vertical = -movement.getDescendSpeed();
            mode = AvatarFlightMode.DESCENDING;
        } else if (!boostApplied && targetForwardSpeed > MIN_FORWARD_FOR_PITCH_TRADE && !neutralPitch) {
            PitchAdjustment pitch = applyPitch(effectivePitchRadians, targetForwardSpeed, vertical,
                    config, boostActive ? boostedHorizontalCap : glideHorizontalCap, diveLoad, climbLoad, dt);
            targetForwardSpeed = pitch.forwardSpeed();
            vertical = pitch.verticalSpeed();
        } else if (mode == AvatarFlightMode.FORWARD_FLIGHT) {
            if (neutralPitch && !jumpApplied) {
                double plannedHorizontalSpeed = Math.hypot(targetForwardSpeed, targetStrafeSpeed);
                vertical = approach(vertical, -glideSinkSpeed(movement, plannedHorizontalSpeed),
                        movement.getGlideSinkAcceleration() * dt);
            }
        } else if (!jumpApplied) {
            vertical = approach(vertical, 0.0, movement.getHoverVerticalDamping() * dt);
        }
        if (!boostActive && targetForwardSpeed > glideHorizontalCap) {
            targetForwardSpeed = AvatarFlightManeuverMath.decayBoostedExcess(targetForwardSpeed, config, dt);
        }

        vertical = Math.max(-movement.getMaxFallSpeed(), Math.min(movement.getMaxFallSpeed(), vertical));
        double x = forwardX * targetForwardSpeed + rightX * targetStrafeSpeed;
        double z = forwardZ * targetForwardSpeed + rightZ * targetStrafeSpeed;
        double horizontalSpeedLimit = resolveHorizontalSpeedLimit(
                currentHorizontalSpeed,
                targetForwardSpeed,
                targetStrafeSpeed,
                movement,
                glideHorizontalCap,
                boostedHorizontalCap,
                boostActive
        );
        HorizontalVelocity horizontalVelocity = capHorizontalVelocity(x, z, horizontalSpeedLimit);
        x = horizontalVelocity.x();
        z = horizontalVelocity.z();
        boolean applyVelocity = mode != AvatarFlightMode.GROUNDED;
        double horizontalSpeed = horizontalVelocity.speed();
        boolean horizontalIdle = horizontalSpeed < HORIZONTAL_IDLE_SPEED;
        boolean fastFlight = !horizontalIdle
                && mode == AvatarFlightMode.FORWARD_FLIGHT
                && AvatarFlightSpeedMetrics.isFastFlightSpeed(horizontalSpeed, config);
        double visualPitch = input.pitchRadians();
        double visualRoll = resolveVisualRoll(
                state.velocityX(),
                state.velocityZ(),
                x,
                z,
                targetStrafeSpeed,
                movement.getMaxBackwardSpeed()
        );
        double hudTargetSpeedRatio = resolveHudTargetSpeedRatio(
                mode,
                effectivePitchRadians,
                currentHorizontalSpeed,
                targetForwardSpeed,
                boostActive,
                explicitAirbrakeIntent,
                config,
                glideHorizontalCap,
                boostedHorizontalCap
        );
        return new Output(mode, x, vertical, z, nextJumpAtMs, nextBoostAtMs, nextLaunchAtMs, diveLoad, climbLoad,
                applyVelocity, jumpApplied, boostApplied, false, 0.0, horizontalIdle, fastFlight, visualPitch,
                visualRoll, hudTargetSpeedRatio, airbrakeApplied);
    }

    @Nonnull
    private static Output groundedOutput(@Nonnull State state) {
        return new Output(
                AvatarFlightMode.GROUNDED,
                0.0,
                0.0,
                0.0,
                state.nextJumpAtMs(),
                state.nextBoostAtMs(),
                state.nextLaunchAtMs(),
                0.0,
                0.0,
                false,
                false,
                false,
                false,
                0.0,
                true,
                false,
                0.0,
                0.0,
                0.0
        );
    }

    private static double resolveHudTargetSpeedRatio(@Nonnull AvatarFlightMode mode,
                                                     double pitchRadians,
                                                     double currentHorizontalSpeed,
                                                     double targetForwardSpeed,
                                                     boolean boostActive,
                                                     boolean explicitAirbrakeIntent,
                                                     @Nonnull TwAvatarFlightConfig config,
                                                     double glideHorizontalCap,
                                                     double boostedHorizontalCap) {
        double targetSpeed = resolveHudTargetSpeed(
                mode,
                pitchRadians,
                currentHorizontalSpeed,
                targetForwardSpeed,
                boostActive,
                explicitAirbrakeIntent,
                config,
                glideHorizontalCap,
                boostedHorizontalCap
        );
        return AvatarFlightSpeedMetrics.speedRatio(targetSpeed, config);
    }

    private static double resolveHudTargetSpeed(@Nonnull AvatarFlightMode mode,
                                                double pitchRadians,
                                                double currentHorizontalSpeed,
                                                double targetForwardSpeed,
                                                boolean boostActive,
                                                boolean explicitAirbrakeIntent,
                                                @Nonnull TwAvatarFlightConfig config,
                                                double glideHorizontalCap,
                                                double boostedHorizontalCap) {
        if (mode == AvatarFlightMode.GROUNDED || explicitAirbrakeIntent) {
            return 0.0;
        }
        if (boostActive) {
            return boostedHorizontalCap;
        }
        if (pitchRadians < 0.0) {
            double diveAmount = AvatarFlightManeuverMath.pitchPower(
                    pitchRadians,
                    true,
                    config.getCurve().getDivePitchExponent()
            );
            double floor = config.getMovement().getGlideStartKickSpeed();
            double projected = floor + (glideHorizontalCap - floor) * diveAmount;
            return Math.max(Math.max(0.0, targetForwardSpeed), projected);
        }
        if (pitchRadians > 0.0) {
            double physicalPitch = Math.min(MAX_PHYSICAL_PITCH_UP_RADIANS, pitchRadians);
            double projected = Math.max(0.0, currentHorizontalSpeed * Math.cos(physicalPitch));
            return Math.min(Math.max(0.0, targetForwardSpeed), projected);
        }
        return Math.max(0.0, targetForwardSpeed);
    }

    private static double resolveHorizontalSpeedLimit(double currentHorizontalSpeed,
                                                      double targetForwardSpeed,
                                                      double targetStrafeSpeed,
                                                      TwAvatarFlightConfig.MovementSettings movement,
                                                      double glideHorizontalCap,
                                                      double boostedHorizontalCap,
                                                      boolean boostActive) {
        double configuredLimit = Math.max(glideHorizontalCap, movement.getMaxBackwardSpeed());
        double boostLimit = Math.max(configuredLimit, boostedHorizontalCap);
        double intendedSpeed = Math.max(Math.abs(targetForwardSpeed), Math.abs(targetStrafeSpeed));
        if (boostActive) {
            return Math.max(0.0, Math.min(boostLimit, Math.max(currentHorizontalSpeed, intendedSpeed)));
        }
        if (intendedSpeed > configuredLimit) {
            return Math.max(0.0, Math.min(boostLimit, Math.max(currentHorizontalSpeed, intendedSpeed)));
        }
        if (currentHorizontalSpeed > configuredLimit) {
            return Math.max(0.0, Math.min(boostLimit,
                    Math.max(currentHorizontalSpeed, Math.min(intendedSpeed, configuredLimit))));
        }
        return Math.max(0.0, Math.min(boostLimit,
                Math.min(configuredLimit, Math.max(currentHorizontalSpeed, intendedSpeed))));
    }

    @Nonnull
    private static HorizontalVelocity capHorizontalVelocity(double x, double z, double maxSpeed) {
        double speed = Math.hypot(x, z);
        if (speed <= maxSpeed || speed <= 0.0) {
            return new HorizontalVelocity(x, z, speed);
        }
        double scale = maxSpeed / speed;
        return new HorizontalVelocity(x * scale, z * scale, maxSpeed);
    }

    @Nonnull
    private static PitchAdjustment applyPitch(double pitchRadians,
                                              double forwardSpeed,
                                              double verticalSpeed,
                                              @Nonnull TwAvatarFlightConfig config,
                                              double glideHorizontalCap,
                                              double diveLoad,
                                              double climbLoad,
                                              double dt) {
        TwAvatarFlightConfig.MovementSettings movement = config.getMovement();
        if (pitchRadians > 0.0) {
            double amount = AvatarFlightManeuverMath.pitchPower(
                    pitchRadians,
                    false,
                    config.getCurve().getClimbPitchExponent()
            );
            double turnAmount = pitchUpAmount(pitchRadians);
            double load = Math.max(0.65, climbLoad);
            double eligibility = AvatarFlightManeuverMath.climbEligibility(forwardSpeed, config);
            double physicalPitch = Math.min(MAX_PHYSICAL_PITCH_UP_RADIANS, pitchRadians);
            double glideSpeed = Math.max(0.0, forwardSpeed);
            double drag = movement.getPitchUpSpeedCost() * amount * load * dt;
            double effectiveSpeed = Math.max(0.0, glideSpeed - drag);
            double energyForwardSpeed = Math.max(0.0, effectiveSpeed * Math.cos(physicalPitch));
            double retainedForwardSpeed = Math.max(0.0, forwardSpeed - drag * PITCH_UP_FORWARD_DRAG_MULTIPLIER);
            double targetForwardSpeed = Math.min(movement.getMaxForwardSpeed(),
                    Math.max(energyForwardSpeed, retainedForwardSpeed));
            double climbVerticalSpeed = Math.min(movement.getMaxFallSpeed(),
                    effectiveSpeed * Math.sin(physicalPitch));
            double sinkVerticalSpeed = -glideSinkSpeed(movement, forwardSpeed);
            double liftBlend = Math.max(0.65, Math.min(1.0, eligibility * load));
            double targetVerticalSpeed = sinkVerticalSpeed
                    + (climbVerticalSpeed - sinkVerticalSpeed) * liftBlend;
            double turnDelta = movement.getPitchUpLiftScale() * Math.max(1.0, glideSpeed) * turnAmount * dt;
            return new PitchAdjustment(
                    Math.max(0.0, approach(forwardSpeed, targetForwardSpeed, turnDelta)),
                    approach(verticalSpeed, targetVerticalSpeed, turnDelta)
            );
        }
        if (pitchRadians < 0.0) {
            double amount = AvatarFlightManeuverMath.pitchPower(
                    pitchRadians,
                    true,
                    config.getCurve().getDivePitchExponent()
            );
            double load = Math.max(0.0, diveLoad);
            double gain = movement.getPitchDownSpeedGain() * amount * load * dt;
            double sink = movement.getPitchDownDiveScale() * amount * (0.35 + 0.65 * load) * dt;
            double targetForwardSpeed = Math.min(glideHorizontalCap, forwardSpeed + gain);
            double baselineSinkSpeed = -glideSinkSpeed(movement, targetForwardSpeed);
            double baselineVerticalSpeed = verticalSpeed > baselineSinkSpeed
                    ? approach(verticalSpeed, baselineSinkSpeed, movement.getGlideSinkAcceleration() * dt)
                    : verticalSpeed;
            double diveVerticalSpeed = verticalSpeed - sink;
            return new PitchAdjustment(
                    targetForwardSpeed,
                    Math.min(baselineVerticalSpeed, diveVerticalSpeed)
            );
        }
        return new PitchAdjustment(forwardSpeed, verticalSpeed);
    }

    private static boolean isNeutralPitch(double pitchRadians) {
        if (pitchRadians < 0.0) {
            return Math.abs(pitchRadians) < MIN_DIVE_PITCH_RADIANS;
        }
        return Math.abs(pitchRadians) < NEUTRAL_PITCH_RADIANS;
    }

    private static double pitchUpAmount(double pitchRadians) {
        if (pitchRadians <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, pitchRadians / Math.toRadians(70.0));
    }

    private static double neutralForwardFlightSpeed(@Nonnull TwAvatarFlightConfig.MovementSettings movement,
                                                    double forwardAxis,
                                                    double currentForwardSpeed,
                                                    double dt) {
        double neutralSpeed = movement.getGlideStartKickSpeed() * clamp(forwardAxis, 0.0, 1.0);
        double current = Math.max(0.0, currentForwardSpeed);
        double rate = current <= neutralSpeed
                ? movement.getNeutralGlideAcceleration()
                : movement.getNeutralGlideDeceleration();
        return forwardStartSpeed(movement, approach(current, neutralSpeed, rate * dt), forwardAxis);
    }

    private static double forwardStartSpeed(@Nonnull TwAvatarFlightConfig.MovementSettings movement,
                                            double currentForwardSpeed,
                                            double forwardAxis) {
        double startSpeed = movement.getGlideStartKickSpeed() * clamp(forwardAxis, 0.0, 1.0);
        return Math.max(Math.max(0.0, currentForwardSpeed), startSpeed);
    }

    private static double glideSinkSpeed(@Nonnull TwAvatarFlightConfig.MovementSettings movement,
                                         double horizontalSpeed) {
        double stallThreshold = movement.getStallSpeedThreshold();
        if (stallThreshold <= 0.0) {
            return movement.getGlideSinkSpeed();
        }
        double speedRatio = clamp(horizontalSpeed / stallThreshold, 0.0, 1.0);
        double stallWeight = 1.0 - speedRatio;
        return movement.getGlideSinkSpeed()
                + (movement.getStallSinkSpeed() - movement.getGlideSinkSpeed()) * stallWeight;
    }

    private static boolean isBoostActive(long nextBoostAtMs, long cooldownMs, long durationMs, long nowMs) {
        if (nextBoostAtMs == 0L || cooldownMs <= 0L || durationMs <= 0L || nowMs >= nextBoostAtMs) {
            return false;
        }
        long boostStartedAtMs = nextBoostAtMs - cooldownMs;
        return nowMs - boostStartedAtMs <= durationMs;
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
                                            double maxStrafeSpeed) {
        double strafeRoll = resolveStrafeRoll(strafeSpeed, maxStrafeSpeed);
        double turnRoll = resolveTurnRoll(previousX, previousZ, nextX, nextZ);
        return Math.abs(turnRoll) > Math.abs(strafeRoll) ? turnRoll : strafeRoll;
    }

    private static double resolveStrafeRoll(double strafeSpeed, double maxStrafeSpeed) {
        if (maxStrafeSpeed <= 0.0) {
            return 0.0;
        }
        double amount = clamp(strafeSpeed / maxStrafeSpeed * STRAFE_ROLL_GAIN, -1.0, 1.0);
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
        signedTurn = clamp(signedTurn * TURN_ROLL_GAIN, -1.0, 1.0);
        return -signedTurn * MAX_VISUAL_ROLL_RADIANS;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PitchAdjustment(double forwardSpeed, double verticalSpeed) {
    }

    private record HorizontalVelocity(double x, double z, double speed) {
    }

    public record State(double velocityX,
                        double velocityY,
                        double velocityZ,
                        long nextJumpAtMs,
                        long nextBoostAtMs,
                        double diveLoad,
                        double climbLoad,
                        long nextLaunchAtMs,
                        @Nonnull AvatarFlightMode mode) {
        public State(double velocityX,
                     double velocityY,
                     double velocityZ,
                     long nextJumpAtMs,
                     long nextBoostAtMs) {
            this(velocityX, velocityY, velocityZ, nextJumpAtMs, nextBoostAtMs, 0.0, 0.0, 0L,
                    AvatarFlightMode.FORWARD_FLIGHT);
        }

        public State(double velocityX,
                     double velocityY,
                     double velocityZ,
                     long nextJumpAtMs,
                     long nextBoostAtMs,
                     double diveLoad,
                     double climbLoad,
                     long nextLaunchAtMs) {
            this(velocityX, velocityY, velocityZ, nextJumpAtMs, nextBoostAtMs, diveLoad, climbLoad, nextLaunchAtMs,
                    AvatarFlightMode.FORWARD_FLIGHT);
        }

        @Nonnull
        public static State from(@Nonnull AvatarFlightComponent component) {
            return new State(
                    component.getVelocityX(),
                    component.getVelocityY(),
                    component.getVelocityZ(),
                    component.getNextJumpAtMs(),
                    component.getNextBoostAtMs(),
                    component.getDiveLoad(),
                    component.getClimbLoad(),
                    component.getNextLaunchAtMs(),
                    component.getMode()
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
                        double pitchRadians,
                        boolean flapAllowed,
                        boolean boostAllowed,
                        boolean launchAllowed,
                        long launchHoldMs,
                        boolean airbrakeActivated,
                        boolean inFluid) {
        public Input(double forwardAxis,
                     double strafeAxis,
                     double verticalAxis,
                     boolean jump,
                     boolean crouch,
                     boolean sprint,
                     boolean airbrake,
                     boolean onGround,
                     double yawRadians,
                     double pitchRadians,
                     boolean flapAllowed,
                     boolean boostAllowed,
                     boolean launchAllowed,
                     long launchHoldMs,
                     boolean airbrakeActivated) {
            this(forwardAxis, strafeAxis, verticalAxis, jump, crouch, sprint, airbrake, onGround, yawRadians,
                    pitchRadians, flapAllowed, boostAllowed, launchAllowed, launchHoldMs, airbrakeActivated, false);
        }

        public Input(double forwardAxis,
                     double strafeAxis,
                     double verticalAxis,
                     boolean jump,
                     boolean crouch,
                     boolean sprint,
                     boolean airbrake,
                     boolean onGround,
                     double yawRadians,
                     double pitchRadians,
                     boolean flapAllowed,
                     boolean boostAllowed,
                     boolean launchAllowed,
                     long launchHoldMs) {
            this(forwardAxis, strafeAxis, verticalAxis, jump, crouch, sprint, airbrake, onGround, yawRadians,
                    pitchRadians, flapAllowed, boostAllowed, launchAllowed, launchHoldMs, false, false);
        }

        public Input(double forwardAxis,
                     double strafeAxis,
                     double verticalAxis,
                     boolean jump,
                     boolean crouch,
                     boolean sprint,
                     boolean airbrake,
                     boolean onGround,
                     double yawRadians,
                     double pitchRadians,
                     boolean flapAllowed,
                     boolean boostAllowed) {
            this(forwardAxis, strafeAxis, verticalAxis, jump, crouch, sprint, airbrake, onGround, yawRadians,
                    pitchRadians, flapAllowed, boostAllowed, true, 0L, false, false);
        }
    }

    public record Output(@Nonnull AvatarFlightMode mode,
                         double velocityX,
                         double velocityY,
                         double velocityZ,
                         long nextJumpAtMs,
                         long nextBoostAtMs,
                         long nextLaunchAtMs,
                         double diveLoad,
                         double climbLoad,
                         boolean applyVelocity,
                         boolean jumpApplied,
                         boolean boostApplied,
                         boolean launchApplied,
                         double launchCost,
                         boolean horizontalIdle,
                         boolean fastFlight,
                         double visualPitchRadians,
                         double visualRollRadians,
                         double hudTargetSpeedRatio,
                         boolean airbrakeApplied) {
        public Output {
            hudTargetSpeedRatio = clamp(hudTargetSpeedRatio, 0.0, 1.0);
        }

        public Output(@Nonnull AvatarFlightMode mode,
                      double velocityX,
                      double velocityY,
                      double velocityZ,
                      long nextJumpAtMs,
                      long nextBoostAtMs,
                      long nextLaunchAtMs,
                      double diveLoad,
                      double climbLoad,
                      boolean applyVelocity,
                      boolean jumpApplied,
                      boolean boostApplied,
                      boolean launchApplied,
                      double launchCost,
                      boolean horizontalIdle,
                      boolean fastFlight,
                      double visualPitchRadians,
                      double visualRollRadians,
                      double hudTargetSpeedRatio) {
            this(mode, velocityX, velocityY, velocityZ, nextJumpAtMs, nextBoostAtMs, nextLaunchAtMs, diveLoad,
                    climbLoad, applyVelocity, jumpApplied, boostApplied, launchApplied, launchCost, horizontalIdle,
                    fastFlight, visualPitchRadians, visualRollRadians, hudTargetSpeedRatio, false);
        }

        public Output(@Nonnull AvatarFlightMode mode,
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
            this(mode, velocityX, velocityY, velocityZ, nextJumpAtMs, nextBoostAtMs, 0L, 0.0, 0.0, applyVelocity,
                    jumpApplied, boostApplied, false, 0.0, horizontalIdle, fastFlight, visualPitchRadians,
                    visualRollRadians, 0.0, false);
        }
    }
}
