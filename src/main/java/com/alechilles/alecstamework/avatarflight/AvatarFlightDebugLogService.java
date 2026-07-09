package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Emits throttled avatar-flight controller diagnostics for runtime animation and input investigations.
 */
public final class AvatarFlightDebugLogService {
    private long nextControllerLogAtMs;

    public void maybeLogControllerTick(@Nonnull TwAvatarFlightConfig config,
                                       @Nonnull AvatarFlightComponent flight,
                                       @Nonnull Ref<EntityStore> ref,
                                       @Nonnull AvatarFlightController.Input input,
                                       @Nonnull AvatarFlightController.Output output,
                                       @Nullable AvatarFlightInputComponent rawInput,
                                       @Nullable MovementStates states,
                                       boolean applyingVelocity,
                                       boolean hasFlightVisualOverrides,
                                       boolean suppressingOverlays) {
        if (!config.getDebug().isLogControllerTicks()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextControllerLogAtMs) {
            return;
        }
        nextControllerLogAtMs = now + TimeUnit.SECONDS.toMillis(1L);
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(formatControllerTick(
                config,
                flight,
                ref,
                input,
                output,
                rawInput,
                states,
                applyingVelocity,
                hasFlightVisualOverrides,
                suppressingOverlays,
                now
        ));
    }

    @Nonnull
    private static String formatControllerTick(@Nonnull TwAvatarFlightConfig config,
                                               @Nonnull AvatarFlightComponent flight,
                                               @Nonnull Ref<EntityStore> ref,
                                               @Nonnull AvatarFlightController.Input input,
                                               @Nonnull AvatarFlightController.Output output,
                                               @Nullable AvatarFlightInputComponent rawInput,
                                               @Nullable MovementStates states,
                                               boolean applyingVelocity,
                                               boolean hasFlightVisualOverrides,
                                               boolean suppressingOverlays,
                                               long now) {
        return String.format(
                "TameworkAvatarFlight debug: ref=%s mode=%s input=%.2f/%.2f/%.2f jump=%s crouch=%s sprint=%s airbrake=%s onGround=%s"
                        + " output=%.2f/%.2f/%.2f apply=%s applyingVelocity=%s jumpApplied=%s boostApplied=%s launchHold=%d launchApplied=%s"
                        + " loads=%.2f/%.2f animIdle=%s animFast=%s visual=%.1f/%.1f visualOverride=%s suppressOverlays=%s"
                        + " clientFly=%s movementAnim=%s poseAnim=%s/%s nextSuppressAt=%s"
                        + " rawInput=sprint=%s ground=%s forward=%.2f strafe=%.2f rawStale=%s rawAgeMs=%s"
                        + " vigour=%.2f/%d recharge=%s speedRatio=%.2f states=%s",
                ref,
                output.mode(),
                input.forwardAxis(),
                input.strafeAxis(),
                input.verticalAxis(),
                input.jump(),
                input.crouch(),
                input.sprint(),
                input.airbrake(),
                input.onGround(),
                output.velocityX(),
                output.velocityY(),
                output.velocityZ(),
                output.applyVelocity(),
                applyingVelocity,
                output.jumpApplied(),
                output.boostApplied(),
                input.launchHoldMs(),
                output.launchApplied(),
                output.diveLoad(),
                output.climbLoad(),
                output.horizontalIdle(),
                output.fastFlight(),
                Math.toDegrees(output.visualPitchRadians()),
                Math.toDegrees(output.visualRollRadians()),
                hasFlightVisualOverrides,
                suppressingOverlays,
                flight.isClientFlyingSynced(),
                emptyAsNone(flight.getMovementAnimationId()),
                emptyAsNone(flight.getPitchPoseAnimationId()),
                emptyAsNone(flight.getRollPoseAnimationId()),
                flight.getNextSuppressedAnimationAtMs(),
                rawInput != null && rawInput.isSprinting(),
                rawInput != null && rawInput.isOnGround(),
                rawInput == null ? 0.0 : rawInput.getForwardAxis(),
                rawInput == null ? 0.0 : rawInput.getStrafeAxis(),
                rawInput == null || rawInput.isStale(now, Math.round(config.getInput().getIntentTimeoutMs())),
                rawAgeMs(rawInput, now),
                flight.getVigourCharges(),
                (int) Math.round(config.getVigour().getMaxCharges()),
                flight.getVigourRechargeMode(),
                AvatarFlightSpeedMetrics.speedRatio(
                        AvatarFlightSpeedMetrics.horizontalSpeed(
                                output.velocityX(),
                                output.velocityY(),
                                output.velocityZ()
                        ),
                        config
                ),
                formatMovementStates(states)
        );
    }

    @Nonnull
    private static String rawAgeMs(@Nullable AvatarFlightInputComponent rawInput, long now) {
        if (rawInput == null || rawInput.getLastInputAtMs() == 0L) {
            return "<none>";
        }
        return Long.toString(Math.max(0L, now - rawInput.getLastInputAtMs()));
    }

    @Nonnull
    private static String emptyAsNone(@Nullable String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    @Nonnull
    private static String formatMovementStates(@Nullable MovementStates states) {
        if (states == null) {
            return "<none>";
        }
        return String.format(
                "fly=%s ground=%s idle=%s hIdle=%s sprint=%s run=%s walk=%s jump=%s crouch=%s fall=%s far=%s",
                states.flying,
                states.onGround,
                states.idle,
                states.horizontalIdle,
                states.sprinting,
                states.running,
                states.walking,
                states.jumping,
                states.crouching,
                states.falling,
                states.fallingFar
        );
    }
}
