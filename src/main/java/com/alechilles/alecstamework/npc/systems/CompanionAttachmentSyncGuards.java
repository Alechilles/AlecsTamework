package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.actions.HarvestAlarmTimeBasis;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * Guards automatic attachment sync from fighting temporary base-game visual states.
 */
final class CompanionAttachmentSyncGuards {
    private static final String DEFAULT_HARVEST_ALARM_NAME = "Harvest_Ready";

    private CompanionAttachmentSyncGuards() {
    }

    static boolean shouldDeferForHarvestCooldown(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable NPCEntity npc,
                                                 @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null) {
            return false;
        }
        String alarmName = resolveHarvestAlarmName();
        TameworkAlarmService.Snapshot alarm = TameworkAlarmService.snapshot(npcRef, store, alarmName);
        return alarm.active;
    }

    static boolean isAlarmActive(@Nullable Alarm alarm, @Nullable Instant now) {
        return isAlarmActive(DEFAULT_HARVEST_ALARM_NAME, alarm, now);
    }

    static boolean isAlarmActive(@Nullable String alarmName, @Nullable Alarm alarm, @Nullable Instant now) {
        if (alarm == null || !alarm.isSet()) {
            return false;
        }
        if (HarvestAlarmTimeBasis.isLegacyWallClockHarvestAlarm(alarmName, alarm, now)) {
            return false;
        }
        return now == null || !alarm.hasPassed(now);
    }

    private static String resolveHarvestAlarmName() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestAlarmName() : null;
        return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_HARVEST_ALARM_NAME;
    }
}
