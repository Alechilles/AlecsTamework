package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.AvatarFlightVfxSettings;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Emits short world-space particle bursts for successful avatar-flight boosts. */
public final class AvatarFlightBoostVfxService {

    @FunctionalInterface
    interface EmissionSink {
        boolean emit(String systemId, double x, double y, double z, float yaw, float scale,
                     float maxDurationSeconds, Ref<EntityStore> ownerRef,
                     ComponentAccessor<EntityStore> componentAccessor);
    }

    private final EmissionSink emissionSink;

    public AvatarFlightBoostVfxService() {
        this(new AvatarFlightParticleEmitter()::emit);
    }

    AvatarFlightBoostVfxService(@Nonnull EmissionSink emissionSink) {
        this.emissionSink = emissionSink;
    }

    public void emitApplied(@Nonnull AvatarFlightController.Output output,
                            @Nonnull TwAvatarFlightConfig config,
                            @Nullable TransformComponent transform,
                            double yawRadians,
                            @Nullable Ref<EntityStore> ownerRef,
                            @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        AvatarFlightVfxSettings vfx = config.getVfx();
        if (!vfx.isEnabled() || transform == null || transform.getPosition() == null) return;

        Vector3d position = transform.getPosition();
        if (output.jumpApplied() && vfx.isUpwardBoostEnabled()) {
            emit(config, vfx.getUpwardBoostParticleSystem(), position, yawRadians,
                    vfx.getUpwardBoostScale(), vfx, ownerRef, componentAccessor);
        }
        if (output.boostApplied() && vfx.isForwardBoostEnabled()) {
            emit(config, vfx.getForwardBoostParticleSystem(), position, yawRadians,
                    vfx.getForwardBoostScale(), vfx, ownerRef, componentAccessor);
        }
    }

    private void emit(@Nonnull TwAvatarFlightConfig config,
                      @Nonnull String systemId,
                      @Nonnull Vector3d position,
                      double yawRadians,
                      double scale,
                      @Nonnull AvatarFlightVfxSettings vfx,
                      @Nullable Ref<EntityStore> ownerRef,
                      @Nonnull ComponentAccessor<EntityStore> componentAccessor) {
        boolean emitted = emissionSink.emit(
                systemId,
                position.x,
                position.y,
                position.z,
                (float) yawRadians,
                (float) scale,
                vfx.getMaxDurationSeconds(),
                ownerRef,
                componentAccessor
        );
        logEmission(config, systemId, position, emitted, scale);
    }

    private static void logEmission(@Nonnull TwAvatarFlightConfig config,
                                    @Nonnull String systemId,
                                    @Nonnull Vector3d position,
                                    boolean emitted,
                                    double scale) {
        if (!config.getDebug().isLogControllerTicks()) return;
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) return;
        plugin.getLogger().at(Level.INFO).log(String.format(
                Locale.ROOT,
                "TameworkAvatarFlight debug: boostVfx config=%s system=%s emitted=%s "
                        + "position=%.2f/%.2f/%.2f scale=%.2f",
                config.getId(),
                systemId,
                emitted,
                position.x,
                position.y,
                position.z,
                scale
        ));
    }
}
