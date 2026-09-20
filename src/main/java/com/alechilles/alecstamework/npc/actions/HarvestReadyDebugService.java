package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies harvest-readiness debug mutations through the production alarm policy. */
public final class HarvestReadyDebugService {
    public boolean isReady(@Nullable Ref<EntityStore> npcRef,
                           @Nullable Store<EntityStore> store) {
        return snapshot(npcRef, store).ready;
    }

    @Nonnull
    public Result setReady(@Nullable Ref<EntityStore> npcRef,
                           @Nullable Role role,
                           @Nullable Store<EntityStore> store,
                           boolean ready) {
        TameworkAlarmService.Snapshot before = snapshot(npcRef, store);
        if (!before.valid) {
            return new Result(Status.ALARM_UNAVAILABLE, before, before);
        }
        if (ready) {
            TameworkAlarmService.clearAlarm(npcRef, store, before.name);
        } else {
            double cooldownSeconds = ActionTameworkHarvestAlarm.resolveHarvestCooldownSeconds(
                    npcRef, role, store
            );
            if (cooldownSeconds <= 0.0) {
                return new Result(Status.TIMEOUT_UNAVAILABLE, before, before);
            }
            if (!TameworkAlarmService.replaceAlarm(
                    npcRef, store, before.name, cooldownSeconds
            )) {
                return new Result(Status.MUTATION_FAILED, before, snapshot(npcRef, store));
            }
        }
        TameworkAlarmService.Snapshot after = snapshot(npcRef, store);
        Status status = after.valid && after.ready == ready ? Status.APPLIED : Status.MUTATION_FAILED;
        return new Result(status, before, after);
    }

    @Nonnull
    private static TameworkAlarmService.Snapshot snapshot(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store) {
        return TameworkAlarmService.snapshot(
                npcRef,
                store,
                ActionTameworkHarvestAlarm.resolveHarvestAlarmName()
        );
    }

    public enum Status {
        APPLIED,
        ALARM_UNAVAILABLE,
        TIMEOUT_UNAVAILABLE,
        MUTATION_FAILED
    }

    public record Result(@Nonnull Status status,
                         @Nonnull TameworkAlarmService.Snapshot before,
                         @Nonnull TameworkAlarmService.Snapshot after) {
    }
}
