package com.alechilles.alecstamework.npc.compat;

import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adapts Role methods whose required live-entity context changed in Update 6.
 */
public final class NpcRoleAccess {
    private static final MethodHandle LEGACY_COULD_BREATHE_CACHED = bindLegacyCouldBreathe();

    private NpcRoleAccess() {
    }

    public static boolean couldBreatheCached(@Nonnull Role role,
                                             @Nonnull Ref<EntityStore> npcRef,
                                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return role.couldBreatheCached(npcRef, accessor);
        }
        if (LEGACY_COULD_BREATHE_CACHED == null) {
            throw new IllegalStateException("Missing Update 5 Role.couldBreatheCached accessor");
        }
        try {
            return (boolean) LEGACY_COULD_BREATHE_CACHED.invoke(role);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not read Update 5 breathing state", throwable);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyCouldBreathe() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    Role.class,
                    "couldBreatheCached",
                    MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
