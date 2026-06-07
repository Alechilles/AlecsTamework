package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkHarvestCooldownComponent;
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
 * Owns durable harvest cooldown state for optimized harvest interactions.
 */
final class HarvestCooldownStateService {
    private HarvestCooldownStateService() {
    }

    static boolean isReady(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        return snapshot(npcRef, store).ready;
    }

    static boolean applyCooldown(@Nullable Ref<EntityStore> npcRef,
                                 @Nullable Store<EntityStore> store,
                                 double cooldownSeconds) {
        Snapshot before = snapshot(npcRef, store);
        if (!before.valid || !before.ready || cooldownSeconds <= 0.0) {
            log("apply-blocked", before, before, cooldownSeconds, false);
            return false;
        }
        ComponentType<EntityStore, TameworkHarvestCooldownComponent> type =
                TameworkHarvestCooldownComponent.getComponentType();
        if (type == null || npcRef == null || !npcRef.isValid() || store == null) {
            log("apply-missing-type", before, before, cooldownSeconds, false);
            return false;
        }
        TameworkHarvestCooldownComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            component = new TameworkHarvestCooldownComponent();
        } else {
            component = component.clone();
        }
        long durationMs = secondsToMillis(cooldownSeconds);
        long untilMs = addSaturating(before.nowMs, durationMs);
        component.setCooldownStartedAtMs(durationMs > 0L ? before.nowMs : 0L);
        component.setCooldownDurationMs(durationMs);
        component.setCooldownUntilMs(untilMs);
        store.putComponent(npcRef, type, component);
        Snapshot after = snapshot(npcRef, store);
        log("apply", before, after, cooldownSeconds, true);
        return true;
    }

    @Nonnull
    static Snapshot snapshot(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        long nowMs = resolveTimeMs(store);
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return Snapshot.invalid(nowMs);
        }
        ComponentType<EntityStore, TameworkHarvestCooldownComponent> type =
                TameworkHarvestCooldownComponent.getComponentType();
        if (type == null) {
            return Snapshot.missingType(nowMs);
        }
        TameworkHarvestCooldownComponent component = store.getComponent(npcRef, type);
        if (component == null) {
            return Snapshot.ready(nowMs, 0L, 0L, 0L);
        }
        long untilMs = component.getCooldownUntilMs();
        boolean active = component.isCooldownActive(nowMs);
        return active
                ? Snapshot.active(nowMs, untilMs, component.getCooldownStartedAtMs(), component.getCooldownDurationMs())
                : Snapshot.ready(nowMs, untilMs, component.getCooldownStartedAtMs(), component.getCooldownDurationMs());
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
                            double cooldownSeconds,
                            boolean applied) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkHarvestDebug: durable-cooldown"
                        + " stage=" + stage
                        + " cooldownSeconds=" + cooldownSeconds
                        + " validBefore=" + before.valid
                        + " activeBefore=" + before.active
                        + " readyBefore=" + before.ready
                        + " nowBeforeMs=" + before.nowMs
                        + " untilBeforeMs=" + before.cooldownUntilMs
                        + " validAfter=" + after.valid
                        + " activeAfter=" + after.active
                        + " readyAfter=" + after.ready
                        + " nowAfterMs=" + after.nowMs
                        + " untilAfterMs=" + after.cooldownUntilMs
                        + " applied=" + applied
        );
    }

    static final class Snapshot {
        final boolean valid;
        final boolean componentTypeAvailable;
        final boolean active;
        final boolean ready;
        final long nowMs;
        final long cooldownUntilMs;
        final long cooldownStartedAtMs;
        final long cooldownDurationMs;

        private Snapshot(boolean valid,
                         boolean componentTypeAvailable,
                         boolean active,
                         boolean ready,
                         long nowMs,
                         long cooldownUntilMs,
                         long cooldownStartedAtMs,
                         long cooldownDurationMs) {
            this.valid = valid;
            this.componentTypeAvailable = componentTypeAvailable;
            this.active = active;
            this.ready = ready;
            this.nowMs = nowMs;
            this.cooldownUntilMs = cooldownUntilMs;
            this.cooldownStartedAtMs = cooldownStartedAtMs;
            this.cooldownDurationMs = cooldownDurationMs;
        }

        static Snapshot invalid(long nowMs) {
            return new Snapshot(false, false, false, false, nowMs, 0L, 0L, 0L);
        }

        static Snapshot missingType(long nowMs) {
            return new Snapshot(false, false, false, false, nowMs, 0L, 0L, 0L);
        }

        static Snapshot ready(long nowMs, long cooldownUntilMs, long cooldownStartedAtMs, long cooldownDurationMs) {
            return new Snapshot(true, true, false, true, nowMs, cooldownUntilMs, cooldownStartedAtMs, cooldownDurationMs);
        }

        static Snapshot active(long nowMs, long cooldownUntilMs, long cooldownStartedAtMs, long cooldownDurationMs) {
            return new Snapshot(true, true, true, false, nowMs, cooldownUntilMs, cooldownStartedAtMs, cooldownDurationMs);
        }
    }
}
