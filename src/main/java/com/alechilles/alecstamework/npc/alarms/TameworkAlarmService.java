package com.alechilles.alecstamework.npc.alarms;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads and writes durable named Tamework alarms.
 */
public final class TameworkAlarmService {
    private TameworkAlarmService() {
    }

    public static boolean isReady(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String alarmName) {
        return snapshot(npcRef, store, alarmName).ready;
    }

    public static boolean applyAlarm(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     @Nullable String alarmName,
                                     double durationSeconds) {
        Snapshot before = snapshot(npcRef, store, alarmName);
        if (!before.valid || !before.ready || durationSeconds <= 0.0) {
            log("apply-blocked", before, before, durationSeconds, false);
            return false;
        }
        ComponentType<EntityStore, TameworkAlarmComponent> type = TameworkAlarmComponent.getComponentType();
        if (type == null || npcRef == null || !npcRef.isValid() || store == null) {
            log("apply-missing-type", before, before, durationSeconds, false);
            return false;
        }
        TameworkAlarmComponent component = store.getComponent(npcRef, type);
        component = component == null ? new TameworkAlarmComponent() : component.clone();

        long durationMs = secondsToMillis(durationSeconds);
        long untilMs = addSaturating(before.nowMs, durationMs);
        component.setAlarm(alarmName, before.nowMs, durationMs, untilMs);
        store.putComponent(npcRef, type, component);

        Snapshot after = snapshot(npcRef, store, alarmName);
        log("apply", before, after, durationSeconds, true);
        return true;
    }

    public static void clearAlarm(@Nullable Ref<EntityStore> npcRef,
                                  @Nullable Store<EntityStore> store,
                                  @Nullable String alarmName) {
        if (alarmName == null || alarmName.isBlank() || npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkAlarmComponent> type = TameworkAlarmComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkAlarmComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            return;
        }
        TameworkAlarmComponent clone = component.clone();
        clone.clearAlarm(alarmName);
        store.putComponent(npcRef, type, clone);
    }

    @Nonnull
    public static Snapshot snapshot(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable String alarmName) {
        long nowMs = resolveTimeMs(store);
        if (alarmName == null || alarmName.isBlank()) {
            return Snapshot.invalidName(nowMs);
        }
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return Snapshot.invalid(alarmName, nowMs);
        }
        ComponentType<EntityStore, TameworkAlarmComponent> type = TameworkAlarmComponent.getComponentType();
        if (type == null) {
            return Snapshot.missingType(alarmName, nowMs);
        }
        TameworkAlarmComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            return Snapshot.ready(alarmName, nowMs, 0L, 0L, 0L, false);
        }
        TameworkAlarmComponent.AlarmEntry alarm = component.getAlarm(alarmName);
        if (alarm == null) {
            return Snapshot.ready(alarmName, nowMs, 0L, 0L, 0L, false);
        }
        boolean active = alarm.isActive(nowMs);
        return active
                ? Snapshot.active(alarmName, nowMs, alarm.getUntilMs(), alarm.getStartedAtMs(), alarm.getDurationMs())
                : Snapshot.ready(alarmName, nowMs, alarm.getUntilMs(), alarm.getStartedAtMs(), alarm.getDurationMs(), true);
    }

    private static long resolveTimeMs(@Nullable Store<EntityStore> store) {
        if (store != null) {
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            Instant gameTime = time != null ? time.getGameTime() : null;
            if (gameTime != null) {
                return gameTime.toEpochMilli();
            }
        }
        return System.currentTimeMillis();
    }

    private static long secondsToMillis(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 0L;
        }
        double millis = seconds * 1000.0;
        if (millis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, Math.round(millis));
    }

    private static long addSaturating(long value, long delta) {
        if (delta > 0L && value > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        if (delta < 0L && value < Long.MIN_VALUE - delta) {
            return Long.MIN_VALUE;
        }
        return value + delta;
    }

    private static void log(String stage,
                            Snapshot before,
                            Snapshot after,
                            double durationSeconds,
                            boolean applied) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkAlarmDebug: alarm"
                        + " stage=" + stage
                        + " name=" + before.name
                        + " durationSeconds=" + durationSeconds
                        + " validBefore=" + before.valid
                        + " activeBefore=" + before.active
                        + " readyBefore=" + before.ready
                        + " nowBeforeMs=" + before.nowMs
                        + " untilBeforeMs=" + before.untilMs
                        + " validAfter=" + after.valid
                        + " activeAfter=" + after.active
                        + " readyAfter=" + after.ready
                        + " nowAfterMs=" + after.nowMs
                        + " untilAfterMs=" + after.untilMs
                        + " applied=" + applied
        );
    }

    /** Immutable snapshot of one named Tamework alarm. */
    public static final class Snapshot {
        public final String name;
        public final boolean valid;
        public final boolean componentTypeAvailable;
        public final boolean exists;
        public final boolean active;
        public final boolean passed;
        public final boolean ready;
        public final long nowMs;
        public final long untilMs;
        public final long startedAtMs;
        public final long durationMs;

        private Snapshot(String name,
                         boolean valid,
                         boolean componentTypeAvailable,
                         boolean exists,
                         boolean active,
                         boolean passed,
                         boolean ready,
                         long nowMs,
                         long untilMs,
                         long startedAtMs,
                         long durationMs) {
            this.name = name;
            this.valid = valid;
            this.componentTypeAvailable = componentTypeAvailable;
            this.exists = exists;
            this.active = active;
            this.passed = passed;
            this.ready = ready;
            this.nowMs = nowMs;
            this.untilMs = untilMs;
            this.startedAtMs = startedAtMs;
            this.durationMs = durationMs;
        }

        static Snapshot invalidName(long nowMs) {
            return new Snapshot("", false, false, false, false, false, false, nowMs, 0L, 0L, 0L);
        }

        static Snapshot invalid(String name, long nowMs) {
            return new Snapshot(name, false, false, false, false, false, false, nowMs, 0L, 0L, 0L);
        }

        static Snapshot missingType(String name, long nowMs) {
            return new Snapshot(name, false, false, false, false, false, false, nowMs, 0L, 0L, 0L);
        }

        static Snapshot ready(String name,
                              long nowMs,
                              long untilMs,
                              long startedAtMs,
                              long durationMs,
                              boolean exists) {
            boolean passed = exists && untilMs != 0L && nowMs >= untilMs;
            return new Snapshot(name, true, true, exists, false, passed, true, nowMs, untilMs, startedAtMs, durationMs);
        }

        static Snapshot active(String name, long nowMs, long untilMs, long startedAtMs, long durationMs) {
            return new Snapshot(name, true, true, true, true, false, false, nowMs, untilMs, startedAtMs, durationMs);
        }
    }
}
