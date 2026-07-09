package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightVfxSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Converts launch charge/release state into bounded world-space particle emissions.
 */
public final class AvatarFlightLaunchVfxService {

    @FunctionalInterface
    interface EmissionSink {
        boolean emit(String systemId, double x, double y, double z, float yaw, float scale,
                     float maxDurationSeconds, ComponentAccessor<EntityStore> componentAccessor);
    }

    private final EmissionSink emissionSink;

    public AvatarFlightLaunchVfxService() {
        this(new AvatarFlightParticleEmitter()::emit);
    }

    AvatarFlightLaunchVfxService(@Nonnull EmissionSink emissionSink) {
        this.emissionSink = emissionSink;
    }

    public void tick(@Nonnull AvatarFlightComponent flight,
                     @Nullable AvatarFlightInputComponent input,
                     @Nonnull AvatarFlightController.Input controllerInput,
                     @Nonnull AvatarFlightController.Output output,
                     @Nonnull TwAvatarFlightConfig config,
                     @Nullable TransformComponent transform,
                     long nowMs,
                     @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        AvatarFlightVfxSettings vfx = config.getVfx();
        if (!config.getLaunch().isEnabled() || !vfx.isEnabled()) {
            flight.clearLaunchVfxState();
            return;
        }

        long releasedHoldMs = controllerInput.launchHoldMs();
        if (releasedHoldMs > 0L) {
            emitRelease(flight, output, config, vfx, transform, controllerInput.yawRadians(),
                    releasedHoldMs, componentAccessor);
            flight.clearLaunchVfxState();
            return;
        }

        if (input == null || !input.isLaunchCharging()) {
            flight.clearLaunchVfxState();
            return;
        }
        if (!input.isOnGround() || transform == null || transform.getPosition() == null) {
            return;
        }

        Vector3d position = transform.getPosition();
        flight.captureLaunchVfxOrigin(
                position.x,
                position.y + vfx.getGroundOffsetY(),
                position.z,
                controllerInput.yawRadians()
        );
        if (!vfx.isLaunchChargeEnabled() || !isPulseDue(flight, nowMs)) return;

        long heldMs = Math.max(0L, nowMs - input.getLaunchChargeStartedAtMs());
        double progress = AvatarFlightLaunchVfxMath.chargeProgress(heldMs, config.getLaunch().getMaxChargeMs());
        emissionSink.emit(
                vfx.getLaunchChargeParticleSystem(),
                flight.getLaunchVfxOriginX(),
                flight.getLaunchVfxOriginY(),
                flight.getLaunchVfxOriginZ(),
                (float) flight.getLaunchVfxYawRadians(),
                (float) AvatarFlightLaunchVfxMath.pulseScale(vfx, progress),
                vfx.getMaxDurationSeconds(),
                componentAccessor
        );
        flight.setNextLaunchChargeVfxAtMs(
                nowMs + AvatarFlightLaunchVfxMath.pulseIntervalMs(vfx, progress)
        );
    }

    private void emitRelease(@Nonnull AvatarFlightComponent flight,
                             @Nonnull AvatarFlightController.Output output,
                             @Nonnull TwAvatarFlightConfig config,
                             @Nonnull AvatarFlightVfxSettings vfx,
                             @Nullable TransformComponent transform,
                             double yawRadians,
                             long holdMs,
                             @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        if (!flight.isLaunchVfxOriginValid() && !captureFallbackOrigin(flight, transform, vfx, yawRadians)) return;
        if (!output.launchApplied()) {
            if (vfx.isLaunchCancelEnabled()) {
                emit(flight, vfx.getLaunchCancelParticleSystem(), vfx.getLaunchCancelScale(), vfx, componentAccessor);
            }
            return;
        }
        if (!vfx.isLaunchReleaseEnabled()) return;
        AvatarFlightLaunchVfxMath.ReleaseTier tier =
                AvatarFlightLaunchVfxMath.releaseTier(config.getLaunch(), vfx, holdMs);
        switch (tier) {
            case PARTIAL -> emit(flight, vfx.getLaunchReleasePartialParticleSystem(),
                    vfx.getLaunchReleasePartialScale(), vfx, componentAccessor);
            case MID -> emit(flight, vfx.getLaunchReleaseMidParticleSystem(),
                    vfx.getLaunchReleaseMidScale(), vfx, componentAccessor);
            case FULL -> emit(flight, vfx.getLaunchReleaseFullParticleSystem(),
                    vfx.getLaunchReleaseFullScale(), vfx, componentAccessor);
        }
    }

    private static boolean captureFallbackOrigin(@Nonnull AvatarFlightComponent flight,
                                                 @Nullable TransformComponent transform,
                                                 @Nonnull AvatarFlightVfxSettings vfx,
                                                 double yawRadians) {
        if (transform == null || transform.getPosition() == null) return false;
        Vector3d position = transform.getPosition();
        flight.captureLaunchVfxOrigin(
                position.x,
                position.y + vfx.getGroundOffsetY(),
                position.z,
                yawRadians
        );
        return true;
    }

    private void emit(@Nonnull AvatarFlightComponent flight,
                      @Nonnull String systemId,
                      double scale,
                      @Nonnull AvatarFlightVfxSettings vfx,
                      @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        emissionSink.emit(
                systemId,
                flight.getLaunchVfxOriginX(),
                flight.getLaunchVfxOriginY(),
                flight.getLaunchVfxOriginZ(),
                (float) flight.getLaunchVfxYawRadians(),
                (float) scale,
                vfx.getMaxDurationSeconds(),
                componentAccessor
        );
    }

    private static boolean isPulseDue(@Nonnull AvatarFlightComponent flight, long nowMs) {
        return flight.getNextLaunchChargeVfxAtMs() == 0L || nowMs >= flight.getNextLaunchChargeVfxAtMs();
    }
}
