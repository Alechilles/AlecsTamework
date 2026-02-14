package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.Locale;

// Centralizes alarm lookups and time resolution for interaction checks.
/** Uses game time when available (WorldTimeResource), falling back to wall-clock. */
final class InteractionAlarmHelper {
    private final ActionTameworkInteract owner;

    InteractionAlarmHelper(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Returns true when the alarm is unset or has passed.
    boolean isAlarmReady(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        if (alarmName == null || alarmName.isBlank()) {
            return false;
        }
        Alarm alarm = resolveAlarm(npcRef, store, alarmName);
        if (alarm == null) {
            return true;
        }
        if (!alarm.isSet()) {
            return true;
        }
        return alarm.hasPassed(resolveGameTime(store));
    }

    // Checks whether the alarm matches the required state string.
    boolean matchesAlarmState(Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              String alarmName,
                              String state) {
        if (alarmName == null || alarmName.isBlank()) {
            return false;
        }
        String normalized = state != null ? state.trim().toLowerCase(Locale.ROOT) : "";
        Alarm alarm = resolveAlarm(npcRef, store, alarmName);
        if (alarm == null) {
            return "unset".equals(normalized);
        }
        Instant now = resolveGameTime(store);
        switch (normalized) {
            case "unset":
                return !alarm.isSet();
            case "passed":
                return alarm.isSet() && alarm.hasPassed(now);
            case "active":
                return alarm.isSet() && !alarm.hasPassed(now);
            default:
                return false;
        }
    }

    // Resolves the alarm instance for an NPC and alarm name.
    private Alarm resolveAlarm(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        NPCEntity npc = owner.resolveNpcEntity(npcRef, store);
        if (npc == null) {
            return null;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return null;
        }
        return alarmStore.get(npc, alarmName);
    }

    // Uses world time if available; otherwise falls back to wall-clock.
    private Instant resolveGameTime(Store<EntityStore> store) {
        if (store == null) {
            return Instant.now();
        }
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        if (time == null) {
            return Instant.now();
        }
        return time.getGameTime();
    }
}
