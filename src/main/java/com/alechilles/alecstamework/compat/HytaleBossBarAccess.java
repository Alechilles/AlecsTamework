package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.builtin.encountermanager.EncounterBossBarState;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Encounter boss bar calls that work on both Update 6 and Update 7. Update 7 added a hard-mode
 * flag to {@code setTracked} and the encounter ref to {@code revertPlayer}.
 */
public final class HytaleBossBarAccess {
    private static final boolean UPDATE_7;
    private static final MethodHandle SET_TRACKED;
    private static final MethodHandle REVERT_PLAYER;

    static {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle setTracked;
        MethodHandle revertPlayer;
        boolean update7;
        try {
            setTracked = lookup.findVirtual(EncounterBossBarState.class, "setTracked",
                    MethodType.methodType(void.class, Ref.class, UUID.class, String.class, boolean.class));
            revertPlayer = lookup.findVirtual(EncounterBossBarState.class, "revertPlayer",
                    MethodType.methodType(void.class, ComponentAccessor.class, Ref.class, Ref.class));
            update7 = true;
        } catch (ReflectiveOperationException update6) {
            try {
                setTracked = lookup.findVirtual(EncounterBossBarState.class, "setTracked",
                        MethodType.methodType(void.class, Ref.class, UUID.class, String.class));
                revertPlayer = lookup.findVirtual(EncounterBossBarState.class, "revertPlayer",
                        MethodType.methodType(void.class, ComponentAccessor.class, Ref.class));
                update7 = false;
            } catch (ReflectiveOperationException missing) {
                throw new ExceptionInInitializerError(missing);
            }
        }
        SET_TRACKED = setTracked;
        REVERT_PLAYER = revertPlayer;
        UPDATE_7 = update7;
    }

    private HytaleBossBarAccess() {
    }

    public static void setTracked(@Nonnull EncounterBossBarState bar, @Nonnull Ref<EntityStore> target,
                                  @Nonnull UUID targetUuid, @Nullable String nameKey) {
        try {
            if (UPDATE_7) {
                SET_TRACKED.invoke(bar, target, targetUuid, nameKey, false);
            } else {
                SET_TRACKED.invoke(bar, target, targetUuid, nameKey);
            }
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static void revertPlayer(@Nonnull EncounterBossBarState bar,
                                    @Nonnull ComponentAccessor<EntityStore> accessor,
                                    @Nonnull Ref<EntityStore> encounter, @Nonnull Ref<EntityStore> player) {
        try {
            if (UPDATE_7) {
                REVERT_PLAYER.invoke(bar, accessor, encounter, player);
            } else {
                REVERT_PLAYER.invoke(bar, accessor, player);
            }
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
