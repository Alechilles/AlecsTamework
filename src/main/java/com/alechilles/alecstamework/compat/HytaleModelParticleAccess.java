package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;

/** Applies model-particle options that are not present in every supported Hytale version. */
public final class HytaleModelParticleAccess {
    private static final MethodHandle SET_CLEAR_PARTICLES_ON_REMOVE = bindClearParticlesOnRemove();

    private HytaleModelParticleAccess() {
    }

    /** Enables immediate particle cleanup on Update 6 and leaves Update 5 behavior unchanged. */
    public static void enableClearParticlesOnRemove(@Nonnull ModelParticle modelParticle) {
        if (SET_CLEAR_PARTICLES_ON_REMOVE == null) {
            return;
        }
        try {
            SET_CLEAR_PARTICLES_ON_REMOVE.invokeExact(modelParticle, true);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not configure model-particle cleanup", throwable);
        }
    }

    private static MethodHandle bindClearParticlesOnRemove() {
        if (!HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    ModelParticle.class,
                    "setClearParticlesOnRemove",
                    MethodType.methodType(void.class, boolean.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
