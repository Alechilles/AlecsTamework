package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelParticle;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies model-particle options that are not present in every supported Hytale version. */
public final class HytaleModelParticleAccess {
    private static final String CANCEL_PARTICLES_PACKET =
            "com.hypixel.hytale.protocol.packets.world.CancelParticleSystems";
    private static final MethodHandle SET_CLEAR_PARTICLES_ON_REMOVE = bindClearParticlesOnRemove();
    private static final MethodHandle CREATE_CANCEL_PACKET = bindCancelPacket();

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

    /** Builds an Update 6 cancellation packet, or returns null when Update 5 has no such packet. */
    @Nullable
    public static ToClientPacket createCancelPacket(@Nonnull String particleSystemId) {
        if (CREATE_CANCEL_PACKET == null) {
            return null;
        }
        try {
            return (ToClientPacket) CREATE_CANCEL_PACKET.invokeExact(
                    new String[]{particleSystemId}
            );
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not create a model-particle cancellation packet", throwable);
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

    private static MethodHandle bindCancelPacket() {
        if (!HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            Class<?> packetType = Class.forName(
                    CANCEL_PARTICLES_PACKET,
                    false,
                    HytaleModelParticleAccess.class.getClassLoader()
            );
            MethodHandle constructor = MethodHandles.publicLookup()
                    .unreflectConstructor(findCancelConstructor(packetType));
            constructor = MethodHandles.insertArguments(
                    constructor,
                    0,
                    (Object) null,
                    (Object) null
            );
            constructor = MethodHandles.insertArguments(constructor, 1, true);
            return constructor.asType(MethodType.methodType(ToClientPacket.class, String[].class));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Constructor<?> findCancelConstructor(@Nonnull Class<?> packetType)
            throws NoSuchMethodException {
        for (Constructor<?> constructor : packetType.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 4
                    && parameters[2] == String[].class
                    && parameters[3] == boolean.class) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(CANCEL_PARTICLES_PACKET);
    }
}
