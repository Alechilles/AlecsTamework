package com.alechilles.alecstamework.npc.compat;

import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nullable;

/**
 * Resolves the role root builder across the Update 5 and Update 6 BuilderSupport APIs.
 */
public final class NpcBuilderAccess {
    private static final MethodHandle LEGACY_GET_PARENT_SPAWNABLE = bindLegacyGetter();

    private NpcBuilderAccess() {
    }

    @Nullable
    public static Builder<?> getRoleRoot(@Nullable BuilderSupport support) {
        if (support == null) {
            return null;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return support.getRootBuilder();
        }
        if (LEGACY_GET_PARENT_SPAWNABLE == null) {
            throw new IllegalStateException("Missing Update 5 BuilderSupport.getParentSpawnable accessor");
        }
        try {
            return (Builder<?>) LEGACY_GET_PARENT_SPAWNABLE.invoke(support);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not resolve the Update 5 role builder", throwable);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyGetter() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    BuilderSupport.class,
                    "getParentSpawnable",
                    MethodType.methodType(Builder.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
