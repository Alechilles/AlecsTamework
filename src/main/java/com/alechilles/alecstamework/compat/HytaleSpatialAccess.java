package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.spatial.SpatialStructure;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Bridges spatial queries whose vector descriptor changed in Hytale Update 6. */
public final class HytaleSpatialAccess {
    private static final MethodHandle COLLECT = bindCollect();

    private HytaleSpatialAccess() {
    }

    public static <T> void collect(@Nonnull SpatialStructure<T> structure,
                                   @Nonnull Vector3d center,
                                   double radius,
                                   @Nonnull List<T> results) {
        try {
            COLLECT.invoke(structure, center, radius, results);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not query a Hytale spatial structure", throwable);
        }
    }

    @Nonnull
    private static MethodHandle bindCollect() {
        Class<?> vectorType = HytaleApiLevel.isUpdate6OrLater() ? Vector3dc.class : Vector3d.class;
        try {
            return MethodHandles.publicLookup().findVirtual(
                    SpatialStructure.class,
                    "collect",
                    MethodType.methodType(void.class, vectorType, double.class, List.class)
            );
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
