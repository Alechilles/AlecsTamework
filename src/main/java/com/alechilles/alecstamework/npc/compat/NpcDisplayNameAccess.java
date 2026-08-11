package com.alechilles.alecstamework.npc.compat;

import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.DisplayNameSupport;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies a persistent NPC display name through the active Hytale support class.
 */
public final class NpcDisplayNameAccess {
    private static final MethodHandle LEGACY_SET_DISPLAY_NAME = bindLegacySetter();

    private NpcDisplayNameAccess() {
    }

    public static void set(@Nonnull Ref<EntityStore> npcRef,
                           @Nonnull String displayName,
                           @Nonnull ComponentAccessor<EntityStore> accessor) {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            DisplayNameSupport.setDisplayName(npcRef, displayName, accessor);
            return;
        }
        if (LEGACY_SET_DISPLAY_NAME == null) {
            throw new IllegalStateException("Missing Update 5 EntitySupport.setDisplayName accessor");
        }
        try {
            LEGACY_SET_DISPLAY_NAME.invoke(npcRef, displayName, accessor);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not set an Update 5 NPC display name", throwable);
        }
    }

    @Nullable
    private static MethodHandle bindLegacySetter() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findStatic(
                    EntitySupport.class,
                    "setDisplayName",
                    MethodType.methodType(void.class, Ref.class, String.class, ComponentAccessor.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
