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
 * Writes marked NPC targets through the active Update 5 or Update 6 Role contract.
 */
public final class NpcMarkedTargetAccess {
    private static final MethodHandle LEGACY_SET_MARKED_TARGET = bindLegacySetter();

    private NpcMarkedTargetAccess() {
    }

    public static void set(@Nonnull Role role,
                           @Nonnull Ref<EntityStore> npcRef,
                           @Nonnull ComponentAccessor<EntityStore> accessor,
                           @Nonnull String slot,
                           @Nullable Ref<EntityStore> targetRef) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            role.setMarkedTarget(npcRef, accessor, slot, targetRef);
            return;
        }
        if (LEGACY_SET_MARKED_TARGET == null) {
            throw new IllegalStateException("Missing Update 5 Role.setMarkedTarget accessor");
        }
        try {
            LEGACY_SET_MARKED_TARGET.invoke(role, slot, targetRef);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not write an Update 5 marked target", throwable);
        }
    }

    @Nullable
    private static MethodHandle bindLegacySetter() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    Role.class,
                    "setMarkedTarget",
                    MethodType.methodType(void.class, String.class, Ref.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
