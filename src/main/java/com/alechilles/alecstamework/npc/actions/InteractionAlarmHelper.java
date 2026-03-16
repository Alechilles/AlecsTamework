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

    // Returns true when the alarm exists and is unset or has passed.
    boolean isAlarmReady(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        return snapshot(npcRef, store, alarmName).ready;
    }

    // Checks whether the alarm matches the required state string.
    boolean matchesAlarmState(Ref<EntityStore> npcRef,
                              Store<EntityStore> store,
                              String alarmName,
                              String state) {
        String normalized = state != null ? state.trim().toLowerCase(Locale.ROOT) : "";
        AlarmSnapshot snapshot = snapshot(npcRef, store, alarmName);
        switch (normalized) {
            case "unset":
                return snapshot.unset;
            case "passed":
                return snapshot.passed;
            case "active":
                return snapshot.active;
            default:
                return false;
        }
    }

    // Captures the raw alarm state used by prompt diagnostics.
    AlarmSnapshot snapshot(Ref<EntityStore> npcRef, Store<EntityStore> store, String alarmName) {
        if (alarmName == null || alarmName.isBlank()) {
            return AlarmSnapshot.invalidName();
        }
        NPCEntity npc = owner.resolveNpcEntity(npcRef, store);
        if (npc == null) {
            return AlarmSnapshot.missingNpc(alarmName);
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return AlarmSnapshot.missingStore(alarmName);
        }
        Alarm alarm = alarmStore.get(npc, alarmName);
        if (alarm == null) {
            return AlarmSnapshot.missingAlarm(alarmName);
        }
        Instant now = resolveGameTime(store);
        boolean set = alarm.isSet();
        boolean passed = set && now != null && alarm.hasPassed(now);
        boolean unset = !set;
        boolean active = set && (now == null || !passed);
        boolean ready = unset || passed;
        return AlarmSnapshot.present(alarmName, now != null, set, unset, active, passed, ready);
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

    // Uses world time if available; returns null when unavailable.
    private Instant resolveGameTime(Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        if (time == null) {
            return null;
        }
        return time.getGameTime();
    }

    // Lightweight immutable view of alarm lookup/state for diagnostics.
    static final class AlarmSnapshot {
        final boolean validName;
        final boolean npcResolved;
        final boolean storeResolved;
        final boolean exists;
        final boolean nowAvailable;
        final boolean set;
        final boolean unset;
        final boolean active;
        final boolean passed;
        final boolean ready;

        private AlarmSnapshot(boolean validName,
                              boolean npcResolved,
                              boolean storeResolved,
                              boolean exists,
                              boolean nowAvailable,
                              boolean set,
                              boolean unset,
                              boolean active,
                              boolean passed,
                              boolean ready) {
            this.validName = validName;
            this.npcResolved = npcResolved;
            this.storeResolved = storeResolved;
            this.exists = exists;
            this.nowAvailable = nowAvailable;
            this.set = set;
            this.unset = unset;
            this.active = active;
            this.passed = passed;
            this.ready = ready;
        }

        static AlarmSnapshot invalidName() {
            return new AlarmSnapshot(false, false, false, false, false, false, false, false, false, false);
        }

        static AlarmSnapshot missingNpc(String alarmName) {
            return new AlarmSnapshot(true, false, false, false, false, false, true, false, false, false);
        }

        static AlarmSnapshot missingStore(String alarmName) {
            return new AlarmSnapshot(true, true, false, false, false, false, true, false, false, false);
        }

        static AlarmSnapshot missingAlarm(String alarmName) {
            return new AlarmSnapshot(true, true, true, false, false, false, true, false, false, false);
        }

        static AlarmSnapshot present(String alarmName,
                                     boolean nowAvailable,
                                     boolean set,
                                     boolean unset,
                                     boolean active,
                                     boolean passed,
                                     boolean ready) {
            return new AlarmSnapshot(true, true, true, true, nowAvailable, set, unset, active, passed, ready);
        }
    }
}
