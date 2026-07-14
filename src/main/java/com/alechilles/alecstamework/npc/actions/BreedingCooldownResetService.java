package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Clears the component and NPC-alarm halves of a breeding cooldown as one guarded mutation.
 *
 * <p>The alarm is cleared first. If that fails, the component remains on cooldown instead of
 * publishing a contradictory ready state that parent snapshot validation cannot safely admit.
 */
public final class BreedingCooldownResetService {
    private final AlarmClearer alarmClearer;

    public BreedingCooldownResetService() {
        this(BreedingCooldownResetService::clearHytaleAlarm);
    }

    BreedingCooldownResetService(@Nonnull AlarmClearer alarmClearer) {
        this.alarmClearer = alarmClearer;
    }

    /** Clears all cooldown evidence and marks the supplied breeding state ready. */
    public boolean forceReady(@Nonnull Ref<EntityStore> npcRef,
                              @Nullable NPCEntity npc,
                              @Nonnull TameworkBreedingComponent breeding,
                              @Nonnull Store<EntityStore> store) {
        if (!npcRef.isValid() || !alarmClearer.clear(npcRef, npc, store)) {
            return false;
        }
        applyReadyState(breeding);
        return true;
    }

    static void applyReadyState(@Nonnull TameworkBreedingComponent breeding) {
        breeding.setReady(true);
        breeding.setCooldownUntilMs(0L);
        breeding.setCooldownStartedAtMs(0L);
        breeding.setCooldownDurationMs(0L);
        breeding.setLastPartnerUuid(null);
    }

    private static boolean clearHytaleAlarm(@Nonnull Ref<EntityStore> npcRef,
                                            @Nullable NPCEntity npc,
                                            @Nonnull Store<EntityStore> store) {
        if (npc == null) {
            return true;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        Alarm alarm = alarmStore != null
                ? alarmStore.get(npc, BreedingCooldownService.BREEDING_COOLDOWN_ALARM_NAME)
                : null;
        if (alarm == null || !alarm.isSet()) {
            return true;
        }
        try {
            alarm.set(npcRef, null, store);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @FunctionalInterface
    interface AlarmClearer {
        boolean clear(Ref<EntityStore> npcRef, @Nullable NPCEntity npc, Store<EntityStore> store);
    }
}
