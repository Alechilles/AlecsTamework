package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Reads compact linked-panel cooldown snapshots from loaded companion state.
 */
final class CommandLinkedPanelCooldownSnapshotService {
    private static final String DEFAULT_HARVEST_ALARM_NAME = "Harvest_Ready";

    @Nullable
    CooldownSnapshot readBreedingCooldownSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                  @Nullable Store<EntityStore> store,
                                                  @Nullable String resolvedRoleId) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return null;
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null) {
            return new CooldownSnapshot(false, false, false, 0L, 0.0);
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        long until = breeding.getCooldownUntilMs();
        boolean active = until != 0L && now < until;
        if (!active) {
            return new CooldownSnapshot(true, breeding.isEnabled(), false, 0L, 1.0);
        }
        long remainingGameMs = Math.max(0L, until - now);
        long remainingRealMs = BreedingTimeService.toEstimatedRealDurationMs(remainingGameMs, store);
        double ratio = resolveBreedingCooldownRatio(breeding, npcRef, store, resolvedRoleId, remainingGameMs);
        return new CooldownSnapshot(true, breeding.isEnabled(), true, remainingRealMs, ratio);
    }

    @Nullable
    CooldownSnapshot readHarvestCooldownSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        TameworkAlarmService.Snapshot snapshot = TameworkAlarmService.snapshot(npcRef, store, resolveHarvestAlarmName());
        return fromAlarmSnapshot(snapshot, store);
    }

    static CooldownSnapshot fromAlarmWindow(boolean known,
                                           boolean active,
                                           long nowMs,
                                           long untilMs,
                                           long startedAtMs,
                                           long durationMs,
                                           @Nullable Store<EntityStore> store) {
        if (!known) {
            return new CooldownSnapshot(false, true, false, 0L, 0.0);
        }
        if (!active) {
            return new CooldownSnapshot(true, true, false, 0L, 1.0);
        }
        long remainingGameMs = Math.max(0L, untilMs - nowMs);
        long remainingRealMs = BreedingTimeService.toEstimatedRealDurationMs(remainingGameMs, store);
        double ratio = resolveCooldownRatio(remainingGameMs, startedAtMs, durationMs, untilMs);
        return new CooldownSnapshot(true, true, true, remainingRealMs, ratio);
    }

    private static CooldownSnapshot fromAlarmSnapshot(@Nullable TameworkAlarmService.Snapshot snapshot,
                                                     @Nullable Store<EntityStore> store) {
        if (snapshot == null || !snapshot.valid) {
            return new CooldownSnapshot(false, true, false, 0L, 0.0);
        }
        return fromAlarmWindow(
                true,
                snapshot.active,
                snapshot.nowMs,
                snapshot.untilMs,
                snapshot.startedAtMs,
                snapshot.durationMs,
                store
        );
    }

    private double resolveBreedingCooldownRatio(@Nullable TameworkBreedingComponent breeding,
                                                @Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nullable String resolvedRoleId,
                                                long remainingMs) {
        long knownDurationMs = 0L;
        if (breeding != null) {
            knownDurationMs = Math.max(0L, breeding.getCooldownDurationMs());
            if (knownDurationMs <= 0L) {
                knownDurationMs = resolveWindowDurationMs(
                        breeding.getCooldownStartedAtMs(),
                        breeding.getCooldownUntilMs()
                );
            }
        }
        if (knownDurationMs > 0L) {
            return clamp(1.0 - ((double) remainingMs / (double) knownDurationMs));
        }

        TwBreedingConfig config = null;
        if (breeding != null && breeding.getConfigId() != null && !breeding.getConfigId().isBlank()) {
            config = TwBreedingConfig.resolveById(breeding.getConfigId());
        }
        String roleId = resolvedRoleId;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (config == null) {
            config = TwBreedingConfig.resolveForRole(roleId);
        }
        if (config == null || config.resolveCooldowns(roleId) == null || config.resolveTiming(roleId) == null) {
            return 0.0;
        }
        long baseDurationMs = BreedingTimeService.toGameDurationMs(
                config.resolveCooldowns(roleId).getBaseCooldownSeconds(),
                config.resolveTiming(roleId).getTimerBasis(),
                store
        );
        if (baseDurationMs <= 0L) {
            return 0.0;
        }
        return clamp(1.0 - ((double) remainingMs / (double) baseDurationMs));
    }

    private static double resolveCooldownRatio(long remainingMs, long startedAtMs, long durationMs, long untilMs) {
        long knownDurationMs = Math.max(0L, durationMs);
        if (knownDurationMs <= 0L) {
            knownDurationMs = resolveWindowDurationMs(startedAtMs, untilMs);
        }
        if (knownDurationMs <= 0L) {
            return 0.0;
        }
        return clamp(1.0 - ((double) remainingMs / (double) knownDurationMs));
    }

    private static long resolveWindowDurationMs(long startedAtMs, long untilMs) {
        return startedAtMs != 0L && untilMs > startedAtMs ? untilMs - startedAtMs : 0L;
    }

    private static String resolveHarvestAlarmName() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestAlarmName() : null;
        return configured != null && !configured.isBlank() ? configured : DEFAULT_HARVEST_ALARM_NAME;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    static final class CooldownSnapshot {
        final boolean known;
        final boolean enabled;
        final boolean active;
        final long remainingMs;
        final double ratio;

        private CooldownSnapshot(boolean known, boolean enabled, boolean active, long remainingMs, double ratio) {
            this.known = known;
            this.enabled = enabled;
            this.active = active;
            this.remainingMs = Math.max(0L, remainingMs);
            this.ratio = clamp(ratio);
        }
    }
}
