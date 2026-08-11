package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Creates and reads entity mounts across the Update 5 rotation and Update 6 vector offsets.
 */
public final class HytaleMountedComponentAccess {
    private static final Bindings BINDINGS = bind();

    private HytaleMountedComponentAccess() {
    }

    @Nonnull
    public static MountedComponent createEntityMount(@Nonnull Ref<EntityStore> mountedTo,
                                                     float x,
                                                     float y,
                                                     float z,
                                                     @Nonnull MountController controller) {
        Object offset = BINDINGS.vectorOffset()
                ? new Vector3f(x, y, z)
                : new Rotation3f(x, y, z);
        try {
            return (MountedComponent) BINDINGS.constructor().invokeExact(
                    (Ref<?>) mountedTo, offset, controller);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not create a mounted component", throwable);
        }
    }

    @Nullable
    public static Vector3f attachmentOffset(@Nullable MountedComponent mounted) {
        if (mounted == null) {
            return null;
        }
        try {
            Object offset = BINDINGS.getAttachmentOffset().invokeExact(mounted);
            if (offset instanceof Vector3fc vector) {
                return new Vector3f(vector);
            }
            if (offset instanceof Rotation3f rotation) {
                return new Vector3f(rotation.x(), rotation.y(), rotation.z());
            }
            return null;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not read a mounted attachment offset", throwable);
        }
    }

    @Nonnull
    private static Bindings bind() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            MethodHandle constructor = lookup.findConstructor(
                    MountedComponent.class,
                    MethodType.methodType(void.class, Ref.class, Vector3f.class, MountController.class)
            ).asType(MethodType.methodType(
                    MountedComponent.class, Ref.class, Object.class, MountController.class));
            MethodHandle getOffset = lookup.findVirtual(
                    MountedComponent.class,
                    "getAttachmentOffset",
                    MethodType.methodType(Vector3fc.class)
            ).asType(MethodType.methodType(Object.class, MountedComponent.class));
            return new Bindings(true, constructor, getOffset);
        } catch (NoSuchMethodException ignored) {
            return bindLegacy(lookup);
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static Bindings bindLegacy(@Nonnull MethodHandles.Lookup lookup) {
        try {
            MethodHandle constructor = lookup.findConstructor(
                    MountedComponent.class,
                    MethodType.methodType(void.class, Ref.class, Rotation3f.class, MountController.class)
            ).asType(MethodType.methodType(
                    MountedComponent.class, Ref.class, Object.class, MountController.class));
            MethodHandle getOffset = lookup.findVirtual(
                    MountedComponent.class,
                    "getAttachmentOffset",
                    MethodType.methodType(Rotation3f.class)
            ).asType(MethodType.methodType(Object.class, MountedComponent.class));
            return new Bindings(false, constructor, getOffset);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Bindings(
            boolean vectorOffset,
            @Nonnull MethodHandle constructor,
            @Nonnull MethodHandle getAttachmentOffset) {
    }
}
