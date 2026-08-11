package com.alechilles.alecstamework.npc.compat;

import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.storage.ParameterStore;
import com.hypixel.hytale.server.npc.storage.PersistentParameter;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves engine alarm stores and named alarms from their Update 5 or Update 6 owner.
 */
public final class NpcAlarmAccess {
    private static final MethodHandle LEGACY_GET_STORE = bindLegacyStoreGetter();
    private static final MethodHandle LEGACY_GET_ALARM = bindLegacyAlarmGetter();

    private NpcAlarmAccess() {
    }

    @Nullable
    public static AlarmStore getStore(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable ComponentAccessor<EntityStore> accessor) {
        if (npcRef == null || !npcRef.isValid() || accessor == null) {
            return null;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return AlarmStore.get(npcRef, accessor);
        }
        NPCEntity npc = accessor.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        if (LEGACY_GET_STORE == null) {
            throw new IllegalStateException("Missing Update 5 NPCEntity.getAlarmStore accessor");
        }
        try {
            return (AlarmStore) LEGACY_GET_STORE.invoke(npc);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not resolve an Update 5 alarm store", throwable);
        }
    }

    @Nullable
    public static Alarm resolveAlarm(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable ComponentAccessor<EntityStore> accessor,
                                     @Nonnull String alarmName) {
        AlarmStore alarmStore = getStore(npcRef, accessor);
        if (alarmStore == null) {
            return null;
        }
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return alarmStore.get(alarmName);
        }
        NPCEntity npc = accessor.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || LEGACY_GET_ALARM == null) {
            return null;
        }
        try {
            return (Alarm) LEGACY_GET_ALARM.invoke(alarmStore, npc, alarmName);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not resolve an Update 5 alarm", throwable);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyStoreGetter() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    NPCEntity.class,
                    "getAlarmStore",
                    MethodType.methodType(AlarmStore.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nullable
    private static MethodHandle bindLegacyAlarmGetter() {
        if (HytaleApiLevel.isUpdate6OrLater()) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(
                    ParameterStore.class,
                    "get",
                    MethodType.methodType(PersistentParameter.class, Entity.class, String.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
