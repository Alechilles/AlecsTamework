package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
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

    static boolean shouldDeferForHarvestCooldown(@Nullable NPCEntity npc,
                                                 @Nullable Store<EntityStore> store) {
        if (npc == null) {
            return false;
        }
        Alarm alarm = resolveAlarm(npc, resolveHarvestAlarmName());
        return isAlarmActive(alarm, resolveGameTime(store));
    }

    static boolean isAlarmActive(@Nullable Alarm alarm, @Nullable Instant now) {
        if (alarm == null || !alarm.isSet()) {
            return false;
        }
        return now == null || !alarm.hasPassed(now);
    }

    private static Alarm resolveAlarm(NPCEntity npc, String alarmName) {
        if (alarmName == null || alarmName.isBlank()) {
            return null;
        }
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return null;
        }
        return alarmStore.get(npc, alarmName);
    }

    private static Instant resolveGameTime(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        return time != null ? time.getGameTime() : null;
    }

    private static String resolveHarvestAlarmName() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestAlarmName() : null;
        return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_HARVEST_ALARM_NAME;
    }
}
