package com.alechilles.alecstamework.math;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Shared helpers for Hytale rotation and vector contracts that vary by patch line. */
public final class TameworkRotationUtil {
    private static final LookAtBinding LOOK_AT = bindLookAt();

    private TameworkRotationUtil() {
    }

    public static Vector3d directionFrom(@Nullable Rotation3f rotation) {
        if (rotation == null) {
            return Transform.getDirection(0.0F, 0.0F);
        }
        return Transform.getDirection(rotation.pitch(), rotation.yaw());
    }

    public static Rotation3f copyOrDefault(@Nullable Rotation3f rotation) {
        return rotation != null ? new Rotation3f(rotation) : new Rotation3f();
    }

    /** Computes a relative look rotation across the Update 5 and Update 6 method shapes. */
    @Nonnull
    public static Rotation3f lookAt(@Nonnull Vector3d relative) {
        try {
            if (LOOK_AT.acceptsComponents()) {
                return (Rotation3f) LOOK_AT.method().invokeExact(relative.x, relative.y, relative.z);
            }
            return (Rotation3f) LOOK_AT.method().invokeExact(relative);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not compute a Hytale look rotation", throwable);
        }
    }

    @Nonnull
    private static LookAtBinding bindLookAt() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            MethodHandle components = lookup.findStatic(
                    Rotation3f.class,
                    "lookAt",
                    MethodType.methodType(Rotation3f.class, double.class, double.class, double.class)
            );
            return new LookAtBinding(true, components);
        } catch (NoSuchMethodException ignored) {
            return bindUpdate5LookAt(lookup);
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static LookAtBinding bindUpdate5LookAt(@Nonnull MethodHandles.Lookup lookup) {
        try {
            MethodHandle vector = lookup.findStatic(
                    Rotation3f.class,
                    "lookAt",
                    MethodType.methodType(Rotation3f.class, Vector3d.class)
            );
            return new LookAtBinding(false, vector);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record LookAtBinding(boolean acceptsComponents, @Nonnull MethodHandle method) {
    }
}
