package com.alechilles.alecstamework.npc.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests durable named Tamework alarm component behavior. */
class TameworkAlarmComponentTest {

    @Test
    void namedAlarmSupportsNegativeGameTimeEpochs() {
        TameworkAlarmComponent component = new TameworkAlarmComponent();
        component.setAlarm("Harvest_Ready", -5000L, -1000L);

        assertTrue(component.isAlarmActive("Harvest_Ready", -2000L));
        assertFalse(component.isAlarmActive("Harvest_Ready", -1000L));
        assertFalse(component.isAlarmActive("Harvest_Ready", 0L));
    }

    @Test
    void setAlarmReplacesOnlyMatchingName() {
        TameworkAlarmComponent component = new TameworkAlarmComponent();
        component.setAlarm("Harvest_Ready", 1000L, 5000L);
        component.setAlarm("Breed_Ready", 2000L, 9000L);
        component.setAlarm("Harvest_Ready", 3000L, 7000L);

        TameworkAlarmComponent.AlarmEntry harvest = component.getAlarm("Harvest_Ready");
        TameworkAlarmComponent.AlarmEntry breed = component.getAlarm("Breed_Ready");

        assertEquals(3000L, harvest.getStartedAtMs());
        assertEquals(4000L, harvest.getDurationMs());
        assertEquals(7000L, harvest.getUntilMs());
        assertEquals(2000L, breed.getStartedAtMs());
        assertEquals(7000L, breed.getDurationMs());
        assertEquals(9000L, breed.getUntilMs());
    }

    @Test
    void clearAlarmRemovesOnlyMatchingName() {
        TameworkAlarmComponent component = new TameworkAlarmComponent();
        component.setAlarm("Harvest_Ready", 1000L, 5000L);
        component.setAlarm("Breed_Ready", 2000L, 9000L);

        component.clearAlarm("Harvest_Ready");

        assertNull(component.getAlarm("Harvest_Ready"));
        assertEquals(9000L, component.getAlarm("Breed_Ready").getUntilMs());
    }

    @Test
    void clonePreservesNamedAlarmWindows() {
        TameworkAlarmComponent component = new TameworkAlarmComponent();
        component.setAlarm("Harvest_Ready", -5000L, -1000L);
        component.setAlarm("Breed_Ready", 1000L, 2000L);

        TameworkAlarmComponent cloned = component.clone();

        assertArrayEquals(new String[] {"Harvest_Ready", "Breed_Ready"}, alarmNames(cloned.getAlarms()));
        assertEquals(-5000L, cloned.getAlarm("Harvest_Ready").getStartedAtMs());
        assertEquals(4000L, cloned.getAlarm("Harvest_Ready").getDurationMs());
        assertEquals(-1000L, cloned.getAlarm("Harvest_Ready").getUntilMs());
    }

    @Test
    void alarmDurationSaturatesAcrossSignedTimestampExtremes() {
        TameworkAlarmComponent component = new TameworkAlarmComponent();

        component.setAlarm("Breeding_Cooldown", Long.MIN_VALUE, Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE, component.getAlarm("Breeding_Cooldown").getDurationMs());
    }

    private static String[] alarmNames(TameworkAlarmComponent.AlarmEntry[] alarms) {
        String[] names = new String[alarms.length];
        for (int i = 0; i < alarms.length; i++) {
            names[i] = alarms[i].getName();
        }
        return names;
    }
}
