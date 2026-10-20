package com.alechilles.alecstamework.npc.compat;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Null-safe access to an NPC's engine alarm store and its named alarms.
 */
public final class NpcAlarmAccess {
    private NpcAlarmAccess() {
    }

    /**
     * Returns null for a missing or invalid reference and for an entity without an alarm store.
     * Reads the component directly because {@code AlarmStore.get} asserts that it is present.
     */
    @Nullable
    public static AlarmStore getStore(@Nullable Ref<EntityStore> npcRef,
                                      @Nullable ComponentAccessor<EntityStore> accessor) {
        if (npcRef == null || !npcRef.isValid() || accessor == null) {
            return null;
        }
        return accessor.getComponent(npcRef, AlarmStore.getComponentType());
    }

    @Nullable
    public static Alarm resolveAlarm(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable ComponentAccessor<EntityStore> accessor,
                                     @Nonnull String alarmName) {
        AlarmStore alarmStore = getStore(npcRef, accessor);
        return alarmStore == null ? null : alarmStore.get(alarmName);
    }
}
