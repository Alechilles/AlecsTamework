package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Bridges particle overloads whose vector descriptor changed in Hytale Update 6. */
public final class HytaleParticleAccess {
    private static final MethodHandle BASIC = bind(
            String.class, vectorType(), ComponentAccessor.class);
    private static final MethodHandle TRANSFORMED = bind(
            String.class, vectorType(), float.class, float.class, float.class,
            float.class, float.class, ComponentAccessor.class);
    private static final MethodHandle VIEWERS = bind(
            String.class, vectorType(), List.class, ComponentAccessor.class);
    private static final MethodHandle ATTACHED = bind(
            String.class, vectorType(), Ref.class, List.class, ComponentAccessor.class);
    private static final MethodHandle COLORED = bind(
            String.class, vectorType(), float.class, float.class, float.class,
            float.class, Color.class, List.class, ComponentAccessor.class);

    private HytaleParticleAccess() {
    }

    public static void spawn(@Nonnull String particleSystem,
                             @Nonnull Vector3d position,
                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            BASIC.invoke(particleSystem, position, accessor);
        } catch (Throwable throwable) {
            throw invocationFailure(throwable);
        }
    }

    public static void spawn(@Nonnull String particleSystem,
                             @Nonnull Vector3d position,
                             float yaw,
                             float pitch,
                             float roll,
                             float scale,
                             float maxDuration,
                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            TRANSFORMED.invoke(particleSystem, position, yaw, pitch, roll, scale, maxDuration, accessor);
        } catch (Throwable throwable) {
            throw invocationFailure(throwable);
        }
    }

    public static void spawn(@Nonnull String particleSystem,
                             @Nonnull Vector3d position,
                             @Nonnull List<Ref<EntityStore>> viewers,
                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            VIEWERS.invoke(particleSystem, position, viewers, accessor);
        } catch (Throwable throwable) {
            throw invocationFailure(throwable);
        }
    }

    public static void spawn(@Nonnull String particleSystem,
                             @Nonnull Vector3d position,
                             @Nullable Ref<EntityStore> attachment,
                             @Nonnull List<Ref<EntityStore>> viewers,
                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            ATTACHED.invoke(particleSystem, position, attachment, viewers, accessor);
        } catch (Throwable throwable) {
            throw invocationFailure(throwable);
        }
    }

    public static void spawn(@Nonnull String particleSystem,
                             @Nonnull Vector3d position,
                             float yaw,
                             float pitch,
                             float roll,
                             float scale,
                             @Nullable Color color,
                             @Nonnull List<Ref<EntityStore>> viewers,
                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            COLORED.invoke(particleSystem, position, yaw, pitch, roll, scale, color, viewers, accessor);
        } catch (Throwable throwable) {
            throw invocationFailure(throwable);
        }
    }

    @Nonnull
    private static Class<?> vectorType() {
        return HytaleApiLevel.isUpdate6OrLater() ? Vector3dc.class : Vector3d.class;
    }

    @Nonnull
    private static MethodHandle bind(@Nonnull Class<?>... parameters) {
        try {
            return MethodHandles.publicLookup().findStatic(
                    ParticleUtil.class,
                    "spawnParticleEffect",
                    MethodType.methodType(void.class, parameters)
            );
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static IllegalStateException invocationFailure(@Nonnull Throwable throwable) {
        return new IllegalStateException("Could not emit a Hytale particle effect", throwable);
    }
}
