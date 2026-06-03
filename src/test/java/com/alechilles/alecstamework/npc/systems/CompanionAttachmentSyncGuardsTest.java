package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.server.npc.util.Alarm;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for harvest-cooldown attachment sync suppression. */
class CompanionAttachmentSyncGuardsTest {

    @Test
    void defersSyncWhileHarvestAlarmIsSetAndNotPassed() throws Exception {
        Alarm alarm = alarmSetTo(Instant.parse("2026-05-11T12:10:00Z"));

        assertTrue(CompanionAttachmentSyncGuards.isAlarmActive(alarm, Instant.parse("2026-05-11T12:00:00Z")));
    }

    @Test
    void defersSyncWhenSetHarvestAlarmCannotResolveTime() throws Exception {
        Alarm alarm = alarmSetTo(Instant.parse("2026-05-11T12:10:00Z"));

        assertTrue(CompanionAttachmentSyncGuards.isAlarmActive(alarm, null));
    }

    @Test
    void allowsSyncWhenHarvestAlarmIsUnset() {
        assertFalse(CompanionAttachmentSyncGuards.isAlarmActive(new Alarm(), Instant.parse("2026-05-11T12:00:00Z")));
    }

    @Test
    void allowsSyncWhenHarvestAlarmHasPassed() throws Exception {
        Alarm alarm = alarmSetTo(Instant.parse("2026-05-11T12:00:00Z"));

        assertFalse(CompanionAttachmentSyncGuards.isAlarmActive(alarm, Instant.parse("2026-05-11T12:10:00Z")));
    }

    private static Alarm alarmSetTo(Instant instant) throws Exception {
        Alarm alarm = new Alarm();
        Field instantField = Alarm.class.getDeclaredField("alarmInstant");
        instantField.setAccessible(true);
        instantField.set(alarm, instant);
        return alarm;
    }
}
